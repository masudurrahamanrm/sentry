import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import url from 'url';
import { authService, DeviceTokenPayload } from '../auth/auth.service';
import { pairingService } from '../pairing/pairing.service';
import { commandService } from '../commands/commands.service';
import { query } from '../database/db';
import { MessageType, BaseMessage } from '@kinetix-sentry/protocol';
import { logger } from '../index';

interface AuthenticatedSocket extends WebSocket {
  device?: DeviceTokenPayload;
  isAlive?: boolean;
}

export class WebSocketGateway {
  private wss: WebSocketServer;
  private connectedSockets = new Map<string, AuthenticatedSocket>();

  constructor(server: http.Server) {
    this.wss = new WebSocketServer({ noServer: true });

    server.on('upgrade', (request, socket, head) => {
      const parsedUrl = url.parse(request.url || '', true);
      if (parsedUrl.pathname === '/ws') {
        const token = parsedUrl.query.token as string;
        if (!token) {
          socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
          socket.destroy();
          return;
        }

        try {
          const device = authService.verifyToken(token);
          this.wss.handleUpgrade(request, socket, head, (ws) => {
            const authWs = ws as AuthenticatedSocket;
            authWs.device = device;
            authWs.isAlive = true;
            this.wss.emit('connection', authWs, request);
          });
        } catch {
          socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
          socket.destroy();
        }
      }
    });

    this.wss.on('connection', (ws: AuthenticatedSocket) => {
      this.handleConnection(ws);
    });

    // Heartbeat liveness checker every 30 seconds
    const interval = setInterval(() => {
      this.wss.clients.forEach((ws) => {
        const authWs = ws as AuthenticatedSocket;
        if (authWs.isAlive === false) {
          authWs.terminate();
          return;
        }
        authWs.isAlive = false;
        authWs.ping();
      });
    }, 30000);

    this.wss.on('close', () => {
      clearInterval(interval);
    });
  }

  private async handleConnection(ws: AuthenticatedSocket) {
    const device = ws.device;
    if (!device) {
      ws.close(4001, 'Unauthorized');
      return;
    }

    const deviceId = device.deviceId;
    this.connectedSockets.set(deviceId, ws);
    logger.info({ deviceId, platform: device.platform }, 'Device connected via WebSocket');

    // Update status in DB
    await query("UPDATE devices SET status = 'ONLINE', last_seen_at = NOW() WHERE device_id = $1", [deviceId]);

    // Notify paired peers of online state
    await this.notifyPairedPeers(deviceId, MessageType.DEVICE_ONLINE, { deviceId });

    ws.on('pong', () => {
      ws.isAlive = true;
    });

    ws.on('message', async (data: Buffer | string) => {
      try {
        const message: BaseMessage = JSON.parse(data.toString());
        await this.handleMessage(ws, message);
      } catch (err) {
        logger.warn({ err, deviceId }, 'Failed to parse incoming WebSocket message');
        this.sendError(ws, 'INVALID_MESSAGE_FORMAT', 'Message could not be parsed as valid JSON');
      }
    });

    ws.on('close', async () => {
      this.connectedSockets.delete(deviceId);
      logger.info({ deviceId }, 'Device disconnected from WebSocket');

      // Update status in DB
      await query("UPDATE devices SET status = 'OFFLINE', last_seen_at = NOW() WHERE device_id = $1", [deviceId]);

      // Notify paired peers of offline state
      await this.notifyPairedPeers(deviceId, MessageType.DEVICE_OFFLINE, { deviceId });
    });
  }

  private async handleMessage(ws: AuthenticatedSocket, message: BaseMessage) {
    const sender = ws.device!;

    switch (message.type) {
      case MessageType.HEARTBEAT:
        await query("UPDATE devices SET last_seen_at = NOW() WHERE device_id = $1", [sender.deviceId]);
        this.send(ws, {
          type: MessageType.HEARTBEAT_ACK,
          id: message.id,
          timestamp: Date.now(),
          payload: { status: 'OK' },
        });
        break;

      case MessageType.COMMAND_REQUEST: {
        const payload = message.payload as { targetDeviceId: string; pairingId: string; type: string; payload: Record<string, unknown>; nonce: string; timestamp: number };
        const isPaired = await this.verifyPairing(sender.deviceId, payload.targetDeviceId);
        if (!isPaired) {
          this.sendError(ws, 'DEVICE_NOT_PAIRED', 'The target device is not paired with your device.');
          return;
        }

        const targetSocket = this.connectedSockets.get(payload.targetDeviceId);
        if (!targetSocket) {
          this.sendError(ws, 'DEVICE_OFFLINE', 'The target device is currently offline.');
          return;
        }

        // Forward command request to target Sentry device
        this.send(targetSocket, message);
        break;
      }

      case MessageType.COMMAND_RESPONSE: {
        const payload = message.payload as { commandId: string; status: 'SUCCESS' | 'DENIED' | 'FAILED'; result?: Record<string, unknown>; targetDeviceId?: string };
        
        // Record in DB
        await commandService.recordCommandResponse(payload.commandId, payload.status, payload.result);

        if (payload.targetDeviceId) {
          const targetSocket = this.connectedSockets.get(payload.targetDeviceId);
          if (targetSocket) {
            this.send(targetSocket, message);
          }
        }
        break;
      }

      default:
        logger.debug({ type: message.type, sender: sender.deviceId }, 'Received unhandled WS message type');
    }
  }

  public sendToDevice(deviceId: string, message: BaseMessage): boolean {
    const socket = this.connectedSockets.get(deviceId);
    if (socket && socket.readyState === WebSocket.OPEN) {
      this.send(socket, message);
      return true;
    }
    return false;
  }

  public isDeviceOnline(deviceId: string): boolean {
    const socket = this.connectedSockets.get(deviceId);
    return !!socket && socket.readyState === WebSocket.OPEN;
  }

  private send(ws: WebSocket, message: BaseMessage): void {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(message));
    }
  }

  private sendError(ws: WebSocket, code: string, messageText: string): void {
    this.send(ws, {
      type: MessageType.ERROR,
      id: `err_${Date.now()}`,
      timestamp: Date.now(),
      payload: { code, message: messageText },
    });
  }

  private async verifyPairing(deviceId1: string, deviceId2: string): Promise<boolean> {
    const res = await query(
      `SELECT id FROM pairings 
       WHERE ((controller_device_id = $1 AND agent_device_id = $2) 
          OR (controller_device_id = $2 AND agent_device_id = $1)) 
         AND status = 'ACTIVE'`,
      [deviceId1, deviceId2]
    );
    return res.rows.length > 0;
  }

  private async notifyPairedPeers(deviceId: string, eventType: MessageType, payload: Record<string, unknown>) {
    try {
      const pairings = await pairingService.listPairingsForDevice(deviceId);
      for (const pairing of pairings) {
        const peerId = pairing.controllerDeviceId === deviceId ? pairing.agentDeviceId : pairing.controllerDeviceId;
        const peerSocket = this.connectedSockets.get(peerId);
        if (peerSocket) {
          this.send(peerSocket, {
            type: eventType,
            id: `evt_${Date.now()}`,
            timestamp: Date.now(),
            payload,
          });
        }
      }
    } catch (err) {
      logger.error({ err, deviceId }, 'Failed to notify paired peers of presence event');
    }
  }

  public close(): Promise<void> {
    return new Promise((resolve) => {
      this.wss.close(() => resolve());
    });
  }
}

export let gateway: WebSocketGateway;

export function setupWebSocketServer(server: http.Server): WebSocketGateway {
  gateway = new WebSocketGateway(server);
  return gateway;
}
