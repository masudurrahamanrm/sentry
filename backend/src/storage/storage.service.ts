import { query } from '../database/db';
import { AppError } from '../middleware/errorHandler';
import { generateNonce } from '@kinetix-sentry/crypto';
import { r2Service } from './r2.service';
import { isMongoConnected, FileModel } from '../database/mongo';
import { logger } from '../logger';

const MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
const SIGNED_URL_TTL_SECONDS = 900; // 15 minutes
const STORAGE_ENDPOINT = process.env.OBJECT_STORAGE_ENDPOINT || 'http://localhost:9000';
const STORAGE_BUCKET = process.env.OBJECT_STORAGE_BUCKET || 'sentry-files';

interface StoredFileMetadata {
  fileId: string;
  pairingId: string;
  uploaderDeviceId: string;
  filename: string;
  fileSize: number;
  contentType: string;
  r2Key?: string;
  r2Url?: string;
  createdAt: number;
}

const fileMetadataMap = new Map<string, StoredFileMetadata>();

export class StorageService {
  async generateUploadSignedUrl(
    pairingId: string,
    uploaderDeviceId: string,
    filename: string,
    fileSize: number,
    contentType: string = 'application/octet-stream'
  ): Promise<{ fileId: string; uploadUrl: string; expiresInSeconds: number; publicUrl?: string }> {
    // 1. Verify file size limit
    if (fileSize > MAX_FILE_SIZE_BYTES) {
      throw new AppError(
        'FILE_TOO_LARGE',
        `File size exceeds maximum permitted limit of ${MAX_FILE_SIZE_BYTES / (1024 * 1024)}MB.`,
        400
      );
    }

    // 2. Verify active pairing relationship
    const pairRes = await query<{ id: string; status: string; controller_device_id: string; agent_device_id: string }>(
      'SELECT id, status, controller_device_id, agent_device_id FROM pairings WHERE id = $1',
      [pairingId]
    );

    if (pairRes.rows.length === 0) {
      throw new AppError('PAIRING_NOT_FOUND', 'Pairing relationship not found.', 404);
    }

    if (pairRes.rows[0].status !== 'ACTIVE') {
      throw new AppError('PAIRING_REVOKED', 'Cannot upload file to a revoked pairing.', 403);
    }

    const fileId = `file_${Date.now()}_${generateNonce(8)}`;
    const objectKey = `${pairingId}/${fileId}/${encodeURIComponent(filename)}`;

    let uploadUrl: string;
    let publicUrl: string | undefined;

    // Use Cloudflare R2 presigned URL if configured
    if (r2Service.isConfigured()) {
      try {
        const presigned = await r2Service.generatePresignedUploadUrl(objectKey, contentType, SIGNED_URL_TTL_SECONDS);
        uploadUrl = presigned.uploadUrl;
        publicUrl = presigned.publicUrl;
      } catch (err) {
        logger.warn({ err }, 'R2 presigned upload failed, falling back to local endpoint');
        uploadUrl = `${STORAGE_ENDPOINT}/${STORAGE_BUCKET}/${objectKey}?token=${generateNonce(16)}&expires=${Date.now() + SIGNED_URL_TTL_SECONDS * 1000}`;
      }
    } else {
      uploadUrl = `${STORAGE_ENDPOINT}/${STORAGE_BUCKET}/${objectKey}?token=${generateNonce(16)}&expires=${Date.now() + SIGNED_URL_TTL_SECONDS * 1000}`;
    }

    const meta: StoredFileMetadata = {
      fileId,
      pairingId,
      uploaderDeviceId,
      filename,
      fileSize,
      contentType,
      r2Key: objectKey,
      r2Url: publicUrl,
      createdAt: Date.now(),
    };

    fileMetadataMap.set(fileId, meta);

    // Persist in MongoDB if connected
    if (isMongoConnected()) {
      try {
        await FileModel.create({
          fileId,
          pairingId,
          uploaderDeviceId,
          filename,
          fileSize,
          contentType,
          r2Key: objectKey,
          r2Url: publicUrl,
        });
      } catch (err) {
        logger.warn({ err }, 'MongoDB file metadata persistence error');
      }
    }

    return {
      fileId,
      uploadUrl,
      expiresInSeconds: SIGNED_URL_TTL_SECONDS,
      publicUrl,
    };
  }

  async generateDownloadSignedUrl(
    pairingId: string,
    requesterDeviceId: string,
    fileId: string
  ): Promise<{ downloadUrl: string; filename: string; contentType: string; expiresInSeconds: number }> {
    // 1. Verify pairing exists and is active
    const pairRes = await query<{ id: string; status: string; controller_device_id: string; agent_device_id: string }>(
      'SELECT id, status, controller_device_id, agent_device_id FROM pairings WHERE id = $1',
      [pairingId]
    );

    if (pairRes.rows.length === 0 || pairRes.rows[0].status !== 'ACTIVE') {
      throw new AppError('PAIRING_NOT_FOUND', 'Active pairing relationship required.', 403);
    }

    // 2. Lookup file metadata from MongoDB or memory
    let file = fileMetadataMap.get(fileId);

    if (!file && isMongoConnected()) {
      try {
        const dbFile = await FileModel.findOne({ fileId }).lean();
        if (dbFile) {
          file = {
            fileId: dbFile.fileId,
            pairingId: dbFile.pairingId,
            uploaderDeviceId: dbFile.uploaderDeviceId,
            filename: dbFile.filename,
            fileSize: dbFile.fileSize,
            contentType: dbFile.contentType,
            r2Key: dbFile.r2Key,
            r2Url: dbFile.r2Url,
            createdAt: dbFile.createdAt ? new Date(dbFile.createdAt).getTime() : Date.now(),
          };
        }
      } catch (_) {}
    }

    if (!file || file.pairingId !== pairingId) {
      throw new AppError('FILE_NOT_FOUND', 'The requested file was not found or belongs to a different pairing.', 404);
    }

    const objectKey = file.r2Key || `${pairingId}/${fileId}/${encodeURIComponent(file.filename)}`;
    let downloadUrl: string;

    if (r2Service.isConfigured()) {
      try {
        const presigned = await r2Service.generatePresignedDownloadUrl(objectKey, SIGNED_URL_TTL_SECONDS);
        downloadUrl = presigned.downloadUrl;
      } catch (err) {
        logger.warn({ err }, 'R2 presigned download failed, falling back to local endpoint');
        downloadUrl = `${STORAGE_ENDPOINT}/${STORAGE_BUCKET}/${objectKey}?token=${generateNonce(16)}&expires=${Date.now() + SIGNED_URL_TTL_SECONDS * 1000}`;
      }
    } else {
      downloadUrl = `${STORAGE_ENDPOINT}/${STORAGE_BUCKET}/${objectKey}?token=${generateNonce(16)}&expires=${Date.now() + SIGNED_URL_TTL_SECONDS * 1000}`;
    }

    return {
      downloadUrl,
      filename: file.filename,
      contentType: file.contentType,
      expiresInSeconds: SIGNED_URL_TTL_SECONDS,
    };
  }
}

export const storageService = new StorageService();
