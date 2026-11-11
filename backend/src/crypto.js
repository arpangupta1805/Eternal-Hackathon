// libsodium helpers and server keys. Primitives are fixed by docs/03-protocol.md:
// crypto_sign (combined mode), crypto_sign_open, crypto_box_seal, crypto_box_seal_open.

import fs from 'node:fs';
import path from 'node:path';
import sodium from 'libsodium-wrappers';

export const SIGN_BYTES = 64;
export const PUBLIC_KEY_BYTES = 32;

/** Must be awaited once before any other function here is used. */
export async function initCrypto() {
  await sodium.ready;
}

// ---- base64: standard alphabet, with padding (RFC 4648) ----

const B64_RE = /^[A-Za-z0-9+/]*={0,2}$/;

export function b64encode(bytes) {
  return Buffer.from(bytes).toString('base64');
}

/** Strict decode. Returns a Uint8Array, or null if the input is not valid padded base64. */
export function b64decode(str) {
  if (typeof str !== 'string' || str.length % 4 !== 0 || !B64_RE.test(str)) return null;
  return new Uint8Array(Buffer.from(str, 'base64'));
}

export function utf8(str) {
  return new Uint8Array(Buffer.from(str, 'utf8'));
}

export function fromUtf8(bytes) {
  return Buffer.from(bytes).toString('utf8');
}

// ---- keys ----

export function generateSignKeypair() {
  const k = sodium.crypto_sign_keypair();
  return { pk: k.publicKey, sk: k.privateKey };
}

export function generateBoxKeypair() {
  const k = sodium.crypto_box_keypair();
  return { pk: k.publicKey, sk: k.privateKey };
}

/**
 * Server keys: { sign: {pk, sk}, box: {pk, sk} }.
 * With a file path, keys are loaded from it or created and saved on first start.
 * With no path, fresh in-memory keys are returned (tests).
 */
export function loadOrCreateServerKeys(keysPath) {
  if (keysPath && fs.existsSync(keysPath)) {
    const j = JSON.parse(fs.readFileSync(keysPath, 'utf8'));
    return {
      sign: { pk: b64decode(j.sign_pk), sk: b64decode(j.sign_sk) },
      box: { pk: b64decode(j.box_pk), sk: b64decode(j.box_sk) },
    };
  }
  const keys = { sign: generateSignKeypair(), box: generateBoxKeypair() };
  if (keysPath) {
    fs.mkdirSync(path.dirname(keysPath), { recursive: true });
    const j = {
      sign_pk: b64encode(keys.sign.pk),
      sign_sk: b64encode(keys.sign.sk),
      box_pk: b64encode(keys.box.pk),
      box_sk: b64encode(keys.box.sk),
    };
    fs.writeFileSync(keysPath, JSON.stringify(j, null, 2), { mode: 0o600 });
  }
  return keys;
}

// ---- operations ----

/** Combined-mode signature: 64-byte signature followed by the message. */
export function sign(messageBytes, signSk) {
  return sodium.crypto_sign(messageBytes, signSk);
}

/** Returns the message bytes, or null if the signature is invalid. */
export function signOpen(signedBytes, signPk) {
  try {
    return sodium.crypto_sign_open(signedBytes, signPk);
  } catch {
    return null;
  }
}

export function seal(messageBytes, boxPk) {
  return sodium.crypto_box_seal(messageBytes, boxPk);
}

/** Returns the plaintext bytes, or null if the box cannot be opened. */
export function sealOpen(sealedBytes, boxPk, boxSk) {
  try {
    return sodium.crypto_box_seal_open(sealedBytes, boxPk, boxSk);
  } catch {
    return null;
  }
}
