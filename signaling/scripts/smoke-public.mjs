// Connects two test clients to our own endpoint; never prints room codes or credentials.
import assert from 'node:assert/strict';
import dns from 'node:dns';
import { isIP } from 'node:net';
import WebSocket from 'ws';
const base = new URL(process.argv[2] ?? 'https://screenguideqi-2f916560.pages.dev');
assert.equal(base.protocol, 'https:');
// Optional diagnostic override affects this test process only, and never disables TLS checks.
const override = process.env.SGQI_SMOKE_IP;
if (override) {
  const family = isIP(override); assert.ok(family, 'SGQI_SMOKE_IP must be an IP address');
  const original = dns.lookup;
  dns.lookup = function (host, options, callback) {
    if (host !== base.hostname) return original.apply(this, arguments);
    if (typeof options === 'function') { callback = options; options = {}; }
    queueMicrotask(() => options?.all ? callback(null, [{ address: override, family }]) : callback(null, override, family));
  };
  console.log('Process-local DNS override enabled; this does not verify system DNS or phone reachability.');
}
const health = await fetch(new URL('/health', base), { signal: AbortSignal.timeout(20_000) });
assert.equal(health.status, 200);
assert.deepEqual(await health.json(), { ok: true, protocol: 1, stage: 0, turnEnabled: false });
console.log('Public HTTPS health passed; TURN disabled.');
const sockets = [];
async function connect() {
  const url = new URL('/signal', base); url.protocol = 'wss:';
  const ws = new WebSocket(url); sockets.push(ws);
  const inbox = [], waits = [];
  let failure;
  let closeCode;
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.type === 'error') {
      failure = new Error('Public service rejected request: ' + String(message.payload?.code ?? 'UNKNOWN').slice(0, 80));
      for (const waiter of waits.splice(0)) { clearTimeout(waiter.timer); waiter.reject(failure); }
      return;
    }
    const index = waits.findIndex(w => w.type === message.type);
    if (index >= 0) { const waiter = waits.splice(index, 1)[0]; clearTimeout(waiter.timer); waiter.resolve(message); }
    else inbox.push(message);
  });
  ws.addEventListener('close', event => {
    closeCode = event.code;
    for (const waiter of waits.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`Public WSS closed before ${waiter.type}: code ${event.code}`));
    }
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WSS handshake timeout')), 20_000);
    ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
    ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('WSS handshake failed')); }, { once: true });
  });
  return {
    send(type, payload = {}, room) { ws.send(JSON.stringify({ v: 1, id: crypto.randomUUID(), type, roomId: room?.roomId, sessionId: room?.sessionId, payload })); },
    wait(type) {
      if (failure) return Promise.reject(failure);
      const index = inbox.findIndex(m => m.type === type);
      if (index >= 0) return Promise.resolve(inbox.splice(index, 1)[0]);
      if (closeCode !== undefined) return Promise.reject(new Error(`Public WSS already closed: code ${closeCode}`));
      return new Promise((resolve, reject) => {
        const waiter = { type, resolve, reject, timer: setTimeout(() => {
          const index = waits.indexOf(waiter); if (index >= 0) waits.splice(index, 1);
          reject(new Error(`Public protocol timeout: ${type}`));
        }, 20_000) };
        waits.push(waiter);
      });
    },
  };
}
try {
  const owner = await connect(), viewer = await connect();
  console.log('Two public WSS clients connected.');
  owner.send('room.create'); const created = await owner.wait('room.created');
  viewer.send('room.join', { code: created.payload.code }); await viewer.wait('join.waiting');
  const request = await owner.wait('join.requested');
  owner.send('join.accept', { joinRequestId: request.payload.joinRequestId, ownerToken: created.payload.ownerToken }, created);
  const session = await owner.wait('session.accepted'); await viewer.wait('session.accepted');
  console.log('Public room creation, join request and owner confirmation passed.');
  owner.send('session.end', {}, session); await owner.wait('session.ended'); await viewer.wait('session.ended');
  console.log('Public WSS pairing, owner confirmation, and two-sided cleanup passed.');
} finally {
  for (const ws of sockets) if (ws.readyState < 2) ws.close(1000);
}
