import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { Miniflare, Log, LogLevel } from 'miniflare';

async function runtime(run, bindings = {}) {
  const mf = new Miniflare({
    modules: true, scriptPath: new URL('../dist/index.js', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1'),
    compatibilityDate: '2025-11-11',
    durableObjects: { ASSIST_HUB: { className: 'AssistHub', useSQLite: true } },
    bindings,
    log: new Log(LogLevel.NONE),
  });
  const sockets = [];
  async function connect() {
    const response = await mf.dispatchFetch('https://test.invalid/signal', { headers: { Upgrade: 'websocket' } });
    assert.equal(response.status, 101);
    const ws = response.webSocket; ws.accept(); sockets.push(ws);
    const inbox = [], waits = [];
    ws.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      const index = waits.findIndex(w => w.type === message.type);
      if (index >= 0) { const waiter = waits.splice(index, 1)[0]; clearTimeout(waiter.timer); waiter.resolve(message); }
      else inbox.push(message);
    });
    return {
      ws, inbox,
      send(type, payload = {}, room) {
        ws.send(JSON.stringify({ v: 1, id: crypto.randomUUID(), type, roomId: room?.roomId, sessionId: room?.sessionId, payload }));
      },
      wait(type) {
        const index = inbox.findIndex(m => m.type === type);
        if (index >= 0) return Promise.resolve(inbox.splice(index, 1)[0]);
        return new Promise((resolve, reject) => {
          const waiter = { type, resolve, timer: setTimeout(() => {
            const index = waits.indexOf(waiter); if (index >= 0) waits.splice(index, 1);
            reject(new Error(`Timed out waiting for ${type}`));
          }, 5000) };
          waits.push(waiter);
        });
      },
    };
  }
  async function pending() {
    const owner = await connect(); owner.send('room.create');
    const created = await owner.wait('room.created');
    assert.ok(/^\d{8}$/.test(created.payload.code), 'Room code must have eight digits');
    const viewer = await connect(); viewer.send('room.join', { code: created.payload.code });
    await viewer.wait('join.waiting');
    const request = await owner.wait('join.requested');
    return { owner, viewer, created, request };
  }
  async function accepted() {
    const result = await pending();
    result.owner.send('join.accept', { joinRequestId: result.request.payload.joinRequestId, ownerToken: result.created.payload.ownerToken }, result.created);
    result.session = await result.owner.wait('session.accepted');
    await result.viewer.wait('session.accepted');
    return result;
  }
  try { await run({ mf, connect, pending, accepted }); }
  finally { for (const ws of sockets) { try { ws.close(1000); } catch {} } await mf.dispose(); }
}

test('health explicitly identifies stage 0 with TURN disabled', () => runtime(async ({ mf }) => {
  const response = await mf.dispatchFetch('https://test.invalid/health');
  assert.deepEqual(await response.json(), { ok: true, protocol: 1, stage: 0, turnEnabled: false });
  assert.equal((await mf.dispatchFetch('https://test.invalid/unknown')).status, 404);
}));

test('pending viewer cannot send media signaling or impersonate owner', () => runtime(async ({ pending }) => {
  const { owner, viewer, created } = await pending();
  viewer.send('ice.candidate', { candidate: 'not-real', sdpMid: '0', sdpMLineIndex: 0 }, created);
  assert.equal((await viewer.wait('error')).payload.code, 'STALE_SESSION');
  viewer.send('join.accept', { joinRequestId: 'forged', ownerToken: 'forged' }, created);
  assert.equal((await viewer.wait('error')).payload.code, 'NOT_AUTHORIZED');
  assert.equal(owner.inbox.filter(m => m.type === 'ice.candidate').length, 0);
}));

test('wrong owner credential is rejected and correct approval consumes code', () => runtime(async ({ pending, connect }) => {
  const { owner, viewer, created, request } = await pending();
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: 'wrong' }, created);
  assert.equal((await owner.wait('error')).payload.code, 'NOT_AUTHORIZED');
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  assert.equal((await owner.wait('session.accepted')).payload.role, 'owner');
  assert.equal((await viewer.wait('session.accepted')).payload.role, 'viewer');
  const intruder = await connect(); intruder.send('room.join', { code: created.payload.code });
  assert.equal((await intruder.wait('error')).payload.code, 'ROOM_UNAVAILABLE');
}));

test('rejecting a viewer preserves owner room after close callback', () => runtime(async ({ pending, connect }) => {
  const { owner, viewer, created, request } = await pending();
  owner.send('join.reject', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  await viewer.wait('join.rejected'); await owner.wait('room.waiting');
  const next = await connect(); next.send('room.join', { code: created.payload.code });
  await next.wait('join.waiting'); await owner.wait('join.requested');
  assert.equal(owner.inbox.filter(m => m.type === 'session.ended').length, 0);
}));

test('withdrawn pending viewer does not destroy owner room', () => runtime(async ({ pending, connect }) => {
  const { owner, viewer, created } = await pending(); viewer.ws.close(1000);
  await owner.wait('room.waiting');
  const next = await connect(); next.send('room.join', { code: created.payload.code });
  await next.wait('join.waiting'); await owner.wait('join.requested');
}));

test('authorization gate, offer direction, stale sessions and forwarding', () => runtime(async ({ accepted }) => {
  const { owner, viewer, session } = await accepted();
  owner.send('sdp.offer', { sdp: 'example' }, session);
  assert.equal((await owner.wait('error')).payload.code, 'INVALID_STATE');
  owner.send('capture.ready', {}, session); await viewer.wait('capture.ready');
  viewer.send('sdp.offer', { sdp: 'example' }, session);
  assert.equal((await viewer.wait('error')).payload.code, 'NOT_AUTHORIZED');
  owner.send('sdp.offer', { sdp: 'example-offer' }, session);
  assert.equal((await viewer.wait('sdp.offer')).payload.sdp, 'example-offer');
  viewer.send('sdp.answer', { sdp: 'example-answer' }, session); await owner.wait('sdp.answer');
  viewer.send('ice.candidate', { candidate: 'example-candidate', sdpMid: '0', sdpMLineIndex: 0 }, { ...session, sessionId: 'old' });
  assert.equal((await viewer.wait('error')).payload.code, 'STALE_SESSION');
  viewer.send('ice.candidate', { candidate: 'example-candidate', sdpMid: '0', sdpMLineIndex: 0 }, session);
  assert.equal((await owner.wait('ice.candidate')).payload.candidate, 'example-candidate');
  viewer.send('media.ready', {}, session); await owner.wait('media.ready');
  owner.send('session.end', {}, session); await owner.wait('session.ended'); await viewer.wait('session.ended');
}));

test('simultaneous join attempts select only one pending viewer', () => runtime(async ({ connect }) => {
  const owner = await connect(); owner.send('room.create'); const created = await owner.wait('room.created');
  const a = await connect(), b = await connect();
  a.send('room.join', { code: created.payload.code }); b.send('room.join', { code: created.payload.code });
  await owner.wait('join.requested');
  const outcomes = await Promise.all([a, b].map(async c => {
    const winning = c.wait('join.waiting').then(() => 'pending');
    const losing = c.wait('error').then(m => m.payload.code);
    return Promise.race([winning, losing]);
  }));
  assert.deepEqual(outcomes.sort(), ['ROOM_UNAVAILABLE', 'pending']);
}));

test('guessing is limited and malformed messages never crash the room actor', () => runtime(async ({ connect }) => {
  const client = await connect(); client.ws.send('{');
  assert.equal((await client.wait('error')).payload.code, 'INVALID_MESSAGE');
  for (let i = 0; i < 5; i++) { client.send('room.join', { code: '00000000' }); assert.equal((await client.wait('error')).payload.code, 'ROOM_UNAVAILABLE'); }
  client.send('room.join', { code: '00000000' });
  assert.equal((await client.wait('error')).payload.code, 'RATE_LIMITED');
}));

test('slow media setup can finish after thirty seconds without losing the approved room', () => runtime(async ({ accepted }) => {
  const { owner, viewer, session } = await accepted();
  owner.send('capture.ready', {}, session); await viewer.wait('capture.ready');
  owner.send('sdp.offer', { sdp: 'delayed-offer' }, session); await viewer.wait('sdp.offer');
  // Model accumulated public WSS delay, including an alarm interval. This used
  // to expire an approved session just as the first decoded frame arrived.
  await new Promise(resolve => setTimeout(resolve, 35_000));
  viewer.send('sdp.answer', { sdp: 'delayed-answer' }, session); await owner.wait('sdp.answer');
  viewer.send('media.ready', {}, session); await owner.wait('media.ready');
  owner.send('session.end', {}, session); await owner.wait('session.ended'); await viewer.wait('session.ended');
}));

test('stage 1 TURN config reports enabled health and issues verifiable short-lived credentials', () => runtime(async ({ mf, pending }) => {
  const health = await (await mf.dispatchFetch('https://test.invalid/health')).json();
  assert.deepEqual(health, { ok: true, protocol: 1, stage: 1, turnEnabled: true });
  const { owner, viewer, created, request } = await pending();
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  const ownerAccepted = await owner.wait('session.accepted');
  const viewerAccepted = await viewer.wait('session.accepted');
  for (const message of [ownerAccepted, viewerAccepted]) {
    const ice = message.payload.ice;
    assert.deepEqual(ice.uris, ['turn:turn.example.com:3478?transport=udp', 'turns:turn.example.com:443?transport=tcp']);
    assert.ok(/^\d{10}$/.test(ice.username), 'username must be a unix expiry timestamp');
    assert.ok(Number(ice.username) > Math.floor(Date.now() / 1000) + 3600, 'credentials must outlive the one hour session cap');
    const expected = createHmac('sha1', 'turn-shared-secret-000000').update(ice.username).digest('base64');
    assert.equal(ice.credential, expected, 'credential must be base64 HMAC-SHA1 per Coturn REST API');
  }
}, {
  TURN_URIS: 'turn:turn.example.com:3478?transport=udp, turns:turn.example.com:443?transport=tcp',
  TURN_SECRET: 'turn-shared-secret-000000',
}));

test('malformed TURN configuration silently falls back to stage 0 and never breaks pairing', () => runtime(async ({ mf, pending }) => {
  const health = await (await mf.dispatchFetch('https://test.invalid/health')).json();
  assert.deepEqual(health, { ok: true, protocol: 1, stage: 0, turnEnabled: false });
  const { owner, viewer, created, request } = await pending();
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  const ownerAccepted = await owner.wait('session.accepted');
  assert.equal(ownerAccepted.payload.ice, undefined);
  await viewer.wait('session.accepted');
}, { TURN_URIS: 'http://not-a-turn-uri:3478', TURN_SECRET: 'short' }));

test('static hosted credentials pass through verbatim and stay out of pairing failures', () => runtime(async ({ mf, pending }) => {
  const health = await (await mf.dispatchFetch('https://test.invalid/health')).json();
  assert.deepEqual(health, { ok: true, protocol: 1, stage: 1, turnEnabled: true });
  const { owner, viewer, created, request } = await pending();
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  const ownerAccepted = await owner.wait('session.accepted');
  const viewerAccepted = await viewer.wait('session.accepted');
  for (const message of [ownerAccepted, viewerAccepted]) {
    const ice = message.payload.ice;
    assert.deepEqual(ice.uris, ['turn:hosted.example.com:3478?transport=udp']);
    assert.equal(ice.username, 'hosted-user');
    assert.equal(ice.credential, 'hosted-credential-abc123');
  }
}, {
  TURN_URIS: 'turn:hosted.example.com:3478?transport=udp',
  TURN_USERNAME: 'hosted-user',
  TURN_CREDENTIAL: 'hosted-credential-abc123',
}));

test('half configured static credentials or missing secret fall back to stage 0', () => runtime(async ({ mf, pending }) => {
  const health = await (await mf.dispatchFetch('https://test.invalid/health')).json();
  assert.deepEqual(health, { ok: true, protocol: 1, stage: 0, turnEnabled: false });
  const { owner, viewer, created, request } = await pending();
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  const ownerAccepted = await owner.wait('session.accepted');
  assert.equal(ownerAccepted.payload.ice, undefined);
  await viewer.wait('session.accepted');
}, { TURN_URIS: 'turn:hosted.example.com:3478', TURN_USERNAME: 'hosted-user' }));

test('cloudflare TURN keys enable stage 1 and degrade to direct when the credential API is unreachable', () => runtime(async ({ mf, pending }) => {
  const health = await (await mf.dispatchFetch('https://test.invalid/health')).json();
  assert.deepEqual(health, { ok: true, protocol: 1, stage: 1, turnEnabled: true });
  const { owner, viewer, created, request } = await pending();
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  const ownerAccepted = await owner.wait('session.accepted');
  assert.equal(ownerAccepted.payload.role, 'owner');
  assert.equal(ownerAccepted.payload.ice, undefined, 'failed credential mint must not break or delay pairing semantics');
  await viewer.wait('session.accepted');
}, { TURN_KEY_ID: '0123456789abcdef0123456789abcdef', TURN_KEY_TOKEN: 'cf-turn-api-token-000000' }));
