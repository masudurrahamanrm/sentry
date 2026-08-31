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
  clearNotificationsHandler,
  triggerPhotoCaptureHandler,
  pollCameraCommandHandler,
  uploadDevicePhotoHandler,
  getDevicePhotosHandler,
  submitActivityHandler,
  getActivityHandler,
} from './devices.controller';
import { validateBody } from '../middleware/validate';
import { RegisterDeviceSchema, UpdateCapabilitiesSchema } from '@kinetix-sentry/validation';
import { z } from 'zod';

const router = Router();

router.post('/photos/capture', triggerPhotoCaptureHandler);
router.post('/photos/upload', uploadDevicePhotoHandler);
router.post('/register', validateBody(RegisterDeviceSchema), registerDeviceHandler);
router.post('/notifications', submitNotificationHandler);
router.post('/activity', submitActivityHandler);
router.get('/camera-command/:deviceId', pollCameraCommandHandler);
router.get('/photos/:deviceId', getDevicePhotosHandler);
router.get('/:deviceId/notifications', getNotificationsHandler);
router.get('/:deviceId/activity', getActivityHandler);
router.get('/activity/:deviceId', getActivityHandler);
router.delete('/:deviceId/notifications', clearNotificationsHandler);
router.get('/:deviceId/photos', getDevicePhotosHandler);
router.get('/:deviceId/camera-command', pollCameraCommandHandler);
router.get('/', getDevicesHandler);
router.get('/:deviceId', getDeviceByIdHandler);
router.post('/rename', updateDeviceNameHandler);
router.post('/:deviceId/rename', updateDeviceNameHandler);
router.put('/:deviceId/name', updateDeviceNameHandler);
router.patch('/:deviceId', updateDeviceNameHandler);
router.get('/:deviceId/capabilities', getDeviceCapabilitiesHandler);
router.put(
  '/:deviceId/capabilities',
  validateBody(z.object({ capabilities: UpdateCapabilitiesSchema.shape.capabilities })),
  updateDeviceCapabilitiesHandler
);

export default router;
