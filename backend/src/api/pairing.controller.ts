import { Request, Response, NextFunction } from 'express';
import { pairingService } from '../pairing/pairing.service';

export async function startPairingHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { controllerDeviceId, agentDeviceId } = req.body;
    const session = await pairingService.startPairingSession(controllerDeviceId, agentDeviceId);
    res.status(201).json({ session });
  } catch (err) {
    next(err);
  }
}

export async function confirmPairingHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { sessionId, pairingCode, agentDeviceId, signature } = req.body;
    const pairing = await pairingService.confirmPairingSession(
      sessionId,
      pairingCode,
      agentDeviceId,
      signature
    );
    res.status(200).json({ pairing });
  } catch (err) {
    next(err);
  }
}
