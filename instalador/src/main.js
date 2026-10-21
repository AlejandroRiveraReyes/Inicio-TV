'use strict';
const { app, BrowserWindow, ipcMain, nativeTheme } = require('electron');
const fs = require('fs');
const path = require('path');
const { connect } = require('./adb');
const { scan } = require('./discover');
const ops = require('./ops');

const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

let win = null;
let busy = false;
let abortCtl = null;
let idle = Promise.resolve();

const apkPath = () =>
  app.isPackaged
    ? path.join(process.resourcesPath, 'inicio-tv.apk')
    : path.join(__dirname, '..', 'resources', 'inicio-tv.apk');

const send = (channel, payload) => {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
};

/** Conecta con la TV, ejecuta `fn` y cierra. Solo una operación a la vez. */
async function withTv(ip, fn) {
  if (!IPV4.test(ip)) throw new Error('La dirección IP no es válida');
  if (busy) throw new Error('Hay otra operación en curso');
  busy = true;
  let release;
  idle = new Promise((resolve) => { release = resolve; });
  abortCtl = new AbortController();
  let conn = null;
  try {
    send('log', `Conectando con ${ip}…`);
    conn = await connect(ip, {
      keyDir: app.getPath('userData'),
      onAuthRequired: () => send('auth-prompt'),
      signal: abortCtl.signal,
    });
    return await fn(conn);
  } finally {
    busy = false;
    abortCtl = null;
    if (conn) conn.close();
    release();
  }
}

const wrap = (fn) => async (_event, arg) => {
  try {
    return { ok: true, data: await fn(arg || {}) };
  } catch (e) {
    return { ok: false, error: e.message || String(e), code: e.code };
  }
};

ipcMain.handle('discover', wrap(async () => {
  if (busy) throw new Error('Hay otra operación en curso');
  busy = true;
  try {
    return await scan({ onProgress: (p) => send('scan-progress', p) });
  } finally {
    busy = false;
  }
}));

ipcMain.handle('status', wrap(({ ip }) => withTv(ip, (conn) => ops.status(conn))));

ipcMain.handle('install', wrap(({ ip, strong }) => withTv(ip, async (conn) => {
  const apk = fs.readFileSync(apkPath());
  await ops.install(conn, apk, {
    strong: !!strong,
    log: (m) => send('log', m),
    progress: (p) => send('install-progress', p),
  });
  return ops.status(conn);
})));

ipcMain.handle('revert', wrap(({ ip, uninstall }) => withTv(ip, async (conn) => {
  await ops.revert(conn, { uninstall: !!uninstall, log: (m) => send('log', m) });
  return ops.status(conn);
})));

// Cancela una conexión en curso (p. ej. esperando a que la TV autorice) y espera a que quede libre.
ipcMain.handle('cancel', async () => {
  if (abortCtl) abortCtl.abort();
  await idle;
  return { ok: true };
});

ipcMain.handle('apk-info', wrap(() => {
  const stat = fs.statSync(apkPath());
  return { kb: Math.round(stat.size / 1024) };
}));

function createWindow() {
  win = new BrowserWindow({
    width: 820,
    height: 600,
    minWidth: 720,
    minHeight: 560,
    title: 'Inicio TV',
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#121110' : '#f2f0eb',
    ...(process.platform === 'darwin'
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 20, y: 24 } }
      : { autoHideMenuBar: true }),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
