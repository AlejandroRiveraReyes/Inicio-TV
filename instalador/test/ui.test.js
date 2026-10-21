'use strict';
// El puente de Electron define `window.tv`. Si app.js declara un `const tv` global,
// lanza «Identifier 'tv' has already been declared» y la interfaz no arranca.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const read = (f) => fs.readFileSync(path.join(__dirname, '..', 'src', f), 'utf8');
const exposed = [...read('preload.js').matchAll(/exposeInMainWorld\('(\w+)'/g)].map((m) => m[1]);

test('app.js no redeclara los nombres que expone el puente', () => {
  const app = read('ui/app.js');
  assert.ok(exposed.length > 0);
  for (const name of exposed) {
    assert.ok(!new RegExp(`^(const|let|var|function|class)\\s+${name}\\b`, 'm').test(app), `app.js declara «${name}» en el ámbito global`);
  }
});

test('la política de seguridad no permite estilos ni scripts en línea', () => {
  const html = read('ui/index.html');
  assert.ok(!/\sstyle="/.test(html), 'el HTML usa style="" y la CSP lo bloquearía');
  assert.ok(!/<script(?![^>]*\ssrc=)/.test(html), 'hay un <script> en línea');
});

test('todas las fuentes referenciadas existen', () => {
  const css = read('ui/style.css');
  for (const [, file] of css.matchAll(/url\("(fonts\/[^"]+)"\)/g)) {
    assert.ok(fs.existsSync(path.join(__dirname, '..', 'src', 'ui', file)), file);
  }
});
