// Controller page for the one-time link. The token comes in the URL fragment (never sent to
// the server in requests or logs) and is removed from the address bar right away.
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const RESUME_KEY = 'hismith_resume';
  const FINAL = { denied: 'Telefonen nekade anslutningen.', kicked: 'Telefonen kopplade bort dig.', ended: 'Länken är avslutad.' };

  let invite = null;
  const hash = location.hash.replace(/^#/, '');
  if (/^[A-Za-z0-9_-]{20,100}$/.test(hash)) invite = hash;
  if (location.hash) history.replaceState(null, '', location.pathname);

  let ws = null;
  let joined = false;
  let approved = false;
  let finished = false;
  let state = null;
  let hbTimer = null;
  let retries = 0;
  let lastSpeedSent = -1;
  let speedTimer = null;
  let toastTimer = null;
  let view = 'manual';

  function storedResume() {
    try { return sessionStorage.getItem(RESUME_KEY); } catch (e) { return null; }
  }
  function storeResume(t) {
    try { if (t) sessionStorage.setItem(RESUME_KEY, t); else sessionStorage.removeItem(RESUME_KEY); } catch (e) { /* private mode */ }
  }

  function setConn(text, kind) {
    $('conn').textContent = text;
    $('dot').className = 'dot' + (kind ? ' ' + kind : '');
  }

  function message(text) {
    $('msg').textContent = text;
    $('msg').classList.toggle('hidden', !text);
  }

  function toast(text) {
    const t = $('toast');
    t.textContent = text;
    t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), 3500);
  }

  function finish(text) {
    finished = true;
    joined = false;
    approved = false;
    storeResume(null);
    clearInterval(hbTimer);
    $('panel').classList.add('hidden');
    message(text);
    setConn('Frånkopplad', 'bad');
  }

  function cmd(c) {
    if (ws && ws.readyState === 1 && approved) ws.send(JSON.stringify({ t: 'cmd', c: c }));
  }

  function connect() {
    if (finished) return;
    const resume = storedResume();
    if (!invite && !resume) {
      finish('Öppna länken du fick från telefonen. Den fungerar bara en gång.');
      return;
    }
    setConn(retries ? 'Återansluter…' : 'Ansluter…');
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(proto + '://' + location.host + '/ws/ctl');
    ws.onopen = function () {
      if (resume && (joined || !invite)) ws.send(JSON.stringify({ t: 'resume', token: resume }));
      else ws.send(JSON.stringify({ t: 'redeem', token: invite }));
    };
    ws.onmessage = function (ev) {
      let m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      onMessage(m);
    };
    ws.onclose = function () {
      clearInterval(hbTimer);
      if (finished) return;
      if (!joined) return;
      // Try to come back within the server's grace window. Motion is already stopping on the phone.
      setConn('Tappade anslutningen – försöker igen…', 'bad');
      retries++;
      if (retries > 6) {
        finish('Anslutningen bröts. Be om en ny länk.');
        return;
      }
      setTimeout(connect, Math.min(4000, 500 * Math.pow(2, retries - 1)));
    };
  }

  function onMessage(m) {
    switch (m.t) {
      case 'joined':
        joined = true;
        invite = null; // used up
        retries = 0;
        storeResume(m.ctlResume);
        approved = m.approval === 'approved';
        message(approved ? '' : 'Väntar på att telefonen godkänner dig…');
        $('panel').classList.toggle('hidden', !approved);
        setConn(m.hostOnline ? (approved ? 'Ansluten' : 'Väntar på godkännande') : 'Telefonen är offline', approved ? 'ok' : '');
        clearInterval(hbTimer);
        hbTimer = setInterval(function () {
          if (ws && ws.readyState === 1) ws.send('{"t":"hb"}');
        }, 1000);
        break;
      case 'approved':
        approved = true;
        message('');
        $('panel').classList.remove('hidden');
        setConn('Ansluten', 'ok');
        toast('Godkänd – du styr nu');
        break;
      case 'denied':
      case 'kicked':
      case 'ended':
        finish(FINAL[m.t]);
        break;
      case 'host_offline':
        setConn('Telefonen tappade nätet', 'bad');
        break;
      case 'host_online':
        setConn(approved ? 'Ansluten' : 'Väntar på godkännande', approved ? 'ok' : '');
        break;
      case 'state':
        state = m.s;
        render();
        break;
      case 'notice':
        toast(m.text);
        break;
      case 'error':
        finish(m.msg || 'Ogiltig länk');
        break;
      default:
        break;
    }
  }

  function clock(sec) {
    sec = Math.max(0, sec | 0);
    const mm = String(Math.floor(sec / 60)).padStart(2, '0');
    const ss = String(sec % 60).padStart(2, '0');
    return sec >= 3600 ? Math.floor(sec / 3600) + ':' + String(Math.floor(sec / 60) % 60).padStart(2, '0') + ':' + ss : mm + ':' + ss;
  }

  function render() {
    const s = state;
    if (!s) return;
    const mine = s.owner === 'remote';
    const manual = s.control === 'manual';
    const auto = s.control === 'auto';
    const paused = s.safety === 'paused';
    const stopped = s.safety === 'stopped';

    $('time').textContent = clock(s.elapsed);
    $('remaining').textContent = auto ? (s.remaining === null ? 'obegränsad tid' : 'kvar ' + clock(s.remaining)) : manual ? 'manuell styrning' : 'ingen session';
    $('speed').textContent = s.speed + ' %';
    $('phase').textContent = stopped ? 'STOPPAD' + (s.reason ? ' – ' + s.reason : '') : paused ? 'Pausad' + (s.reason ? ' – ' + s.reason : '') : auto ? (s.phase || 'Automatik') : manual ? 'Manuell · mål ' + s.target + ' %' : 'Redo';
    const who = s.owner === 'remote' ? 'du' : s.owner === 'local' ? 'telefonen' : s.owner === 'challenge' ? 'utmaningen' : null;
    $('info').textContent = [
      s.mode === 'ORAL' ? 'Oral' : s.mode === 'ANAL' ? 'Anal' : 'Pocket',
      s.machines + ' maskin' + (s.machines === 1 ? '' : 'er'),
      'max ' + s.maxSpeed + ' %',
      who ? 'styrs av ' + who : null,
    ].filter(Boolean).join(' · ');

    $('speedSlider').max = String(s.maxSpeed);
    $('manualToggle').textContent = manual && mine ? 'Avsluta manuell styrning' : 'Starta manuell styrning';
    $('manualToggle').disabled = (auto || (manual && !mine)) || s.machines === 0;
    $('manualBox').classList.toggle('hidden', !(manual && mine));
    if (manual && mine && document.activeElement !== $('speedSlider') && !speedTimer) {
      $('speedSlider').value = String(s.target);
      $('speedVal').textContent = s.target + ' %';
    }

    $('autoStart').disabled = auto || manual || s.machines === 0;
    const canAct = auto && mine && !paused && !s.final;
    document.querySelectorAll('[data-action]').forEach(function (b) { b.disabled = !canAct; });
    if (auto && document.activeElement !== $('style') && s.style) $('style').value = s.style;
    $('finalNow').disabled = !(auto && mine);
    $('autoEnd').disabled = !auto;
    if (document.activeElement !== $('intensity')) {
      $('intensity').value = String(s.intensity);
      $('intensityVal').textContent = s.intensity + ' %';
    }

    $('pause').disabled = !(auto || manual) || paused;
    $('resume').disabled = !paused || !mine;

    const lube = s.lube || {};
    const showLube = lube.available && (lube.extra || lube.super);
    $('lubeRow').classList.toggle('hidden', !showLube);
    $('extra').classList.toggle('hidden', !lube.extra);
    $('super').classList.toggle('hidden', !lube.super);
    $('extra').textContent = 'EXTRA ' + Math.round(lube.extraMl || 0) + ' ml';
    $('super').textContent = 'SUPER ' + Math.round(lube.superMl || 0) + ' ml';
    $('extra').disabled = !!lube.busy;
    $('super').disabled = !!lube.busy;
  }

  function showView(v) {
    view = v;
    $('tabManual').classList.toggle('selected', v === 'manual');
    $('tabAuto').classList.toggle('selected', v === 'auto');
    $('manual').classList.toggle('hidden', v !== 'manual');
    $('auto').classList.toggle('hidden', v !== 'auto');
  }

  function sendSpeedThrottled(v) {
    $('speedVal').textContent = v + ' %';
    if (speedTimer) return;
    speedTimer = setTimeout(function () {
      speedTimer = null;
      const val = parseInt($('speedSlider').value, 10);
      if (val !== lastSpeedSent) {
        lastSpeedSent = val;
        cmd({ k: 'speed', v: val });
      }
    }, 120);
  }

  // ------------------------------------------------------------------ wiring

  $('stop').addEventListener('click', function () {
    if (ws && ws.readyState === 1 && approved) cmd({ k: 'stop' });
    else toast('Ingen anslutning – telefonen stoppar själv inom några sekunder');
    if (navigator.vibrate) navigator.vibrate(80);
  });
  $('tabManual').addEventListener('click', function () { showView('manual'); });
  $('tabAuto').addEventListener('click', function () { showView('auto'); });
  $('manualToggle').addEventListener('click', function () {
    const s = state || {};
    if (s.control === 'manual' && s.owner === 'remote') cmd({ k: 'manual_end' });
    else { lastSpeedSent = -1; cmd({ k: 'manual_start' }); }
  });
  $('speedSlider').addEventListener('input', function (e) { sendSpeedThrottled(parseInt(e.target.value, 10)); });
  document.querySelectorAll('[data-speed]').forEach(function (b) {
    b.addEventListener('click', function () {
      const v = Math.min(parseInt(b.getAttribute('data-speed'), 10), parseInt($('speedSlider').max, 10));
      $('speedSlider').value = String(v);
      sendSpeedThrottled(v);
    });
  });
  $('intensity').addEventListener('input', function (e) { $('intensityVal').textContent = e.target.value + ' %'; });
  $('intensity').addEventListener('change', function (e) {
    if (state && state.control === 'auto' && state.owner === 'remote') cmd({ k: 'intensity', v: parseInt(e.target.value, 10) });
  });
  $('autoStart').addEventListener('click', function () {
    const min = Math.max(1, Math.min(240, parseInt($('autoMin').value, 10) || 15));
    let max = parseInt($('autoMax').value, 10);
    if (isNaN(max)) max = 25;
    max = Math.max(0, Math.min(240, max));
    cmd({ k: 'auto_start', min: min, max: max === 0 ? 0 : Math.max(min, max), intensity: parseInt($('intensity').value, 10), style: $('style').value });
  });
  $('style').addEventListener('change', function (e) {
    if (state && state.control === 'auto' && state.owner === 'remote') cmd({ k: 'style', v: e.target.value });
  });
  document.querySelectorAll('[data-action]').forEach(function (b) {
    b.addEventListener('click', function () {
      cmd({ k: 'action', a: b.getAttribute('data-action') });
      if (navigator.vibrate) navigator.vibrate(30);
    });
  });
  $('finalNow').addEventListener('click', function () { cmd({ k: 'final_now' }); });
  $('autoEnd').addEventListener('click', function () { cmd({ k: 'auto_end' }); });
  $('pause').addEventListener('click', function () { cmd({ k: 'pause' }); });
  $('resume').addEventListener('click', function () { cmd({ k: 'resume' }); });
  $('extra').addEventListener('click', function () { cmd({ k: 'lube', kind: 'extra' }); });
  $('super').addEventListener('click', function () { cmd({ k: 'lube', kind: 'super' }); });

  showView(view);
  connect();
})();
