import { DurableObject } from "cloudflare:workers";
import { digest, parseMessage, ProtocolError, randomCode, requireCondition as need, text } from "./protocol";
import { resolveTurnPlan, turnGrant } from "./turn";
import type { TurnGrant } from "./turn";
import type { Env, Message } from "./protocol";

type Role = "unbound" | "owner" | "viewer";
interface Client { id: string; ipHash: string; role: Role; roomId?: string; createdAt: number }
interface Room {
  id: string; codeKey: string; ownerId: string; ownerTokenHash: string;
  viewerId?: string; joinRequestId?: string; sessionId?: string;
  state: "waiting" | "pending" | "accepted" | "negotiating" | "active";
  expiresAt: number; deadline: number; attempts: number; offerSent: boolean; answerSent: boolean;
}
const PAIR_TTL = 5 * 60_000;
const SESSION_TTL = 60 * 60_000;

export class AssistHub extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];
    const metadata: Client = {
      id: crypto.randomUUID(), ipHash: await digest(request.headers.get("CF-Connecting-IP") ?? "local"),
      role: "unbound", createdAt: Date.now(),
    };
    // Check after hashing: no await may interleave another accept between this check and acceptWebSocket.
    if (this.ctx.getWebSockets().length >= 32) return new Response("Capacity reached", { status: 503 });
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(metadata);
    await this.armAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    await this.ctx.blockConcurrencyWhile(async () => {
      let requestId: string | undefined;
      try {
        const m = parseMessage(raw); requestId = m.id;
        const client = ws.deserializeAttachment() as Client;
        await this.rateLimit(`messages:${client.id}`, 240, 60_000);
        await this.handle(ws, client, m);
      } catch (error) {
        this.send(ws, "error", { code: error instanceof ProtocolError ? error.code : "INTERNAL_ERROR" }, undefined, requestId);
      }
    });
  }

  private async handle(ws: WebSocket, client: Client, m: Message): Promise<void> {
    if (m.type === "room.create") {
      need(client.role === "unbound", "INVALID_STATE");
      await this.rateLimit(`create:${client.ipHash}`, 10, 60 * 60_000);
      const rooms = await this.ctx.storage.list<Room>({ prefix: "room:" });
      need(rooms.size < 5, "CAPACITY_REACHED");
      let code = randomCode(), codeKey = `code:${await digest(code)}`;
      while (await this.ctx.storage.get(codeKey)) { code = randomCode(); codeKey = `code:${await digest(code)}`; }
      const token = crypto.randomUUID() + crypto.randomUUID();
      const room: Room = {
        id: crypto.randomUUID(), codeKey, ownerId: client.id, ownerTokenHash: await digest(token),
        state: "waiting", expiresAt: Date.now() + PAIR_TTL, deadline: Date.now() + PAIR_TTL,
        attempts: 0, offerSent: false, answerSent: false,
      };
      await this.ctx.storage.put({ [`room:${room.id}`]: room, [codeKey]: room.id });
      client.role = "owner"; client.roomId = room.id; ws.serializeAttachment(client);
      this.send(ws, "room.created", { code, ownerToken: token, expiresAt: room.expiresAt }, room, m.id);
      return;
    }
    if (m.type === "room.join") {
      need(client.role === "unbound", "INVALID_STATE");
      await this.rateLimit(`join:${client.ipHash}`, 5, 60_000);
      const code = text(m.payload.code, 8); need(/^\d{8}$/.test(code), "ROOM_UNAVAILABLE");
      const roomId = await this.ctx.storage.get<string>(`code:${await digest(code)}`);
      const room = roomId ? await this.ctx.storage.get<Room>(`room:${roomId}`) : undefined;
      need(room && room.state === "waiting" && room.expiresAt > Date.now(), "ROOM_UNAVAILABLE");
      need(this.find(room.ownerId), "ROOM_UNAVAILABLE");
      need(room.attempts < 20, "ROOM_UNAVAILABLE");
      room.attempts++; room.state = "pending"; room.viewerId = client.id;
      room.joinRequestId = crypto.randomUUID(); room.deadline = Math.min(room.expiresAt, Date.now() + 60_000);
      await this.save(room);
      client.role = "viewer"; client.roomId = room.id; ws.serializeAttachment(client);
      this.send(ws, "join.waiting", {}, room, m.id);
      this.send(this.find(room.ownerId), "join.requested", { joinRequestId: room.joinRequestId }, room);
      return;
    }
    need(client.roomId && m.roomId === client.roomId, "NOT_AUTHORIZED");
    const room = await this.ctx.storage.get<Room>(`room:${client.roomId}`);
    need(room, "STALE_SESSION");
    if (room.deadline <= Date.now()) { await this.endRoom(room, "timeout"); throw new ProtocolError("ROOM_UNAVAILABLE"); }
    need(client.id === room.ownerId || client.id === room.viewerId, "NOT_AUTHORIZED");
    if (m.type === "join.accept" || m.type === "join.reject") {
      need(client.id === room.ownerId && client.role === "owner", "NOT_AUTHORIZED");
      need(room.state === "pending" && m.payload.joinRequestId === room.joinRequestId, "INVALID_STATE");
      need(await digest(text(m.payload.ownerToken, 200)) === room.ownerTokenHash, "NOT_AUTHORIZED");
      if (m.type === "join.reject") { await this.resetPending(room, "rejected"); return; }
      need(this.find(room.viewerId), "ROOM_UNAVAILABLE");
      room.state = "accepted"; room.sessionId = crypto.randomUUID(); room.deadline = Date.now() + 120_000;
      await this.ctx.storage.delete(room.codeKey); await this.save(room);
      // Stage 1 TURN: credentials cover the 1 hour session cap plus negotiation margin.
      // Stage 0 (no TURN_URIS/TURN_SECRET) sends the payload unchanged.
      const plan = resolveTurnPlan(this.env);
      const grant: TurnGrant | undefined = plan ? await turnGrant(plan, this.env, 7200) : undefined;
      const acceptedPayload = (role: string): Record<string, unknown> => grant ? { role, ice: grant } : { role };
      this.send(this.find(room.ownerId), "session.accepted", acceptedPayload("owner"), room, m.id);
      this.send(this.find(room.viewerId), "session.accepted", acceptedPayload("viewer"), room);
      return;
    }
    if (m.type === "session.end") {
      if (room.sessionId) need(m.sessionId === room.sessionId, "STALE_SESSION");
      if (room.state === "pending" && client.id === room.viewerId) await this.resetPending(room, "withdrawn");
      else await this.endRoom(room, "user_stop");
      return;
    }
    need(room.sessionId && m.sessionId === room.sessionId, "STALE_SESSION");
    if (m.type === "capture.ready") {
      need(client.id === room.ownerId, "NOT_AUTHORIZED"); need(room.state === "accepted", "INVALID_STATE");
      room.state = "negotiating"; room.deadline = Date.now() + 90_000; await this.save(room);
      this.send(this.find(room.viewerId), "capture.ready", {}, room); return;
    }
    need(room.state === "negotiating" || room.state === "active", "INVALID_STATE");
    let payload: Record<string, unknown>;
    if (m.type === "sdp.offer") {
      need(client.id === room.ownerId, "NOT_AUTHORIZED"); need(!room.offerSent, "INVALID_STATE");
      payload = { type: "offer", sdp: text(m.payload.sdp, 60_000) }; room.offerSent = true; await this.save(room);
    } else if (m.type === "sdp.answer") {
      need(client.id === room.viewerId, "NOT_AUTHORIZED"); need(room.offerSent && !room.answerSent, "INVALID_STATE");
      payload = { type: "answer", sdp: text(m.payload.sdp, 60_000) }; room.answerSent = true; await this.save(room);
    } else if (m.type === "ice.candidate") {
      need(Number.isInteger(m.payload.sdpMLineIndex) && Number(m.payload.sdpMLineIndex) >= 0 && Number(m.payload.sdpMLineIndex) <= 16, "INVALID_MESSAGE");
      payload = { candidate: text(m.payload.candidate, 8_192), sdpMid: text(m.payload.sdpMid, 64), sdpMLineIndex: m.payload.sdpMLineIndex };
    } else if (m.type === "media.ready") {
      need(client.id === room.viewerId && room.answerSent && room.state === "negotiating", "INVALID_STATE");
      room.state = "active"; room.deadline = Date.now() + SESSION_TTL; await this.save(room); payload = {};
    } else throw new ProtocolError("UNKNOWN_MESSAGE");
    const recipient = client.id === room.ownerId ? room.viewerId : room.ownerId;
    this.send(this.find(recipient), m.type, payload, room);
  }

  private clients(): Array<{ ws: WebSocket; client: Client }> {
    return this.ctx.getWebSockets().map(ws => ({ ws, client: ws.deserializeAttachment() as Client }));
  }
  private find(id?: string): WebSocket | undefined { return this.clients().find(c => c.client.id === id)?.ws; }
  private send(ws: WebSocket | undefined, type: string, payload: Record<string, unknown>, room?: Room, replyTo?: string): void {
    if (!ws) return;
    try { ws.send(JSON.stringify({ v: 1, id: crypto.randomUUID(), type, roomId: room?.id, sessionId: room?.sessionId, replyTo, payload })); }
    catch { /* Close/error callbacks or room expiry will finish cleanup. */ }
  }
  private save(room: Room): Promise<void> { return this.ctx.storage.put(`room:${room.id}`, room); }
  private async rateLimit(key: string, max: number, window: number): Promise<void> {
    const now = Date.now();
    const times = (await this.ctx.storage.get<number[]>(`rate:${key}`) ?? []).filter(t => now - t < window);
    need(times.length < max, "RATE_LIMITED"); times.push(now);
    await this.ctx.storage.put(`rate:${key}`, times);
  }
  private async resetPending(room: Room, reason: string): Promise<void> {
    const viewer = this.find(room.viewerId);
    if (viewer) { this.send(viewer, "join.rejected", { reason }, room); viewer.close(1000, reason); }
    delete room.viewerId; delete room.joinRequestId; room.state = "waiting"; room.deadline = room.expiresAt;
    await this.save(room); this.send(this.find(room.ownerId), "room.waiting", {}, room);
  }
  private async endRoom(room: Room, reason: string): Promise<void> {
    await this.ctx.storage.delete([`room:${room.id}`, room.codeKey]);
    for (const { ws, client } of this.clients()) if (client.roomId === room.id) {
      this.send(ws, "session.ended", { reason }, room); ws.close(1000, "session ended");
    }
  }
  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try { ws.close(code === 1006 ? 1000 : code); } catch { /* Already closed. */ }
    await this.ctx.blockConcurrencyWhile(async () => {
      const client = ws.deserializeAttachment() as Client;
      await this.ctx.storage.delete(`rate:messages:${client.id}`);
      const room = client.roomId ? await this.ctx.storage.get<Room>(`room:${client.roomId}`) : undefined;
      if (room && (client.id === room.ownerId || client.id === room.viewerId)) {
        if (room.state === "pending" && client.id === room.viewerId) await this.resetPending(room, "withdrawn");
        else await this.endRoom(room, "peer_disconnected");
      }
    });
  }
  async webSocketError(ws: WebSocket): Promise<void> { await this.webSocketClose(ws, 1011); }
  private async armAlarm(): Promise<void> {
    if (await this.ctx.storage.getAlarm() === null) await this.ctx.storage.setAlarm(Date.now() + 30_000);
  }
  async alarm(): Promise<void> {
    await this.ctx.blockConcurrencyWhile(async () => {
      const now = Date.now();
      const rooms = await this.ctx.storage.list<Room>({ prefix: "room:" });
      for (const room of rooms.values()) if (room.deadline <= now) {
        if (room.state === "pending" && room.expiresAt > now) await this.resetPending(room, "timeout");
        else await this.endRoom(room, "timeout");
      }
      for (const { ws, client } of this.clients()) if (client.role === "unbound" && now - client.createdAt > 30_000) ws.close(1000, "idle");
      const rates = await this.ctx.storage.list<number[]>({ prefix: "rate:" });
      for (const [key, times] of rates) if (!times.some(t => now - t < 60 * 60_000)) await this.ctx.storage.delete(key);
      if (this.ctx.getWebSockets().length > 0 || (await this.ctx.storage.list({ prefix: "room:", limit: 1 })).size > 0 || (await this.ctx.storage.list({ prefix: "rate:", limit: 1 })).size > 0) {
        await this.ctx.storage.setAlarm(now + 30_000);
      }
    });
  }
}
