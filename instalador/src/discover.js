'use strict';
// Busca dispositivos con ADB por red (puerto 5555) en las subredes locales del ordenador.
const net = require('net');
const os = require('os');

function isPrivate(ip) {
  const [a, b] = ip.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function localAddresses() {
  const result = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list || []) {
      if (i.family === 'IPv4' && !i.internal && isPrivate(i.address)) result.push(i.address);
    }
  }
  return result;
}

function probe(ip, port, timeoutMs) {
  return new Promise((resolve) => {
    const s = net.connect({ host: ip, port });
    const done = (ok) => { s.destroy(); resolve(ok); };
    s.setTimeout(timeoutMs, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

/** Recorre la /24 de cada interfaz y devuelve las IP con el puerto 5555 abierto. */
async function scan({ port = 5555, timeoutMs = 700, concurrency = 96, onProgress } = {}) {
  const own = localAddresses();
  const targets = [];
  for (const addr of own) {
    const base = addr.split('.').slice(0, 3).join('.');
    for (let n = 1; n < 255; n++) {
      const ip = `${base}.${n}`;
      if (!own.includes(ip) && !targets.includes(ip)) targets.push(ip);
    }
  }
  const found = [];
  let next = 0;
  let done = 0;
  const worker = async () => {
    while (next < targets.length) {
      const ip = targets[next++];
      if (await probe(ip, port, timeoutMs)) found.push(ip);
      if (onProgress) onProgress(++done / targets.length);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, targets.length) }, worker));
  const key = (ip) => ip.split('.').reduce((acc, n) => acc * 256 + Number(n), 0);
  return { found: found.sort((a, b) => key(a) - key(b)), networks: own, scanned: targets.length };
}

module.exports = { scan, isPrivate, localAddresses };
