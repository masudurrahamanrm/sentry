import { Router } from 'express';
import { requestChallengeHandler, verifyChallengeHandler } from './auth.controller';
import { validateBody } from '../middleware/validate';
import { z } from 'zod';
import { DeviceIdSchema } from '@kinetix-sentry/validation';

const router = Router();

router.post(
  '/challenge',
  validateBody(z.object({ deviceId: DeviceIdSchema })),
  requestChallengeHandler
);

router.post(
  '/verify',
  validateBody(
    z.object({
      challengeId: z.string().min(10),
      deviceId: DeviceIdSchema,
      signature: z.string().min(10),
    })
  ),
  verifyChallengeHandler
);

export default router;
