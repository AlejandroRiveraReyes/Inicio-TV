'use strict';
// Prueba manual contra una TV real:  node test/live.js 192.168.1.151 [--install]
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { connect } = require('../src/adb');
const { scan } = require('../src/discover');
const ops = require('../src/ops');

(async () => {
  const ip = process.argv[2];
  const doInstall = process.argv.includes('--install');
  const keyDir = path.join(os.tmpdir(), 'iniciotv-live-key');

  console.log('1) Búsqueda en la red…');
  const t0 = Date.now();
  const res = await scan();
  console.log(`   redes ${res.networks.join(', ')} · ${res.scanned} IP en ${((Date.now() - t0) / 1000).toFixed(1)} s · encontradas: ${res.found.join(', ') || 'ninguna'}`);
  if (!ip) return;

  console.log(`2) Conectando con ${ip} (acepta el aviso en la TV si aparece)…`);
  const conn = await connect(ip, { keyDir, onAuthRequired: () => console.log('   → la TV pide autorización') });
  console.log('   conectado:', conn.banner.split(';')[0]);
  console.log('3) Estado:', JSON.stringify(await ops.status(conn)));
  if (doInstall) {
    console.log('4) Instalando (modo normal, mismo APK)…');
    const apk = fs.readFileSync(path.join(__dirname, '..', 'resources', 'inicio-tv.apk'));
    let last = -1;
    await ops.install(conn, apk, {
      strong: false,
      log: (m) => console.log('   ' + m),
      progress: (p) => { const pc = Math.floor(p * 4) * 25; if (pc !== last) { last = pc; console.log(`   subida ${pc}%`); } },
    });
    console.log('5) Estado final:', JSON.stringify(await ops.status(conn)));
  }
  conn.close();
})().catch((e) => { console.error('FALLO:', e.code || '', e.message); process.exit(1); });
