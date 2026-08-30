import http from 'http';
import { WebSocket } from 'ws';
import jwt from 'jsonwebtoken';
import { server } from '../src/index';
import { authService } from '../src/auth/auth.service';
import { MessageType, BaseMessage } from '@kinetix-sentry/protocol';

jest.mock('../src/database/db', () => ({
  query: jest.fn().mockResolvedValue({ rows: [{ id: 'mock-pairing-id' }] }),
  pool: {
    connect: jest.fn(),
    on: jest.fn(),
  },
}));

describe('Phase 8: WebSocket Gateway & Real-Time Event Router', () => {
  let httpServer: http.Server;
  let serverPort: number;

  beforeAll((done) => {
    httpServer = server.listen(0, () => {
      const addr = httpServer.address();
      if (addr && typeof addr === 'object') {
        serverPort = addr.port;
      }
      done();
    });
  });

  afterAll((done) => {
    httpServer.close(() => done());
  });

  it('rejects connection without authentication token', (done) => {
    const ws = new WebSocket(`ws://localhost:${serverPort}/ws`);

    ws.on('error', (err) => {
      expect(err).toBeDefined();
      done();
    });

    ws.on('open', () => {
      ws.close();
      done(new Error('Should not have connected without auth token'));
    });
  });

  it('connects successfully with valid device JWT token', (done) => {
    const testToken = jwt.sign(
      { deviceId: 'KX-1234-5678', deviceName: 'Controller', platform: 'Android' },
      process.env.JWT_SECRET || 'dev_jwt_secret_key_32_characters_minimum_len'
    );

    const ws = new WebSocket(`ws://localhost:${serverPort}/ws?token=${testToken}`);

    ws.on('open', () => {
      expect(ws.readyState).toBe(WebSocket.OPEN);
      ws.close();
      done();
    });
  });

  it('exchanges heartbeat and receives heartbeat ACK', (done) => {
    const testToken = require('jsonwebtoken').sign(
      { deviceId: 'SN-4321-8765', deviceName: 'Agent', platform: 'Android' },
      process.env.JWT_SECRET || 'dev_jwt_secret_key_32_characters_minimum_len'
    );

    const ws = new WebSocket(`ws://localhost:${serverPort}/ws?token=${testToken}`);

    ws.on('open', () => {
      const heartbeatMsg: BaseMessage = {
        type: MessageType.HEARTBEAT,
        id: 'hb_123',
        timestamp: Date.now(),
        payload: {},
      };
      ws.send(JSON.stringify(heartbeatMsg));
    });

    ws.on('message', (data) => {
      const msg: BaseMessage = JSON.parse(data.toString());
      if (msg.type === MessageType.HEARTBEAT_ACK) {
        expect(msg.id).toBe('hb_123');
        ws.close();
        done();
      }
    });
  });
});
