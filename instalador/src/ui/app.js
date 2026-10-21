'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const bridge = window.tv;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const reduced = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

const store = {
  get(key) { try { return localStorage.getItem(key); } catch (e) { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* sin almacenamiento */ } },
};

const state = {
  ip: null,
  info: null,
  mode: store.get('mode') === 'normal' ? 'normal' : 'strong',
  pct: 0,
  attempt: 0,
  connecting: false,
  retry: null,
};

document.body.classList.toggle('mac', bridge.platform === 'darwin');

// --- Texto que cambia con un fundido corto --------------------------------------------------

function swapText(el, text) {
  if (el.textContent === text) return;
  if (reduced()) { el.textContent = text; return; }
  el.style.transition = 'opacity 110ms ease, filter 110ms ease';
  el.style.opacity = '0';
  el.style.filter = 'blur(2px)';
  setTimeout(() => {
    el.textContent = text;
    el.style.opacity = '';
    el.style.filter = '';
  }, 110);
}

/** Título de dos líneas: la segunda en cursiva. */
function setTitle(el, first, second) {
  el.replaceChildren(document.createTextNode(first));
  if (second) {
    el.appendChild(document.createElement('br'));
    const em = document.createElement('em');
    em.textContent = second;
    el.appendChild(em);
  }
}

// --- Cambio de pantalla -----------------------------------------------------------------------

const views = Object.fromEntries($$('.view').map((v) => [v.dataset.view, v]));
const STAGES = {
  scan: [1, 'Buscar'], pick: [1, 'Buscar'], none: [1, 'Buscar'], error: [1, 'Buscar'],
  auth: [2, 'Conectar'], ready: [3, 'Instalar'], working: [3, 'Instalar'], done: [3, 'Listo'],
};
let current = null;
let token = 0;

function updateChrome(name) {
  const [n, label] = STAGES[name];
  const steps = $('#steps');
  steps.dataset.n = String(n);
  if (name === 'done') steps.setAttribute('data-done', ''); else steps.removeAttribute('data-done');
  swapText($('#stepLabel'), label);
  $('#led').classList.toggle('busy', name === 'scan' || name === 'auth' || name === 'working');
  $('#ipLabel').textContent = state.ip || '';
}

function go(name) {
  if (current === name) return;
  const prev = current ? views[current] : null;
  const next = views[name];
  current = name;
  const t = ++token;

  const show = () => {
    if (t !== token) return;
    for (const v of Object.values(views)) {
      if (v !== next) { v.hidden = true; v.classList.remove('in', 'out'); }
    }
    $$('.s', next).forEach((el, i) => el.style.setProperty('--i', String(i)));
    next.classList.remove('in', 'out');
    next.hidden = false;
    void next.offsetWidth; // fuerza el estado inicial antes de animar la entrada
    next.classList.add('in');
    updateChrome(name);
    const focusable = $('[data-autofocus]', next);
    if (focusable) focusable.focus({ preventScroll: true });
  };

  if (prev && !prev.hidden) {
    prev.classList.add('out');
    setTimeout(show, reduced() ? 0 : 130);
  } else {
    show();
  }
}

// --- Búsqueda y conexión ----------------------------------------------------------------------

function placeBlips(ips) {
  const box = $('#blips');
  box.replaceChildren();
  ips.forEach((ip, i) => {
    const last = Number(ip.split('.').pop());
    const angle = ((last * 47 + i * 120) % 360) * (Math.PI / 180);
    const radius = 52 + (last % 40);
    const blip = document.createElement('span');
    blip.className = 'blip';
    blip.style.left = `${105 + Math.cos(angle) * radius}px`;
    blip.style.top = `${105 + Math.sin(angle) * radius}px`;
    box.appendChild(blip);
  });
}

function showScan(title, subtitle) {
  setTitle($('#scanTitle'), title[0], title[1]);
  swapText($('#scanSub'), subtitle);
  go('scan');
}

async function scanAndConnect() {
  const mine = ++state.attempt;
  state.retry = scanAndConnect;
  showScan(['Buscando', 'tu TV'], 'Debe estar encendida y en este mismo wifi.');
  $('#blips').replaceChildren();
  await bridge.cancel();
  const [res] = await Promise.all([bridge.discover(), sleep(reduced() ? 0 : 1100)]);
  if (mine !== state.attempt) return;
  if (!res.ok) return showError(res.error);

  const { found, networks } = res.data;
  if (!networks.length) return showNone('Este ordenador no está conectado a ninguna red local.');
  placeBlips(found);
  const last = store.get('lastIp');
  if (found.includes(last)) return connectTo(last);
  if (found.length === 1) return connectTo(found[0]);
  if (found.length > 1) return showPick(found, last);
  showNone();
}

function showNone(message) {
  $('#noneSub').textContent = message ||
    'Lo más probable: falta activar la depuración por red en la TV. Son un par de minutos, una sola vez.';
  go('none');
}

function showPick(ips, last) {
  const list = $('#pickList');
  list.replaceChildren();
  for (const ip of ips) {
    const li = document.createElement('li');
    const btn = document.createElement('button');
    btn.type = 'button';
    const text = document.createElement('span');
    text.className = 'ip-text';
    text.textContent = ip;
    btn.appendChild(text);
    if (ip === last) {
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = 'Última vez';
      btn.appendChild(tag);
    }
    btn.insertAdjacentHTML('beforeend', '<svg class="arrow" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8h10M9 4l4 4-4 4"/></svg>');
    if (ip !== last) btn.lastElementChild.style.marginLeft = 'auto';
    btn.addEventListener('click', () => connectTo(ip));
    li.appendChild(btn);
    list.appendChild(li);
  }
  go('pick');
}

async function connectTo(ip) {
  const mine = ++state.attempt;
  state.ip = ip;
  state.connecting = true;
  state.retry = () => connectTo(ip);
  showScan(['Conectando', 'con tu TV'], ip);
  const res = await bridge.status(ip);
  if (mine !== state.attempt) return;
  state.connecting = false;
  if (!res.ok) return showError(res.error);
  store.set('lastIp', ip);
  state.info = res.data;
  showReady();
}

bridge.onAuthPrompt(() => { if (state.connecting) go('auth'); });

// --- IP manual --------------------------------------------------------------------------------

$$('.ipform').forEach((form) => {
  const input = $('input', form);
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const ip = input.value.trim();
    const ok = /^(\d{1,3}\.){3}\d{1,3}$/.test(ip) && ip.split('.').every((n) => Number(n) <= 255);
    if (!ok) {
      if (!reduced()) {
        input.animate(
          [{ transform: 'translateX(0)' }, { transform: 'translateX(-6px)' }, { transform: 'translateX(5px)' }, { transform: 'translateX(-3px)' }, { transform: 'none' }],
          { duration: 280, easing: 'ease-out' },
        );
      }
      return;
    }
    input.blur();
    connectTo(ip);
  });
});

// --- Pantalla "lista" -------------------------------------------------------------------------

const HINTS = {
  strong: 'Instala Inicio TV y desactiva el launcher de Google para que no cargue anuncios en segundo plano. Se puede deshacer.',
  normal: 'Solo instala. En muchas TV, Google conserva la prioridad al pulsar Inicio.',
};

function setMode(mode, { animate = true } = {}) {
  state.mode = mode;
  store.set('mode', mode);
  const seg = $('#mode');
  seg.style.setProperty('--pos', mode === 'strong' ? '0' : '1');
  $$('button', seg).forEach((b) => b.setAttribute('aria-checked', String(b.dataset.mode === mode)));
  if (animate) swapText($('#modeHint'), HINTS[mode]); else $('#modeHint').textContent = HINTS[mode];
}

function showReady() {
  const s = state.info;
  $('#readyName').textContent = s.name;
  $('#readyMeta').textContent = `${state.ip} · Android ${s.android || '?'}`;

  const active = s.installed && s.isDefault;
  $('#readyDot').classList.toggle('on', active);
  $('#readyState').textContent = !s.installed
    ? 'Inicio TV sin instalar'
    : active ? `Inicio TV ${s.version} · activo` : `Inicio TV ${s.version} · instalado, sin activar`;

  $('#installLabel').textContent = !s.installed ? 'Instalar en la TV' : active ? 'Reinstalar' : 'Activar Inicio TV';
  $('#revertBtn').hidden = !s.installed;
  $('#uninstallBtn').hidden = !s.installed;
  disarmUninstall();
  setMode(state.mode, { animate: false });
  go('ready');
}

$('#mode').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-mode]');
  if (b) setMode(b.dataset.mode);
});
$('#mode').addEventListener('keydown', (e) => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
  e.preventDefault();
  const mode = e.key === 'ArrowLeft' ? 'strong' : 'normal';
  setMode(mode);
  $(`[data-mode="${mode}"]`).focus();
});

// Desinstalar pide una segunda pulsación en el propio botón, sin diálogos.
let armTimer = null;
function disarmUninstall() {
  clearTimeout(armTimer);
  armTimer = null;
  const btn = $('#uninstallBtn');
  btn.classList.remove('armed');
  $('.label', btn).textContent = 'Desinstalar';
}
function onUninstall() {
  const btn = $('#uninstallBtn');
  if (!armTimer) {
    btn.classList.add('armed');
    swapText($('.label', btn), 'Pulsa otra vez para confirmar');
    armTimer = setTimeout(() => { disarmUninstall(); }, 3500);
    return;
  }
  disarmUninstall();
  run('uninstall');
}

// --- Trabajando: odómetro ---------------------------------------------------------------------

for (const id of ['reelTens', 'reelOnes']) {
  const reel = $('#' + id);
  for (let d = 0; d < 10; d++) {
    const span = document.createElement('span');
    span.textContent = String(d);
    reel.appendChild(span);
  }
}

function setPct(value, force = false) {
  const n = Math.max(0, Math.min(99, Math.round(value)));
  if (!force && n <= state.pct) return;
  state.pct = n;
  $('#reelTens').style.setProperty('--d', String(Math.floor(n / 10)));
  $('#reelOnes').style.setProperty('--d', String(n % 10));
  $('#track').style.setProperty('--p', String(n / 100));
  $('#odo').setAttribute('aria-label', `Progreso ${n}%`);
}

const PHASES = [
  [/Conectando/, 'Conectando', 1],
  [/Enviando/, 'Enviando a la TV', 2],
  [/Instalado/, 'Instalado', 86],
  [/Desactivando/, 'Desactivando el launcher de Google', 90],
  [/Estableciendo/, 'Activando Inicio TV', 95],
  [/Reactivando/, 'Reactivando el launcher original', 20],
  [/Activando/, 'Reactivando el launcher original', 45],
  [/predeterminado:/, 'Ajustando el launcher', 70],
  [/Desinstalando/, 'Desinstalando Inicio TV', 85],
  [/Listo/, 'Terminando', 99],
];

const WORK_TITLES = {
  install: ['Instalando', 'en tu TV'],
  revert: ['Volviendo', 'al original'],
  uninstall: ['Desinstalando', 'Inicio TV'],
};

function onLog(message) {
  const phase = PHASES.find(([re]) => re.test(message));
  if (!phase) return;
  swapText($('#workPhase'), phase[1]);
  setPct(phase[2]);
}

async function run(kind) {
  const mine = ++state.attempt;
  state.retry = () => run(kind);
  const [a, b] = WORK_TITLES[kind];
  setTitle($('#workTitle'), a, b);
  $('#workPhase').textContent = 'Preparando';
  state.pct = 0;
  setPct(0, true);
  go('working');

  const off = [bridge.onLog(onLog), bridge.onInstallProgress((p) => setPct(2 + p * 78))];
  const res = kind === 'install' ? await bridge.install(state.ip, state.mode === 'strong') : await bridge.revert(state.ip, kind === 'uninstall');
  off.forEach((fn) => fn());
  if (mine !== state.attempt) return;
  if (!res.ok) return showError(res.error);

  setPct(99);
  await sleep(reduced() ? 0 : 560);
  state.info = res.data;
  showDone(kind);
}

// --- Hecho y error ----------------------------------------------------------------------------

const DONE = {
  install: [['Todo', 'listo'], 'Pulsa Inicio en el mando de la TV.'],
  revert: [['Vuelta', 'al original'], 'La TV vuelve a usar su launcher de siempre.'],
  uninstall: [['Inicio TV', 'desinstalado'], 'La TV usa su launcher original.'],
};

function buildTicks() {
  const group = $('.ticks');
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * Math.PI * 2;
    const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line.setAttribute('x1', String(90 + Math.cos(a) * 76));
    line.setAttribute('y1', String(90 + Math.sin(a) * 76));
    line.setAttribute('x2', String(90 + Math.cos(a) * 84));
    line.setAttribute('y2', String(90 + Math.sin(a) * 84));
    line.style.setProperty('--tx', `${Math.cos(a) * 16}px`);
    line.style.setProperty('--ty', `${Math.sin(a) * 16}px`);
    group.appendChild(line);
  }
}

function showDone(kind) {
  const [[a, b], sub] = DONE[kind];
  setTitle($('#doneTitle'), a, b);
  $('#doneSub').textContent = sub;
  go('done');
}

function showError(message) {
  state.connecting = false;
  let text = message || 'Error desconocido.';
  if (/No se pudo conectar/.test(text)) text += ' Comprueba que la TV está encendida y que la depuración por red sigue activada.';
  $('#errorMsg').textContent = text;
  go('error');
}

// --- Guía lateral -----------------------------------------------------------------------------

const GUIDE = [
  ['Abre Ajustes', 'La rueda dentada, en la pantalla de inicio de la TV.'],
  ['Entra en Preferencias del dispositivo → Información', 'En algunas TV se llama Sistema → Acerca de.'],
  ['Pulsa OK 7 veces sobre «Compilación»', 'Saldrá «Ahora eres desarrollador». También puede llamarse «Número de compilación».'],
  ['Abre Opciones para desarrolladores', 'Ya aparece en Preferencias del dispositivo.'],
  ['Activa Depuración USB y Depuración por red', 'La segunda puede llamarse «Depuración ADB por red». Deja la TV encendida.'],
];

function buildGuide() {
  const list = $('#guide');
  GUIDE.forEach(([title, detail], i) => {
    const li = document.createElement('li');
    li.style.setProperty('--i', String(i));
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'step';
    btn.setAttribute('role', 'checkbox');
    const checked = store.get('guide' + i) === '1';
    btn.setAttribute('aria-checked', String(checked));
    btn.innerHTML = '<span class="n"></span><span><span class="t"></span><span class="d"></span></span>' +
      '<span class="box"><svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.4l2.6 2.6L10 3.4"/></svg></span>';
    $('.n', btn).textContent = String(i + 1).padStart(2, '0');
    $('.t', btn).textContent = title;
    $('.d', btn).textContent = detail;
    btn.addEventListener('click', () => {
      const next = btn.getAttribute('aria-checked') !== 'true';
      btn.setAttribute('aria-checked', String(next));
      store.set('guide' + i, next ? '1' : '0');
    });
    li.appendChild(btn);
    list.appendChild(li);
  });
}

let lastFocus = null;
function openDrawer() {
  const drawer = $('#drawer');
  if (drawer.classList.contains('open')) return;
  lastFocus = document.activeElement;
  drawer.inert = false;
  $('#app').inert = true;
  drawer.classList.add('open');
  $('#scrim').classList.add('open');
  setTimeout(() => $('#drawerClose').focus({ preventScroll: true }), 60);
}
function closeDrawer() {
  const drawer = $('#drawer');
  if (!drawer.classList.contains('open')) return;
  drawer.classList.remove('open');
  $('#scrim').classList.remove('open');
  drawer.inert = true;
  $('#app').inert = false;
  if (lastFocus && lastFocus.focus) lastFocus.focus({ preventScroll: true });
}
$('#scrim').addEventListener('click', closeDrawer);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawer(); });

// --- Acciones ---------------------------------------------------------------------------------

const ACTIONS = {
  help: openDrawer,
  close: closeDrawer,
  rescan: scanAndConnect,
  install: () => run('install'),
  revert: () => run('revert'),
  uninstall: onUninstall,
  back: showReady,
  retry: () => (state.retry ? state.retry() : scanAndConnect()),
};

document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (!el) return;
  if (el.dataset.act !== 'uninstall') disarmUninstall();
  const action = ACTIONS[el.dataset.act];
  if (action) action();
});

buildTicks();
buildGuide();
setMode(state.mode, { animate: false });
scanAndConnect();
