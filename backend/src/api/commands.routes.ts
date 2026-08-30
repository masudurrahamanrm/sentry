import { Router } from 'express';
import { dispatchCommandHandler, getCommandByIdHandler, recordCommandResponseHandler } from './commands.controller';
import { validateBody } from '../middleware/validate';
import { CreateCommandSchema } from '@kinetix-sentry/validation';
import { z } from 'zod';

const router = Router();

router.post('/', validateBody(CreateCommandSchema), dispatchCommandHandler);
router.get('/:commandId', getCommandByIdHandler);
router.post(
  '/:commandId/respond',
  validateBody(
    z.object({
      status: z.enum(['SUCCESS', 'DENIED', 'FAILED']),
      result: z.record(z.unknown()).optional(),
    })
  ),
  recordCommandResponseHandler
);

export default router;
