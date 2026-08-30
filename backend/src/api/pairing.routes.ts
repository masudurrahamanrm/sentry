import { Router } from 'express';
import { startPairingHandler, confirmPairingHandler } from './pairing.controller';
import { validateBody } from '../middleware/validate';
import { StartPairingSchema, ConfirmPairingSchema } from '@kinetix-sentry/validation';

const router = Router();

router.post('/start', validateBody(StartPairingSchema), startPairingHandler);
router.post('/confirm', validateBody(ConfirmPairingSchema), confirmPairingHandler);

export default router;
