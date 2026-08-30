import { Request, Response, NextFunction } from 'express';
import { authService } from './auth.service';

export async function requestChallengeHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { deviceId } = req.body;
    const challenge = await authService.generateChallenge(deviceId);
    res.status(200).json({ challenge });
  } catch (err) {
    next(err);
  }
}

export async function verifyChallengeHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { challengeId, deviceId, signature } = req.body;
    const result = await authService.verifyChallenge(challengeId, deviceId, signature);
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
}
