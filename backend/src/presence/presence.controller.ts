import { Request, Response, NextFunction } from 'express';
import { presenceService } from './presence.service';

export async function getDevicePresenceHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const presence = await presenceService.getDevicePresence(req.params.deviceId);
    res.json({ presence });
  } catch (err) {
    next(err);
  }
}

export async function postHeartbeatHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.device?.deviceId || req.body.deviceId;
    if (!deviceId) {
      res.status(400).json({ error: { code: 'MISSING_DEVICE_ID', message: 'Device ID is required.' } });
      return;
    }
    await presenceService.recordHeartbeat(deviceId);
    res.json({ status: 'OK', timestamp: new Date().toISOString() });
  } catch (err) {
    next(err);
  }
}
