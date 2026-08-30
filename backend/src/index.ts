import express from 'express';
import http from 'http';
import cors from 'cors';
import dotenv from 'dotenv';
import pino from 'pino';

dotenv.config();

const logger = pino({
  transport: {
    target: 'pino-pretty',
    options: { colorize: true },
  },
});

import apiRouter from './api/routes';
import { errorHandler } from './middleware/errorHandler';
import { setupWebSocketServer } from './websocket';

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 4000;

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

app.get('/health', (req, res) => {
  res.json({
    status: 'HEALTHY',
    service: 'kinetix-sentry-backend',
    timestamp: new Date().toISOString(),
  });
});

app.use('/api/v1', apiRouter);

// Global Error Handler
app.use(errorHandler);

// Setup WebSocket Gateway
const wsGateway = setupWebSocketServer(server);

if (process.env.NODE_ENV !== 'test') {
  server.listen(PORT, () => {
    logger.info(`Kinetix-Sentry Backend server running on port ${PORT}`);
  });
}

export { app, server, logger, wsGateway };
