export enum StandardCommandType {
  DEVICE_INFO = 'DEVICE_INFO',
  PING = 'PING',
  GET_BATTERY = 'GET_BATTERY',
  GET_LOCATION = 'GET_LOCATION',
  TAKE_PHOTO = 'TAKE_PHOTO',
  LIST_FILES = 'LIST_FILES',
  GET_NOTIFICATION_LOGS = 'GET_NOTIFICATION_LOGS',
}

export interface CommandExecutionResult {
  commandId: string;
  status: 'SUCCESS' | 'DENIED' | 'FAILED';
  result?: Record<string, unknown>;
  reason?: string;
}
