'use strict';
// Cliente ADB mínimo por red (TCP 5555). No necesita tener `adb` instalado.
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const A_CNXN = 0x4e584e43;
const A_AUTH = 0x48545541;
const A_OPEN = 0x4e45504f;
const A_OKAY = 0x59414b4f;
const A_CLSE = 0x45534c43;
const A_WRTE = 0x45545257;

const AUTH_TOKEN = 1;
const AUTH_SIGNATURE = 2;
const AUTH_RSAPUBLICKEY = 3;

const VERSION = 0x01000000;
const MAX_DATA = 4096;

// adbd comprueba el token como una firma RSA PKCS#1 de un hash SHA-1 ya calculado.
const SHA1_DIGEST_INFO = Buffer.from('3021300906052b0e03021a05000414', 'hex');

class AdbError extends Error {
  constructor(message, code) {
    super(message);
    this.name = 'AdbError';
    this.code = code;
  }
}

/** Clave RSA propia del instalador, en `dir/adbkey` (PKCS#8 PEM, como la de adb). */
function loadOrCreateKey(dir) {
  const file = path.join(dir, 'adbkey');
  fs.mkdirSync(dir, { recursive: true });
  if (fs.existsSync(file)) {
    try {
      const priv = crypto.createPrivateKey(fs.readFileSync(file));
      return { priv, pub: crypto.createPublicKey(priv) };
    } catch (e) {
      // clave corrupta: se genera otra
    }
  }
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  fs.writeFileSync(file, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  return { priv: privateKey, pub: crypto.createPublicKey(privateKey) };
}

function modInverse(a, m) {
  let [r0, r1] = [m, ((a % m) + m) % m];
  let [t0, t1] = [0n, 1n];
  while (r1 !== 0n) {
    const q = r0 / r1;
    [r0, r1] = [r1, r0 - q * r1];
    [t0, t1] = [t1, t0 - q * t1];
  }
  if (r0 !== 1n) throw new Error('Sin inverso modular');
  return ((t0 % m) + m) % m;
}

/** Clave pública en el formato de adbd (estructura RSAPublicKey de mincrypt) + nombre, en base64. */
function androidPublicKey(pub, label) {
  const jwk = pub.export({ format: 'jwk' });
  const toBig = (b64u) => BigInt('0x' + Buffer.from(b64u, 'base64url').toString('hex'));
  const n = toBig(jwk.n);
  const e = toBig(jwk.e);
  const words = 64;
  const r32 = 1n << 32n;
  const n0inv = (r32 - modInverse(n % r32, r32)) % r32;
  const rr = (1n << BigInt(words * 64)) % n;

  const out = Buffer.alloc(4 + 4 + words * 4 * 2 + 4);
  let off = 0;
  out.writeUInt32LE(words, off); off += 4;
  out.writeUInt32LE(Number(n0inv), off); off += 4;
  for (const big of [n, rr]) {
    let tmp = big;
    for (let i = 0; i < words; i++) {
      out.writeUInt32LE(Number(tmp & 0xffffffffn), off);
      off += 4;
      tmp >>= 32n;
    }
  }
  out.writeUInt32LE(Number(e), off);
  return Buffer.from(out.toString('base64') + ' ' + label + '\0');
}

function sign(priv, token) {
  return crypto.privateEncrypt(
    { key: priv, padding: crypto.constants.RSA_PKCS1_PADDING },
    Buffer.concat([SHA1_DIGEST_INFO, token]),
  );
}

function keyLabel() {
  return 'InicioTV@' + os.hostname().replace(/[^A-Za-z0-9_-]/g, '');
}

class AdbConnection {
  constructor(sock) {
    this.sock = sock;
    this.buf = Buffer.alloc(0);
    this.queue = [];
    this.waiters = [];
    this.error = null;
    this.nextId = 1;
    this.banner = '';
    this.chain = Promise.resolve();
    sock.on('data', (d) => {
      this.buf = Buffer.concat([this.buf, d]);
      this._parse();
    });
    sock.on('error', (e) => this._fail(new AdbError('Error de red: ' + e.message, 'network')));
    sock.on('close', () => this._fail(new AdbError('La TV cerró la conexión', 'closed')));
  }

  _parse() {
    while (this.buf.length >= 24) {
      const len = this.buf.readUInt32LE(12);
      if (this.buf.length < 24 + len) break;
      const msg = {
        cmd: this.buf.readUInt32LE(0),
        arg0: this.buf.readUInt32LE(4),
        arg1: this.buf.readUInt32LE(8),
        data: Buffer.from(this.buf.subarray(24, 24 + len)),
      };
      this.buf = this.buf.subarray(24 + len);
      const w = this.waiters.shift();
      if (w) w.resolve(msg);
      else this.queue.push(msg);
    }
  }

  _fail(err) {
    if (!this.error) this.error = err;
    for (const w of this.waiters.splice(0)) w.reject(this.error);
  }

  read(timeoutMs) {
    if (this.queue.length) return Promise.resolve(this.queue.shift());
    if (this.error) return Promise.reject(this.error);
    return new Promise((resolve, reject) => {
      const w = {
        resolve: (m) => { clearTimeout(timer); resolve(m); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      };
      const timer = setTimeout(() => {
        const i = this.waiters.indexOf(w);
        if (i >= 0) this.waiters.splice(i, 1);
        reject(new AdbError('La TV no respondió a tiempo', 'timeout'));
      }, timeoutMs);
      this.waiters.push(w);
    });
  }

  send(cmd, arg0, arg1, data = Buffer.alloc(0)) {
    const h = Buffer.alloc(24);
    let sum = 0;
    for (const b of data) sum += b;
    h.writeUInt32LE(cmd >>> 0, 0);
    h.writeUInt32LE(arg0 >>> 0, 4);
    h.writeUInt32LE(arg1 >>> 0, 8);
    h.writeUInt32LE(data.length, 12);
    h.writeUInt32LE(sum >>> 0, 16);
    h.writeUInt32LE((cmd ^ 0xffffffff) >>> 0, 20);
    this.sock.write(Buffer.concat([h, data]));
  }

  async handshake(key, onAuthRequired) {
    this.send(A_CNXN, VERSION, MAX_DATA, Buffer.from('host::\0'));
    let signed = false;
    let keysSent = 0;
    try {
      for (;;) {
        const m = await this.read(keysSent ? 120000 : 10000);
        if (m.cmd === A_CNXN) {
          this.banner = m.data.toString();
          return;
        }
        if (m.cmd !== A_AUTH || m.arg0 !== AUTH_TOKEN) {
          throw new AdbError('Respuesta inesperada de la TV', 'protocol');
        }
        if (!signed) {
          this.send(A_AUTH, AUTH_SIGNATURE, 0, sign(key.priv, m.data));
          signed = true;
        } else {
          // Clave desconocida: la TV enseña el aviso «¿Permitir depuración?».
          if (++keysSent > 3) throw new AdbError('La TV rechazó la autorización', 'denied');
          if (keysSent === 1 && onAuthRequired) onAuthRequired();
          this.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, androidPublicKey(key.pub, keyLabel()));
        }
      }
    } catch (e) {
      if (keysSent && (e.code === 'closed' || e.code === 'timeout')) {
        throw new AdbError('No se aceptó la autorización en la TV', 'denied');
      }
      throw e;
    }
  }

  /**
   * Abre un servicio (`shell:…`, `exec:…`), envía `input` si lo hay y devuelve la salida.
   * Las llamadas se encolan: el protocolo de este cliente atiende una conversación a la vez.
   */
  run(service, options = {}) {
    const result = this.chain.then(() => this._run(service, options));
    this.chain = result.catch(() => {});
    return result;
  }

  async _run(service, { input = null, onProgress = null }) {
    const local = this.nextId++;
    this.send(A_OPEN, local, 0, Buffer.from(service + '\0'));
    const st = { remote: 0, out: [], closed: false, okay: false };
    const handle = (m) => {
      if (m.arg1 !== local) return; // mensaje de una conversación anterior
      if (m.cmd === A_OKAY) {
        st.remote = m.arg0;
        st.okay = true;
      } else if (m.cmd === A_WRTE) {
        st.out.push(m.data);
        this.send(A_OKAY, local, m.arg0);
      } else if (m.cmd === A_CLSE) {
        st.closed = true;
        if (!st.remote) st.remote = m.arg0;
      }
    };
    while (!st.okay && !st.closed) handle(await this.read(30000));
    if (st.closed && !st.okay) throw new AdbError('La TV rechazó el servicio ' + service, 'refused');

    if (input) {
      for (let off = 0; off < input.length && !st.closed; off += MAX_DATA) {
        const chunk = input.subarray(off, off + MAX_DATA);
        st.okay = false;
        this.send(A_WRTE, local, st.remote, chunk);
        while (!st.okay && !st.closed) handle(await this.read(30000));
        if (onProgress) onProgress(Math.min(1, (off + chunk.length) / input.length));
      }
    }
    while (!st.closed) handle(await this.read(60000));
    this.send(A_CLSE, local, st.remote);
    return Buffer.concat(st.out).toString('utf8');
  }

  shell(command) {
    return this.run('shell:' + command);
  }

  close() {
    this.sock.destroy();
  }
}

async function connect(host, { port = 5555, keyDir, onAuthRequired, connectTimeout = 4000, signal } = {}) {
  const key = loadOrCreateKey(keyDir);
  const sock = await new Promise((resolve, reject) => {
    const s = net.connect({ host, port });
    const fail = (why) => reject(new AdbError(`No se pudo conectar con ${host}:${port} (${why})`, 'unreachable'));
    const timer = setTimeout(() => { s.destroy(); fail('sin respuesta'); }, connectTimeout);
    s.once('connect', () => { clearTimeout(timer); resolve(s); });
    s.once('error', (e) => { clearTimeout(timer); fail(e.code || e.message); });
  });
  sock.setNoDelay(true);
  const conn = new AdbConnection(sock);
  const onAbort = () => conn.close();
  if (signal) {
    if (signal.aborted) onAbort();
    else signal.addEventListener('abort', onAbort, { once: true });
  }
  try {
    await conn.handshake(key, onAuthRequired);
  } catch (e) {
    conn.close();
    throw e;
  } finally {
    if (signal) signal.removeEventListener('abort', onAbort);
  }
  return conn;
}

module.exports = { connect, AdbError, androidPublicKey, loadOrCreateKey, sign, SHA1_DIGEST_INFO };
