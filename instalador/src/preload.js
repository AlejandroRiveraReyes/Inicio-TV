'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const on = (channel) => (cb) => {
  const listener = (_e, payload) => cb(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('tv', {
  platform: process.platform,
  cancel: () => ipcRenderer.invoke('cancel'),
  discover: () => ipcRenderer.invoke('discover'),
  status: (ip) => ipcRenderer.invoke('status', { ip }),
  install: (ip, strong) => ipcRenderer.invoke('install', { ip, strong }),
  revert: (ip, uninstall) => ipcRenderer.invoke('revert', { ip, uninstall }),
  apkInfo: () => ipcRenderer.invoke('apk-info'),
  onLog: on('log'),
  onScanProgress: on('scan-progress'),
  onInstallProgress: on('install-progress'),
  onAuthPrompt: on('auth-prompt'),
});
