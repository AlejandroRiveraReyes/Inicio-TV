'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { androidPublicKey, loadOrCreateKey, sign, SHA1_DIGEST_INFO } = require('../src/adb');
const { isPrivate } = require('../src/discover');

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'adbtest-'));

function adbBinary() {
  try { return execFileSync('which', ['adb']).toString().trim(); } catch (e) { return null; }
}

test('la clave pública coincide con la que genera `adb pubkey`', { skip: !adbBinary() }, () => {
  const dir = tmp();
  const key = loadOrCreateKey(dir);
  const mine = androidPublicKey(key.pub, 'x').toString().split(' ')[0];
  const reference = execFileSync(adbBinary(), ['pubkey', path.join(dir, 'adbkey')]).toString().trim().split(' ')[0];
  assert.strictEqual(mine, reference);
});

test('la clave se guarda y se vuelve a cargar igual', () => {
  const dir = tmp();
  const a = loadOrCreateKey(dir);
  const b = loadOrCreateKey(dir);
  assert.strictEqual(
    a.pub.export({ type: 'spki', format: 'der' }).toString('hex'),
    b.pub.export({ type: 'spki', format: 'der' }).toString('hex'),
  );
});

test('la firma del token es PKCS#1 v1.5 sobre SHA-1 y verifica con la pública', () => {
  const key = loadOrCreateKey(tmp());
  const token = crypto.randomBytes(20);
  const sig = sign(key.priv, token);
  assert.strictEqual(sig.length, 256);
  const recovered = crypto.publicDecrypt({ key: key.pub, padding: crypto.constants.RSA_PKCS1_PADDING }, sig);
  assert.deepStrictEqual(recovered, Buffer.concat([SHA1_DIGEST_INFO, token]));
});

test('solo se escanean redes privadas', () => {
  assert.ok(isPrivate('192.168.1.5'));
  assert.ok(isPrivate('10.0.0.2'));
  assert.ok(isPrivate('172.20.1.1'));
  assert.ok(!isPrivate('8.8.8.8'));
  assert.ok(!isPrivate('172.32.0.1'));
});
