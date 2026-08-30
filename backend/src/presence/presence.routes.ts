import { Router } from 'express';
import { getDevicePresenceHandler, postHeartbeatHandler } from './presence.controller';
import { validateBody } from '../middleware/validate';
import { z } from 'zod';
import { DeviceIdSchema } from '@kinetix-sentry/validation';

const router = Router();

router.get('/:deviceId', getDevicePresenceHandler);
router.post(
  '/heartbeat',
  validateBody(z.object({ deviceId: DeviceIdSchema })),
  postHeartbeatHandler
);

export default router;
