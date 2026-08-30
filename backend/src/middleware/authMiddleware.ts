import { Request, Response, NextFunction } from 'express';
import { authService, DeviceTokenPayload } from '../auth/auth.service';
import { AppError } from './errorHandler';

declare global {
  namespace Express {
    interface Request {
      device?: DeviceTokenPayload;
    }
  }
}

export function requireDeviceAuth(req: Request, _res: Response, next: NextFunction): void {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    throw new AppError('UNAUTHORIZED', 'Authentication token required.', 401);
  }

  const token = authHeader.substring(7).trim();
  const payload = authService.verifyToken(token);
  req.device = payload;
  next();
}
