import {
  generateDeviceId,
  generateKeyPair,
  signPayload,
  verifySignature,
  generatePairingCode,
  hashPairingCode,
  generateNonce,
} from '../src/index';

describe('Phase 5: Cryptography and Key Management', () => {
  describe('Device ID Generation', () => {
    it('generates valid Sentry Device ID format (SN-XXXX-XXXX)', () => {
      const id = generateDeviceId('SN');
      expect(id).toMatch(/^SN-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$/);
    });

    it('generates valid Kinetix Controller ID format (KX-XXXX-XXXX)', () => {
      const id = generateDeviceId('KX');
      expect(id).toMatch(/^KX-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$/);
    });

    it('generates unique device IDs on successive calls', () => {
      const id1 = generateDeviceId('SN');
      const id2 = generateDeviceId('SN');
      expect(id1).not.toBe(id2);
    });
  });

  describe('Key Pair Generation & ECDSA Signatures', () => {
    it('generates a valid PEM-encoded ECDSA key pair', () => {
      const { publicKey, privateKey } = generateKeyPair();
      expect(publicKey).toContain('-----BEGIN PUBLIC KEY-----');
      expect(privateKey).toContain('-----BEGIN PRIVATE KEY-----');
    });

    it('successfully signs and verifies an authentication payload', () => {
      const { publicKey, privateKey } = generateKeyPair();
      const payload = JSON.stringify({
        deviceId: 'SN-7F42-K9P3',
        nonce: generateNonce(),
        timestamp: Date.now(),
      });

      const signature = signPayload(privateKey, payload);
      expect(typeof signature).toBe('string');
      expect(signature.length).toBeGreaterThan(20);

      const isValid = verifySignature(publicKey, payload, signature);
      expect(isValid).toBe(true);
    });

    it('rejects verification if message payload was tampered with', () => {
      const { publicKey, privateKey } = generateKeyPair();
      const payload = JSON.stringify({ deviceId: 'SN-7F42-K9P3', command: 'DEVICE_INFO' });
      const tamperedPayload = JSON.stringify({ deviceId: 'SN-7F42-K9P3', command: 'UNAUTHORIZED_ACCESS' });

      const signature = signPayload(privateKey, payload);
      const isValid = verifySignature(publicKey, tamperedPayload, signature);
      expect(isValid).toBe(false);
    });

    it('rejects verification with wrong public key', () => {
      const keyPairA = generateKeyPair();
      const keyPairB = generateKeyPair();
      const payload = 'test-message-to-authenticate';

      const signature = signPayload(keyPairA.privateKey, payload);
      const isValid = verifySignature(keyPairB.publicKey, payload, signature);
      expect(isValid).toBe(false);
    });
  });

  describe('Pairing Code & Nonce Generation', () => {
    it('generates 6-digit numeric pairing codes', () => {
      const code = generatePairingCode();
      expect(code).toMatch(/^\d{6}$/);
      const num = parseInt(code, 10);
      expect(num).toBeGreaterThanOrEqual(100000);
      expect(num).toBeLessThanOrEqual(999999);
    });

    it('produces deterministic SHA-256 pairing hashes', () => {
      const hash1 = hashPairingCode('123456', 'salt');
      const hash2 = hashPairingCode('123456', 'salt');
      const hash3 = hashPairingCode('654321', 'salt');
      expect(hash1).toBe(hash2);
      expect(hash1).not.toBe(hash3);
    });
  });
});
