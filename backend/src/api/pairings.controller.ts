import { Request, Response, NextFunction } from 'express';
import { pairingService } from '../pairing/pairing.service';

export async function listPairingsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.query.deviceId as string;
    if (!deviceId) {
      res.status(400).json({ error: { code: 'MISSING_QUERY', message: 'deviceId query param is required.' } });
      return;
    }
    const pairings = await pairingService.listPairingsForDevice(deviceId);
    res.json({ pairings });
  } catch (err) {
    next(err);
  }
}

export async function getPairingByIdHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const pairing = await pairingService.getPairingById(req.params.id);
    res.json({ pairing });
  } catch (err) {
    next(err);
  }
}

export async function revokePairingHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const pairing = await pairingService.revokePairing(req.params.id);
    res.json({ pairing });
  } catch (err) {
    next(err);
  }
}
