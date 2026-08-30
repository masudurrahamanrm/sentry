import { Router } from 'express';
import { listPairingsHandler, getPairingByIdHandler, revokePairingHandler } from './pairings.controller';

const router = Router();

router.get('/', listPairingsHandler);
router.get('/:id', getPairingByIdHandler);
router.delete('/:id', revokePairingHandler);

export default router;
