import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { createRelay } from '../src/server.js';

const quiet = () => {};

/** Connects and collects messages; `next(type)` waits for the next message of that type. */
function client(url, headers = {}) {
  const ws = new WebSocket(url, { headers });
  const inbox = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d.toString());
    const i = waiters.findIndex((w) => w.type === m.t);
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m);
    else inbox.push(m);
  });
  const closed = new Promise((resolve) => ws.on('close', (code) => resolve(code)));
  return {
    ws,
    closed,
    open: () => new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); }),
    send: (o) => ws.send(typeof o === 'string' ? o : JSON.stringify(o)),
    next: (type, ms = 2000) => {
      const i = inbox.findIndex((m) => m.t === type);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        const w = { type, resolve };
        waiters.push(w);
        setTimeout(() => reject(new Error(`timeout waiting for ${type}; inbox=${JSON.stringify(inbox)}`)), ms);
      });
    },
    has: (type) => inbox.some((m) => m.t === type),
    close: () => ws.close(),
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function startRelay(opts = {}) {
  const relay = createRelay({ log: quiet, hostSecret: '', inviteTtlMs: 60_000, ctlGraceMs: 1_000, hostGraceMs: 1_000, redeemPerMinute: 1000, ...opts });
  const port = await relay.listen(0);
  return { relay, base: `ws://127.0.0.1:${port}`, http: `http://127.0.0.1:${port}` };
}

async function hostWithInvite(base, secret = '') {
  const host = client(`${base}/ws/host`);
  await host.open();
  host.send({ t: 'hello', v: 1, secret });
  const ready = await host.next('ready');
  return { host, ready };
}

async function controller(base, token, t = 'redeem') {
  const ctl = client(`${base}/ws/ctl`);
  await ctl.open();
  ctl.send({ t, token });
  return ctl;
}

let env;
before(async () => { env = await startRelay(); });
after(async () => { await env.relay.close(); });

test('host gets a session and a one-time invite', async () => {
  const { host, ready } = await hostWithInvite(env.base);
  assert.equal(ready.controller, 'none');
  assert.match(ready.invite, /^[A-Za-z0-9_-]{40,}$/);
  assert.match(ready.hostResume, /^[A-Za-z0-9_-]{40,}$/);
  host.close();
});

test('invite works once, commands only after approval, state is relayed', async () => {
  const { host, ready } = await hostWithInvite(env.base);
  const ctl = await controller(env.base, ready.invite);
  const joined = await ctl.next('joined');
  assert.equal(joined.approval, 'pending');
  assert.equal((await host.next('ctl_joined')).resumed, false);

  // Before approval: commands are dropped, heartbeats pass.
  ctl.send({ t: 'cmd', c: { k: 'speed', v: 50 } });
  ctl.send({ t: 'hb' });
  const hb = await host.next('ctl');
  assert.deepEqual(hb.m, { t: 'hb' });

  // Second use of the same invite fails.
  const again = await controller(env.base, ready.invite);
  assert.equal((await again.next('error')).code, 'invalid');

  host.send({ t: 'approve' });
  await ctl.next('approved');
  ctl.send({ t: 'cmd', c: { k: 'speed', v: 40 } });
  const fwd = await host.next('ctl');
  assert.deepEqual(fwd.m, { t: 'cmd', c: { k: 'speed', v: 40 } });

  host.send({ t: 'state', s: { speed: 40, mode: 'ANAL' } });
  assert.deepEqual((await ctl.next('state')).s, { speed: 40, mode: 'ANAL' });
  host.send({ t: 'notice', text: 'hej' });
  assert.equal((await ctl.next('notice')).text, 'hej');
  host.close();
  ctl.close();
});

test('only one controller at a time', async () => {
  const { host, ready } = await hostWithInvite(env.base);
  const ctl = await controller(env.base, ready.invite);
  await ctl.next('joined');
  host.send({ t: 'new_invite' });
  const inv = await host.next('invite');
  const second = await controller(env.base, inv.invite);
  assert.equal((await second.next('error')).code, 'busy');
  host.close();
  ctl.close();
});

test('kick revokes the controller and its resume token', async () => {
  const { host, ready } = await hostWithInvite(env.base);
  const ctl = await controller(env.base, ready.invite);
  const joined = await ctl.next('joined');
  host.send({ t: 'approve' });
  await ctl.next('approved');
  host.send({ t: 'kick' });
  await ctl.next('kicked');
  await ctl.closed;
  const back = await controller(env.base, joined.ctlResume, 'resume');
  assert.equal((await back.next('error')).code, 'expired');
  host.close();
});

test('controller can resume within grace with a rotated token, not after', async () => {
  const { host, ready } = await hostWithInvite(env.base);
  const ctl = await controller(env.base, ready.invite);
  const joined = await ctl.next('joined');
  host.send({ t: 'approve' });
  await ctl.next('approved');
  await host.next('ctl_joined');
  ctl.close();
  await host.next('ctl_left');

  const back = await controller(env.base, joined.ctlResume, 'resume');
  const rejoined = await back.next('joined');
  assert.equal(rejoined.approval, 'approved');
  assert.notEqual(rejoined.ctlResume, joined.ctlResume);
  assert.equal((await host.next('ctl_joined')).resumed, true);

  // The old resume token is gone.
  back.close();
  await host.next('ctl_left');
  const stale = await controller(env.base, joined.ctlResume, 'resume');
  assert.equal((await stale.next('error')).code, 'expired');

  // After the grace period the new one is gone too.
  await sleep(1_200);
  const late = await controller(env.base, rejoined.ctlResume, 'resume');
  assert.equal((await late.next('error')).code, 'expired');
  host.close();
});

test('host resume keeps the session; end tells the controller', async () => {
  const { host, ready } = await hostWithInvite(env.base);
  const ctl = await controller(env.base, ready.invite);
  await ctl.next('joined');
  host.send({ t: 'approve' });
  await ctl.next('approved');
  host.ws.terminate();
  await ctl.next('host_offline');

  const host2 = client(`${env.base}/ws/host`);
  await host2.open();
  host2.send({ t: 'hello', v: 1, resume: ready.hostResume });
  const r2 = await host2.next('ready');
  assert.equal(r2.session, ready.session);
  assert.equal(r2.controller, 'active');
  await ctl.next('host_online');

  host2.send({ t: 'end' });
  await ctl.next('ended');
  await ctl.closed;
});

test('unknown host resume says session_gone', async () => {
  const host = client(`${env.base}/ws/host`);
  await host.open();
  host.send({ t: 'hello', v: 1, resume: 'x'.repeat(43) });
  assert.equal((await host.next('error')).code, 'session_gone');
  host.close();
});

test('expired invites are refused', async () => {
  const short = await startRelay({ inviteTtlMs: 100 });
  const { host, ready } = await hostWithInvite(short.base);
  await sleep(200);
  const ctl = await controller(short.base, ready.invite);
  assert.equal((await ctl.next('error')).code, 'invalid');
  host.close();
  await short.relay.close();
});

test('host secret is enforced when configured', async () => {
  const sec = await startRelay({ hostSecret: 'hemligt' });
  const bad = client(`${sec.base}/ws/host`);
  await bad.open();
  bad.send({ t: 'hello', v: 1, secret: 'fel' });
  assert.equal((await bad.next('error')).code, 'bad_secret');
  const { host, ready } = await hostWithInvite(sec.base, 'hemligt');
  assert.ok(ready.invite);
  host.close();
  await sec.relay.close();
});

test('cross-site controller connections are rejected', async () => {
  const ws = new WebSocket(`${env.base}/ws/ctl`, { headers: { Origin: 'https://evil.example' } });
  const err = await new Promise((resolve) => ws.on('error', resolve));
  assert.match(String(err.message), /403/);
});

test('redeem attempts are rate limited', async () => {
  const rl = await startRelay({ redeemPerMinute: 3 });
  for (let i = 0; i < 3; i++) {
    const c = await controller(rl.base, 'a'.repeat(43));
    assert.equal((await c.next('error')).code, 'invalid');
  }
  const c = await controller(rl.base, 'a'.repeat(43));
  assert.equal((await c.next('error')).code, 'rate');
  await rl.relay.close();
});

test('http: health, controller page with strict headers, nothing else', async () => {
  const h = await fetch(`${env.http}/healthz`);
  assert.equal(await h.text(), 'ok');
  const page = await fetch(`${env.http}/r/`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /default-src 'self'/);
  assert.equal(page.headers.get('referrer-policy'), 'no-referrer');
  assert.match(await page.text(), /HISMITH REMOTE/);
  assert.equal((await fetch(`${env.http}/r/../src/server.js`)).status, 404);
  assert.equal((await fetch(`${env.http}/etc/passwd`)).status, 404);
});

test('api/info tells the app what it is talking to', async () => {
  const info = await (await fetch(`${env.http}/api/info`)).json();
  assert.equal(info.name, 'hismith-relay');
  assert.equal(info.secretRequired, false);
  assert.match(info.version, /^\d+\.\d+\.\d+$/);
  const sec = await startRelay({ hostSecret: 'x' });
  assert.equal((await (await fetch(`${sec.http}/api/info`)).json()).secretRequired, true);
  await sec.relay.close();
});
