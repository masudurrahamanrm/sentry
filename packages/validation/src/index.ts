import { z } from 'zod';

export const DeviceIdSchema = z.string().regex(/^(SN|KX)-[A-Z0-9]{4}-[A-Z0-9]{4}$/, {
  message: 'Device ID must follow the format SN-XXXX-XXXX or KX-XXXX-XXXX',
});

export const RegisterDeviceSchema = z.object({
  deviceId: DeviceIdSchema,
  deviceName: z.string().min(1).max(100),
  platform: z.enum(['Android', 'iOS', 'Desktop', 'Web']),
  osVersion: z.string().min(1).max(50),
  appVersion: z.string().min(1).max(50),
  publicKey: z.string().min(32),
  capabilities: z
    .object({
      camera: z.boolean().default(false),
      location: z.boolean().default(false),
      notifications: z.boolean().default(false),
      files: z.boolean().default(false),
      microphone: z.boolean().default(false),
      battery: z.boolean().default(true),
    })
    .optional(),
});

export const StartPairingSchema = z.object({
  controllerDeviceId: DeviceIdSchema,
  agentDeviceId: DeviceIdSchema,
});

export const ConfirmPairingSchema = z.object({
  sessionId: z.string().uuid(),
  pairingCode: z.string().length(6),
  agentDeviceId: DeviceIdSchema,
  signature: z.string().min(10),
});

export const CreateCommandSchema = z.object({
  pairingId: z.string().uuid(),
  commandType: z.string().min(1),
  nonce: z.string().min(8),
  timestamp: z.number().int().positive(),
  payload: z.record(z.unknown()),
  signature: z.string().min(10).optional(),
});

export const UpdateCapabilitiesSchema = z.object({
  deviceId: DeviceIdSchema,
  capabilities: z.object({
    camera: z.boolean(),
    location: z.boolean(),
    notifications: z.boolean(),
    files: z.boolean(),
    microphone: z.boolean(),
    battery: z.boolean(),
  }),
});
