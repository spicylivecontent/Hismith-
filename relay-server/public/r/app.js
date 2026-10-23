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
  let claimedAt = 0;
  let dragging = false;
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
    const locked = !!s.locked;
    const hasMachine = s.machines > 0;

    // Automatics only when the phone allows it; otherwise the link is a plain manual mode.
    $('tabs').classList.toggle('hidden', !s.allowAuto);
    if (!s.allowAuto && view !== 'manual') showView('manual');

    $('speed').textContent = s.speed + ' %';
    $('phase').textContent = locked ? 'Låst av telefonen' : stopped ? 'STOPPAD' : paused ? 'Pausad'
      : auto ? (s.phase || 'Automatik') : manual ? (mine ? 'Du styr' : 'Telefonen styr') : 'Redo';
    $('info').textContent = [
      manual && mine ? 'mål ' + s.target + ' %' : null,
      auto ? (mine ? 'din automatik' : 'telefonens automatik') : null,
      hasMachine ? 'max ' + s.maxSpeed + ' %' : null,
    ].filter(Boolean).join(' · ');
    $('clockRow').classList.toggle('hidden', !auto);
    $('time').textContent = clock(s.elapsed);
    $('remaining').textContent = s.remaining === null ? ' · obegränsad tid' : ' · kvar ' + clock(s.remaining);
    $('locked').classList.toggle('hidden', !locked);

    // Hismith: only shown when a machine is connected. The slider takes manual control itself.
    $('hismith').classList.toggle('hidden', !hasMachine);
    $('noMachine').classList.toggle('hidden', hasMachine);
    const busyElsewhere = auto || (manual && !mine);
    const canSteer = hasMachine && !locked && !busyElsewhere;
    $('speedSlider').max = String(s.maxSpeed);
    $('speedSlider').disabled = !canSteer;
    document.querySelectorAll('[data-speed]').forEach(function (b) { b.disabled = !canSteer; });
    $('hismithNote').textContent = locked ? '' : auto ? 'Automatiken kör – avsluta den för att styra själv.'
      : manual && !mine ? 'Telefonen styr just nu.' : stopped ? 'Dra i reglaget för att starta igen.' : '';
    if (!dragging && document.activeElement !== $('speedSlider') && !speedTimer) {
      const v = manual && mine ? s.target : 0;
      $('speedSlider').value = String(v);
      $('speedVal').textContent = v + ' %';
    }

    $('autoStart').disabled = locked || auto || manual || !hasMachine;
    const canAct = !locked && auto && mine && !paused && !s.final;
    document.querySelectorAll('[data-action]').forEach(function (b) { b.disabled = !canAct; });
    if (auto && document.activeElement !== $('style') && s.style) $('style').value = s.style;
    $('finalNow').disabled = locked || !(auto && mine);
    $('autoEnd').disabled = locked || !(auto && mine);
    if (document.activeElement !== $('intensity')) {
      $('intensity').value = String(s.intensity);
      $('intensityVal').textContent = s.intensity + ' %';
    }
    $('pause').disabled = locked || !auto || paused;
    $('resume').disabled = locked || !paused || !mine;

    // Lube Launcher: not shown at all when it isn't available (no launcher, not allowed, Pocket).
    const lube = s.lube || {};
    $('lube').classList.toggle('hidden', !lube.available);
    const maxMl = Math.max(1, Math.min(30, lube.maxMl || 30));
    $('ml').max = String(maxMl);
    if (parseInt($('ml').value, 10) > maxMl) $('ml').value = String(maxMl);
    renderLube(lube.busy, locked);
  }

  function renderLube(busy, locked) {
    const ml = parseInt($('ml').value, 10);
    $('mlVal').textContent = ml + ' ml';
    $('pumpVal').textContent = $('pump').value + ' %';
    $('spray').textContent = busy ? 'Sprutar…' : 'Spruta ' + ml + ' ml';
    $('spray').disabled = !!busy || !!locked;
  }

  function showView(v) {
    view = v;
    $('tabManual').classList.toggle('selected', v === 'manual');
    $('tabAuto').classList.toggle('selected', v === 'auto');
    $('manual').classList.toggle('hidden', v !== 'manual');
    $('auto').classList.toggle('hidden', v !== 'auto');
  }

  /** Moving the slider is the explicit start of manual control (the phone still decides). */
  function claimManual() {
    const s = state || {};
    if (s.control === 'manual' && s.owner === 'remote') return;
    if (Date.now() - claimedAt < 1500) return; // already asked, waiting for the state
    claimedAt = Date.now();
    lastSpeedSent = -1;
    cmd({ k: 'manual_start' });
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

  function stopAll() {
    if (ws && ws.readyState === 1 && approved) cmd({ k: 'stop' });
    else toast('Ingen anslutning – telefonen stoppar själv inom några sekunder');
    if (navigator.vibrate) navigator.vibrate(80);
  }

  // ------------------------------------------------------------------ wiring

  $('stop').addEventListener('click', stopAll);
  $('lubeStop').addEventListener('click', stopAll);
  $('tabManual').addEventListener('click', function () { showView('manual'); });
  $('tabAuto').addEventListener('click', function () { showView('auto'); });
  $('speedSlider').addEventListener('pointerdown', function () { dragging = true; });
  ['pointerup', 'pointercancel'].forEach(function (t) { window.addEventListener(t, function () { dragging = false; }); });
  $('speedSlider').addEventListener('input', function (e) {
    claimManual();
    sendSpeedThrottled(parseInt(e.target.value, 10));
  });
  document.querySelectorAll('[data-speed]').forEach(function (b) {
    b.addEventListener('click', function () {
      const v = Math.min(parseInt(b.getAttribute('data-speed'), 10), parseInt($('speedSlider').max, 10));
      $('speedSlider').value = String(v);
      claimManual();
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
  $('ml').addEventListener('input', function () { renderLube(state && state.lube && state.lube.busy, state && state.locked); });
  $('pump').addEventListener('input', function () { renderLube(state && state.lube && state.lube.busy, state && state.locked); });
  $('spray').addEventListener('click', function () {
    cmd({ k: 'spray', ml: parseInt($('ml').value, 10), pump: parseInt($('pump').value, 10) });
    if (navigator.vibrate) navigator.vibrate(40);
  });

  showView(view);
  connect();
})();
