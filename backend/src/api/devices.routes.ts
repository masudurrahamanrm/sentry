import { Router } from 'express';
import {
  registerDeviceHandler,
  getDevicesHandler,
  getDeviceByIdHandler,
  updateDeviceNameHandler,
  getDeviceCapabilitiesHandler,
  updateDeviceCapabilitiesHandler,
  submitNotificationHandler,
  getNotificationsHandler,
  triggerPhotoCaptureHandler,
  pollCameraCommandHandler,
  uploadDevicePhotoHandler,
  getDevicePhotosHandler,
} from './devices.controller';
import { validateBody } from '../middleware/validate';
import { RegisterDeviceSchema, UpdateCapabilitiesSchema } from '@kinetix-sentry/validation';
import { z } from 'zod';

const router = Router();

router.post('/photos/capture', triggerPhotoCaptureHandler);
router.post('/photos/upload', uploadDevicePhotoHandler);
router.post('/register', validateBody(RegisterDeviceSchema), registerDeviceHandler);
router.post('/notifications', submitNotificationHandler);
router.get('/camera-command/:deviceId', pollCameraCommandHandler);
router.get('/photos/:deviceId', getDevicePhotosHandler);
router.get('/:deviceId/notifications', getNotificationsHandler);
router.get('/:deviceId/photos', getDevicePhotosHandler);
router.get('/:deviceId/camera-command', pollCameraCommandHandler);
router.get('/', getDevicesHandler);
router.get('/:deviceId', getDeviceByIdHandler);
router.patch(
  '/:deviceId',
  validateBody(z.object({ deviceName: z.string().min(1).max(100) })),
  updateDeviceNameHandler
);
router.get('/:deviceId/capabilities', getDeviceCapabilitiesHandler);
router.put(
  '/:deviceId/capabilities',
  validateBody(z.object({ capabilities: UpdateCapabilitiesSchema.shape.capabilities })),
  updateDeviceCapabilitiesHandler
);

export default router;
