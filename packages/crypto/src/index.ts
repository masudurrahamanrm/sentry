import * as crypto from 'crypto';

/**
 * Generate a friendly device ID in the format SN-XXXX-XXXX or KX-XXXX-XXXX
 * Uses cryptographic randomness without touching sensitive hardware identifiers.
 */
export function generateDeviceId(prefix: 'SN' | 'KX' = 'SN'): string {
  const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'; // base32 without easily confused chars (0, O, 1, I)
  const getRandomChunk = (length: number): string => {
    const bytes = crypto.randomBytes(length);
    let result = '';
    for (let i = 0; i < length; i++) {
      result += chars[bytes[i] % chars.length];
    }
    return result;
  };
  return `${prefix}-${getRandomChunk(4)}-${getRandomChunk(4)}`;
}

export interface KeyPairResult {
  publicKey: string;
  privateKey: string;
}

/**
 * Generate an ECDSA (prime256v1 / NIST P-256) key pair compatible with Android KeyStore & WebCrypto
 */
export function generateKeyPair(): KeyPairResult {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
    publicKeyEncoding: {
      type: 'spki',
      format: 'pem',
    },
    privateKeyEncoding: {
      type: 'pkcs8',
      format: 'pem',
    },
  });
  return { publicKey, privateKey };
}

/**
 * Cryptographically sign a message payload using ECDSA SHA-256
 */
export function signPayload(privateKeyPem: string, payload: string): string {
  const sign = crypto.createSign('SHA256');
  sign.update(payload);
  sign.end();
  return sign.sign(privateKeyPem, 'base64');
}

/**
 * Verify a cryptographic signature against a public key and message payload
 */
export function verifySignature(publicKeyPem: string, payload: string, signatureBase64: string): boolean {
  try {
    const verify = crypto.createVerify('SHA256');
    verify.update(payload);
    verify.end();
    return verify.verify(publicKeyPem, signatureBase64, 'base64');
  } catch {
    return false;
  }
}

/**
 * Generate a random 6-digit numeric pairing code
 */
export function generatePairingCode(): string {
  const num = crypto.randomInt(100000, 999999);
  return num.toString();
}

/**
 * Hash a pairing code with SHA-256 for secure server-side storage
 */
export function hashPairingCode(code: string, salt: string = ''): string {
  return crypto.createHash('sha256').update(`${code}:${salt}`).digest('hex');
}

/**
 * Generate a cryptographic nonce
 */
export function generateNonce(bytes = 16): string {
  return crypto.randomBytes(bytes).toString('hex');
}
