import { Router } from 'express';
import { requestUploadUrlHandler, requestDownloadUrlHandler } from './storage.controller';
import { validateBody } from '../middleware/validate';
import { z } from 'zod';

const router = Router();

router.post(
  '/upload-url',
  validateBody(
    z.object({
      pairingId: z.string().uuid(),
      filename: z.string().min(1).max(255),
      fileSize: z.number().int().positive(),
      contentType: z.string().optional(),
      uploaderDeviceId: z.string().optional(),
    })
  ),
  requestUploadUrlHandler
);

router.post(
  '/download-url',
  validateBody(
    z.object({
      pairingId: z.string().uuid(),
      fileId: z.string().min(5),
      requesterDeviceId: z.string().optional(),
    })
  ),
  requestDownloadUrlHandler
);

export default router;
