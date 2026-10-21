'use strict';
// Operaciones sobre la TV. Equivalen a scripts/instalar.sh y scripts/revertir.sh.

const PKG = 'com.launchertv.home';
const ALIAS = `${PKG}/${PKG}.HomeAlias`;
const STOCK = [
  'com.google.android.tvlauncher',
  'com.google.android.apps.tv.launcherx',
  'com.google.android.leanbacklauncher',
];
const STATE_FILE = '/data/local/tmp/launchertv_desactivados.txt';
const HOME_INTENT = 'am start -a android.intent.action.MAIN -c android.intent.category.HOME';

const lines = (s) => s.replace(/\r/g, '').split('\n').map((l) => l.trim()).filter(Boolean);

/** Datos básicos de la TV y estado de Inicio TV. */
async function status(conn) {
  const prop = async (p) => (await conn.shell(`getprop ${p}`)).trim();
  const [manufacturer, model, release] = await Promise.all([
    prop('ro.product.manufacturer'),
    prop('ro.product.model'),
    prop('ro.build.version.release'),
  ]);
  const installed = (await conn.shell(`pm list packages ${PKG}`)).includes(`package:${PKG}`);
  let version = '';
  if (installed) {
    const m = (await conn.shell(`dumpsys package ${PKG} | grep -m1 versionName`)).match(/versionName=(\S+)/);
    version = m ? m[1] : '';
  }
  const disabled = lines(await conn.shell('pm list packages -d'));
  const stockDisabled = STOCK.filter((p) => disabled.includes(`package:${p}`));
  const home = lines(await conn.shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'))
    .pop() || '';
  return {
    name: [manufacturer, model].filter(Boolean).join(' ') || 'Android TV',
    android: release,
    installed,
    version,
    stockDisabled,
    isDefault: home.startsWith(PKG + '/'),
  };
}

async function install(conn, apk, { strong, log, progress }) {
  log(`Enviando Inicio TV a la TV (${(apk.length / 1024).toFixed(0)} KB)…`);
  const out = await conn.run(`exec:cmd package install -r -S ${apk.length}`, { input: apk, onProgress: progress });
  if (!/Success/.test(out)) {
    throw new Error('La TV rechazó la instalación: ' + (out.trim() || 'sin detalles'));
  }
  log('Instalado.');

  await conn.shell(`pm enable ${ALIAS}`);

  if (strong) {
    for (const pkg of STOCK) {
      if (!(await conn.shell(`pm path ${pkg}`)).includes('package:')) continue;
      log(`Desactivando el launcher de fábrica (${pkg})… se puede deshacer.`);
      await conn.shell(`pm disable-user --user 0 ${pkg}`);
      await conn.shell(`grep -qx ${pkg} ${STATE_FILE} 2>/dev/null || echo ${pkg} >> ${STATE_FILE}`);
    }
  }

  log('Estableciendo Inicio TV como launcher predeterminado…');
  await conn.shell(`cmd package set-home-activity ${ALIAS}`);
  await conn.shell(HOME_INTENT);
  log('Listo. Pulsa Inicio en el mando de la TV.');
}

async function revert(conn, { uninstall, log }) {
  log('Reactivando el launcher original…');
  const saved = lines(await conn.shell(`cat ${STATE_FILE} 2>/dev/null`));
  const disabled = lines(await conn.shell('pm list packages -d'));
  for (const pkg of new Set([...saved, ...STOCK])) {
    if (!disabled.includes(`package:${pkg}`)) continue;
    log(`Activando ${pkg}`);
    await conn.shell(`pm enable ${pkg}`);
  }
  await conn.shell(`rm -f ${STATE_FILE}`);
  await conn.shell(`pm disable ${ALIAS}`);

  // Preferir el launcher de Google: otras apps (p. ej. Orange TV) también se declaran HOME.
  const homes = lines(await conn.shell('cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'))
    .filter((l) => l.includes('/') && !l.startsWith(PKG + '/') && !l.includes('FallbackHome') && !l.startsWith('com.android.provision'));
  const original = homes.find((l) => /^com\.google\.android\.(tvlauncher|apps\.tv\.launcherx|leanbacklauncher)\//.test(l)) || homes[0];
  if (original) {
    log(`Launcher predeterminado: ${original}`);
    await conn.shell(`cmd package set-home-activity ${original}`);
  }
  if (uninstall) {
    log('Desinstalando Inicio TV…');
    await conn.shell(`pm uninstall ${PKG}`);
  }
  await conn.shell(HOME_INTENT);
  log('Listo: la TV vuelve a usar su launcher original.');
}

module.exports = { status, install, revert, PKG };
