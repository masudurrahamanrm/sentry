export type PlatformType = 'Android' | 'iOS' | 'Desktop' | 'Web';

export type DeviceStatus = 'ONLINE' | 'OFFLINE' | 'CONNECTING' | 'PAIRING' | 'REVOKED';

export type PairingStatus = 'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'REJECTED' | 'REVOKED';

export type CommandStatus = 'PENDING' | 'SENT' | 'SUCCESS' | 'DENIED' | 'FAILED' | 'EXPIRED';

export interface Device {
  id: string;
  deviceId: string;
  deviceName: string;
  platform: PlatformType;
  osVersion: string;
  appVersion: string;
  publicKey: string;
  status: DeviceStatus;
  capabilities?: DeviceCapabilities;
  createdAt: Date;
  lastSeenAt: Date;
}

export interface PairingSession {
  id: string;
  controllerDeviceId: string;
  agentDeviceId: string;
  pairingCodeHash: string;
  expiresAt: Date;
  status: PairingStatus;
  createdAt: Date;
  confirmedAt?: Date | null;
}

export interface Pairing {
  id: string;
  controllerDeviceId: string;
  agentDeviceId: string;
  createdAt: Date;
  lastUsedAt: Date;
  status: PairingStatus;
  revokedAt?: Date | null;
}

export interface Command {
  id: string;
  pairingId: string;
  commandId: string;
  commandType: string;
  payload: Record<string, unknown>;
  status: CommandStatus;
  createdAt: Date;
  completedAt?: Date | null;
}

export interface DeviceCapabilities {
  camera: boolean;
  location: boolean;
  notifications: boolean;
  files: boolean;
  microphone: boolean;
  battery: boolean;
}
