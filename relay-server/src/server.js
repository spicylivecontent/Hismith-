// Hismith Smart Session – minimal relay for the one-time remote link.
//
// The phone ("host") and the browser holding the link ("controller") both connect here over
// WSS. The server only knows opaque session ids, hashed one-time tokens and who is connected.
// It never sees devices, modes, lube amounts or history, and it never decides anything about
// motion: the phone validates every command and enforces every limit.
//
// Tokens: 32 random bytes, base64url. Only SHA-256 hashes are stored. The invite works once;
// redeeming it gives the controller a resume token that only works for a short time after a
// disconnect. Only one controller per session.

import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const PUBLIC_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
const VERSION = JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'package.json'), 'utf8')).version;

const STATIC = {
  '/r/': ['r/index.html', 'text/html; charset=utf-8'],
  '/r/index.html': ['r/index.html', 'text/html; charset=utf-8'],
  '/r/app.js': ['r/app.js', 'text/javascript; charset=utf-8'],
  '/r/style.css': ['r/style.css', 'text/css; charset=utf-8'],
};

export function defaultConfig(env = process.env) {
  return {
    port: Number(env.PORT || 8080),
    hostSecret: env.HOST_SECRET || '',
    inviteTtlMs: Number(env.INVITE_TTL_SEC || 900) * 1000,
    hostGraceMs: Number(env.HOST_GRACE_SEC || 60) * 1000,
    ctlGraceMs: Number(env.CTL_GRACE_SEC || 15) * 1000,
    maxSessions: Number(env.MAX_SESSIONS || 200),
    trustProxy: env.TRUST_PROXY === '1',
    helloTimeoutMs: 5000,
    pingIntervalMs: 15000,
    redeemPerMinute: 20,
    log: env.QUIET === '1' ? () => {} : (...a) => console.log(new Date().toISOString(), ...a),
  };
}

const newToken = () => crypto.randomBytes(32).toString('base64url');
const hash = (t) => crypto.createHash('sha256').update(String(t)).digest('hex');
const isToken = (t) => typeof t === 'string' && t.length >= 20 && t.length <= 100 && /^[A-Za-z0-9_-]+$/.test(t);

function safeEqual(a, b) {
  const ha = Buffer.from(hash(a), 'hex');
  const hb = Buffer.from(hash(b), 'hex');
  return crypto.timingSafeEqual(ha, hb);
}

function send(ws, obj) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}

function parse(data) {
  try {
    const m = JSON.parse(data.toString());
    return m && typeof m === 'object' && !Array.isArray(m) ? m : null;
  } catch {
    return null;
  }
}

export function createRelay(overrides = {}) {
  const cfg = { ...defaultConfig(), ...overrides };
  const sessions = new Map(); // id -> session
  const invites = new Map(); // hash -> id
  const hostResumes = new Map(); // hash -> id
  const ctlResumes = new Map(); // hash -> id
  const redeemHits = new Map(); // ip -> [timestamps]

  function issueInvite(s) {
    if (s.inviteHash) invites.delete(s.inviteHash);
    const token = newToken();
    s.inviteHash = hash(token);
    s.inviteExpires = Date.now() + cfg.inviteTtlMs;
    invites.set(s.inviteHash, s.id);
    return token;
  }

  function revokeInvite(s) {
    if (s.inviteHash) invites.delete(s.inviteHash);
    s.inviteHash = null;
    s.inviteExpires = 0;
  }

  function revokeCtlResume(s) {
    if (s.ctlResumeHash) ctlResumes.delete(s.ctlResumeHash);
    s.ctlResumeHash = null;
  }

  function controllerState(s) {
    if (!s.ctl) return 'none';
    return s.ctlApproved ? 'active' : 'pending';
  }

  function inviteValid(s) {
    return !!s.inviteHash && s.inviteExpires > Date.now();
  }

  function destroy(s, reason) {
    if (!sessions.has(s.id)) return;
    sessions.delete(s.id);
    revokeInvite(s);
    revokeCtlResume(s);
    if (s.hostResumeHash) hostResumes.delete(s.hostResumeHash);
    clearTimeout(s.hostTimer);
    clearTimeout(s.ctlTimer);
    if (s.ctl) {
      send(s.ctl, { t: 'ended' });
      s.ctl.close(1000, 'ended');
    }
    if (s.host) s.host.close(1000, 'ended');
    cfg.log(`session ended (${reason}); active=${sessions.size}`);
  }

  function kickController(s, why) {
    revokeCtlResume(s);
    s.ctlApproved = false;
    clearTimeout(s.ctlTimer);
    if (s.ctl) {
      send(s.ctl, { t: why });
      const c = s.ctl;
      s.ctl = null;
      c.close(1000, why);
    }
    s.ctlOfflineSince = 0;
  }

  function clientIp(req) {
    if (cfg.trustProxy) {
      const xf = req.headers['x-forwarded-for'];
      if (typeof xf === 'string' && xf.length) return xf.split(',')[0].trim();
    }
    return req.socket.remoteAddress || '?';
  }

  function rateLimited(ip) {
    const now = Date.now();
    const list = (redeemHits.get(ip) || []).filter((t) => now - t < 60_000);
    list.push(now);
    redeemHits.set(ip, list);
    if (redeemHits.size > 5000) redeemHits.clear();
    return list.length > cfg.redeemPerMinute;
  }

  // ------------------------------------------------------------------ host

  function onHost(ws) {
    let session = null;
    const helloTimer = setTimeout(() => ws.close(4000, 'no hello'), cfg.helloTimeoutMs);

    ws.on('message', (data) => {
      const m = parse(data);
      if (!m) return;
      if (!session) {
        if (m.t !== 'hello') return;
        if (cfg.hostSecret && !safeEqual(String(m.secret || ''), cfg.hostSecret)) {
          send(ws, { t: 'error', code: 'bad_secret', msg: 'Fel värdhemlighet' });
          ws.close(4003, 'bad secret');
          return;
        }
        if (m.resume !== undefined) {
          const id = isToken(m.resume) ? hostResumes.get(hash(m.resume)) : undefined;
          const s = id && sessions.get(id);
          if (!s) {
            send(ws, { t: 'error', code: 'session_gone', msg: 'Sessionen har gått ut' });
            return;
          }
          clearTimeout(helloTimer);
          if (s.host && s.host !== ws) s.host.close(4001, 'replaced');
          session = s;
          s.host = ws;
          s.hostOfflineSince = 0;
          clearTimeout(s.hostTimer);
          send(ws, { t: 'ready', session: s.id, inviteValid: inviteValid(s), inviteTtlSec: Math.max(0, Math.round((s.inviteExpires - Date.now()) / 1000)), controller: controllerState(s) });
          if (s.ctl) {
            send(s.ctl, { t: 'host_online' });
            if (!s.ctlApproved) send(ws, { t: 'ctl_joined', resumed: false });
          }
          return;
        }
        if (sessions.size >= cfg.maxSessions) {
          send(ws, { t: 'error', code: 'full', msg: 'Servern är full' });
          ws.close(4004, 'full');
          return;
        }
        clearTimeout(helloTimer);
        const hostResume = newToken();
        session = {
          id: crypto.randomUUID(),
          host: ws,
          hostResumeHash: hash(hostResume),
          hostOfflineSince: 0,
          hostTimer: null,
          inviteHash: null,
          inviteExpires: 0,
          ctl: null,
          ctlApproved: false,
          ctlResumeHash: null,
          ctlOfflineSince: 0,
          ctlTimer: null,
        };
        sessions.set(session.id, session);
        hostResumes.set(session.hostResumeHash, session.id);
        const invite = issueInvite(session);
        send(ws, { t: 'ready', session: session.id, hostResume, invite, inviteTtlSec: Math.round(cfg.inviteTtlMs / 1000), controller: 'none' });
        cfg.log(`session created; active=${sessions.size}`);
        return;
      }

      const s = session;
      if (s.host !== ws) return;
      switch (m.t) {
        case 'hb':
          break;
        case 'new_invite': {
          const invite = issueInvite(s);
          send(ws, { t: 'invite', invite, inviteTtlSec: Math.round(cfg.inviteTtlMs / 1000) });
          break;
        }
        case 'approve':
          if (s.ctl && !s.ctlApproved) {
            s.ctlApproved = true;
            send(s.ctl, { t: 'approved' });
          }
          break;
        case 'deny':
          kickController(s, 'denied');
          break;
        case 'kick':
          kickController(s, 'kicked');
          break;
        case 'state':
          if (s.ctl && s.ctlApproved && m.s && typeof m.s === 'object') send(s.ctl, { t: 'state', s: m.s });
          break;
        case 'notice':
          if (s.ctl && typeof m.text === 'string') send(s.ctl, { t: 'notice', text: m.text.slice(0, 200) });
          break;
        case 'end':
          destroy(s, 'host ended');
          break;
        default:
          break;
      }
    });

    ws.on('close', () => {
      clearTimeout(helloTimer);
      const s = session;
      if (!s || s.host !== ws) return;
      s.host = null;
      s.hostOfflineSince = Date.now();
      if (s.ctl) send(s.ctl, { t: 'host_offline' });
      clearTimeout(s.hostTimer);
      s.hostTimer = setTimeout(() => destroy(s, 'host gone'), cfg.hostGraceMs);
    });
  }

  // ------------------------------------------------------------------ controller

  function attachController(s, ws, resumed) {
    revokeCtlResume(s);
    const resume = newToken();
    s.ctlResumeHash = hash(resume);
    ctlResumes.set(s.ctlResumeHash, s.id);
    s.ctl = ws;
    s.ctlOfflineSince = 0;
    clearTimeout(s.ctlTimer);
    send(ws, { t: 'joined', ctlResume: resume, approval: s.ctlApproved ? 'approved' : 'pending', hostOnline: !!s.host });
    send(s.host, { t: 'ctl_joined', resumed: resumed && s.ctlApproved });
  }

  function onController(ws, req) {
    let session = null;
    const ip = clientIp(req);
    const helloTimer = setTimeout(() => ws.close(4000, 'no token'), cfg.helloTimeoutMs);

    ws.on('message', (data) => {
      const m = parse(data);
      if (!m) return;
      if (!session) {
        if (m.t !== 'redeem' && m.t !== 'resume') return;
        if (rateLimited(ip)) {
          send(ws, { t: 'error', code: 'rate', msg: 'För många försök – vänta en minut' });
          ws.close(4029, 'rate');
          return;
        }
        if (!isToken(m.token)) {
          send(ws, { t: 'error', code: 'invalid', msg: 'Ogiltig eller redan använd länk' });
          ws.close(4002, 'invalid');
          return;
        }
        const h = hash(m.token);
        if (m.t === 'redeem') {
          const id = invites.get(h);
          const s = id && sessions.get(id);
          if (!s || s.inviteHash !== h || s.inviteExpires <= Date.now()) {
            send(ws, { t: 'error', code: 'invalid', msg: 'Ogiltig, utgången eller redan använd länk' });
            ws.close(4002, 'invalid');
            return;
          }
          if (s.ctl) {
            send(ws, { t: 'error', code: 'busy', msg: 'Någon annan styr redan' });
            ws.close(4009, 'busy');
            return;
          }
          revokeInvite(s); // one use only
          clearTimeout(helloTimer);
          session = s;
          s.ctlApproved = false;
          attachController(s, ws, false);
          cfg.log('controller joined');
          return;
        }
        const id = ctlResumes.get(h);
        const s = id && sessions.get(id);
        const inGrace = s && !s.ctl && s.ctlOfflineSince && Date.now() - s.ctlOfflineSince <= cfg.ctlGraceMs;
        if (!inGrace) {
          send(ws, { t: 'error', code: 'expired', msg: 'Anslutningen har gått ut – be om en ny länk' });
          ws.close(4002, 'expired');
          return;
        }
        clearTimeout(helloTimer);
        session = s;
        attachController(s, ws, true);
        return;
      }

      const s = session;
      if (s.ctl !== ws) return;
      if (m.t === 'hb') {
        send(s.host, { t: 'ctl', m: { t: 'hb' } });
      } else if (m.t === 'cmd' && s.ctlApproved && m.c && typeof m.c === 'object' && !Array.isArray(m.c)) {
        send(s.host, { t: 'ctl', m: { t: 'cmd', c: m.c } });
      }
    });

    ws.on('close', () => {
      clearTimeout(helloTimer);
      const s = session;
      if (!s || s.ctl !== ws) return;
      s.ctl = null;
      s.ctlOfflineSince = Date.now();
      send(s.host, { t: 'ctl_left' });
      clearTimeout(s.ctlTimer);
      s.ctlTimer = setTimeout(() => {
        revokeCtlResume(s);
        s.ctlApproved = false;
      }, cfg.ctlGraceMs);
    });
  }

  // ------------------------------------------------------------------ http

  function securityHeaders(req) {
    const host = String(req.headers.host || '').replace(/[^A-Za-z0-9.:-]/g, '');
    return {
      'Content-Security-Policy': `default-src 'self'; connect-src 'self' wss://${host}; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
      'Referrer-Policy': 'no-referrer',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Cache-Control': 'no-store',
    };
  }

  const server = http.createServer((req, res) => {
    const url = (req.url || '/').split('?')[0];
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405).end();
      return;
    }
    if (url === '/healthz') {
      res.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok');
      return;
    }
    if (url === '/api/info') {
      // Lets the app check that the address is a Hismith relay and whether it needs the secret.
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }).end(JSON.stringify({
        name: 'hismith-relay',
        version: VERSION,
        secretRequired: !!cfg.hostSecret,
        inviteTtlSec: Math.round(cfg.inviteTtlMs / 1000),
      }));
      return;
    }
    if (url === '/' || url === '/r') {
      res.writeHead(302, { Location: '/r/', ...securityHeaders(req) }).end();
      return;
    }
    const file = STATIC[url];
    if (!file) {
      res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found');
      return;
    }
    fs.readFile(path.join(PUBLIC_DIR, file[0]), (err, body) => {
      if (err) {
        res.writeHead(500).end();
        return;
      }
      res.writeHead(200, { 'Content-Type': file[1], ...securityHeaders(req) });
      res.end(req.method === 'HEAD' ? undefined : body);
    });
  });

  const wssHost = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 });
  const wssCtl = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 });

  server.on('upgrade', (req, socket, head) => {
    const url = (req.url || '').split('?')[0];
    if (url === '/ws/host') {
      wssHost.handleUpgrade(req, socket, head, (ws) => onHost(ws));
      return;
    }
    if (url === '/ws/ctl') {
      // Browsers always send Origin: only accept our own page.
      const origin = req.headers.origin;
      if (origin) {
        let ok = false;
        try {
          ok = new URL(origin).host === req.headers.host;
        } catch {
          ok = false;
        }
        if (!ok) {
          socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
          socket.destroy();
          return;
        }
      }
      wssCtl.handleUpgrade(req, socket, head, (ws) => onController(ws, req));
      return;
    }
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
  });

  // Dead-connection detection.
  const alive = new WeakMap();
  const heartbeat = setInterval(() => {
    for (const wss of [wssHost, wssCtl]) {
      for (const ws of wss.clients) {
        if (alive.get(ws) === false) {
          ws.terminate();
          continue;
        }
        alive.set(ws, false);
        ws.ping();
      }
    }
  }, cfg.pingIntervalMs);
  for (const wss of [wssHost, wssCtl]) {
    wss.on('connection', (ws) => {
      alive.set(ws, true);
      ws.on('pong', () => alive.set(ws, true));
    });
  }

  return {
    server,
    sessions,
    listen(port = cfg.port) {
      return new Promise((resolve) => server.listen(port, () => resolve(server.address().port)));
    },
    close() {
      clearInterval(heartbeat);
      for (const s of [...sessions.values()]) destroy(s, 'shutdown');
      for (const wss of [wssHost, wssCtl]) for (const ws of wss.clients) ws.terminate();
      return new Promise((resolve) => server.close(() => resolve()));
    },
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const relay = createRelay();
  relay.listen().then((port) => {
    const c = defaultConfig();
    c.log(`Hismith relay listening on :${port}${c.hostSecret ? ' (HOST_SECRET set)' : ' (no HOST_SECRET – anyone can create sessions)'}`);
  });
  const stop = () => relay.close().then(() => process.exit(0));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
