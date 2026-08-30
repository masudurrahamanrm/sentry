import { Request, Response, NextFunction } from 'express';
import { storageService } from './storage.service';

export async function requestUploadUrlHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { pairingId, filename, fileSize, contentType } = req.body;
    const uploaderDeviceId = req.device?.deviceId || req.body.uploaderDeviceId;

    const result = await storageService.generateUploadSignedUrl(
      pairingId,
      uploaderDeviceId,
      filename,
      fileSize,
      contentType
    );
    res.status(201).json(result);
  } catch (err) {
    next(err);
  }
}

export async function requestDownloadUrlHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { pairingId, fileId } = req.body;
    const requesterDeviceId = req.device?.deviceId || req.body.requesterDeviceId;

    const result = await storageService.generateDownloadSignedUrl(
      pairingId,
      requesterDeviceId,
      fileId
    );
    res.status(200).json(result);
  } catch (err) {
    next(err);
  }
}
