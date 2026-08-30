import { DeviceStatus, DeviceCapabilities, PlatformType } from '@kinetix-sentry/types';
export * from './commands';

export enum MessageType {
  // Registration
  DEVICE_REGISTER = 'DEVICE_REGISTER',
  DEVICE_REGISTERED = 'DEVICE_REGISTERED',

  // Pairing Flow
  PAIR_REQUEST = 'PAIR_REQUEST',
  PAIR_CODE = 'PAIR_CODE',
  PAIR_CONFIRM = 'PAIR_CONFIRM',
  PAIR_SUCCESS = 'PAIR_SUCCESS',
  PAIR_FAILED = 'PAIR_FAILED',

  // Presence & Heartbeat
  DEVICE_ONLINE = 'DEVICE_ONLINE',
  DEVICE_OFFLINE = 'DEVICE_OFFLINE',
  HEARTBEAT = 'HEARTBEAT',
  HEARTBEAT_ACK = 'HEARTBEAT_ACK',

  // Commands
  COMMAND_REQUEST = 'COMMAND_REQUEST',
  COMMAND_RESPONSE = 'COMMAND_RESPONSE',

  // Capabilities
  CAPABILITY_REQUEST = 'CAPABILITY_REQUEST',
  CAPABILITY_RESPONSE = 'CAPABILITY_RESPONSE',

  // Management
  UNPAIR_DEVICE = 'UNPAIR_DEVICE',
  REVOKE_DEVICE = 'REVOKE_DEVICE',

  // Errors
  ERROR = 'ERROR',
}

export interface BaseMessage<T = unknown> {
  type: MessageType;
  id: string;
  timestamp: number;
  payload: T;
}

export interface RegisterPayload {
  deviceId: string;
  deviceName: string;
  platform: PlatformType;
  osVersion: string;
  appVersion: string;
  publicKey: string;
}

export interface PairRequestPayload {
  controllerDeviceId: string;
  agentDeviceId: string;
}

export interface PairCodePayload {
  sessionId: string;
  pairingCode: string;
  expiresInSeconds: number;
}

export interface PairConfirmPayload {
  sessionId: string;
  agentDeviceId: string;
  controllerDeviceId: string;
  pairingCode: string;
  signature: string;
}

export interface CommandRequestPayload {
  commandId: string;
  pairingId: string;
  targetDeviceId: string;
  type: string;
  timestamp: number;
  nonce: string;
  payload: Record<string, unknown>;
  signature?: string;
}

export interface CommandResponsePayload {
  commandId: string;
  status: 'SUCCESS' | 'DENIED' | 'FAILED';
  result?: Record<string, unknown>;
  reason?: string;
}

export interface CapabilityUpdatePayload {
  deviceId: string;
  capabilities: DeviceCapabilities;
}

export interface ErrorPayload {
  code: string;
  message: string;
  details?: unknown;
}
