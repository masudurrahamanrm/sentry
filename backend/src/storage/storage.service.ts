import { query } from '../database/db';
import { AppError } from '../middleware/errorHandler';
import { generateNonce } from '@kinetix-sentry/crypto';

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
  ): Promise<{ fileId: string; uploadUrl: string; expiresInSeconds: number }> {
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

    // Generate signed upload endpoint
    const uploadUrl = `${STORAGE_ENDPOINT}/${STORAGE_BUCKET}/${objectKey}?token=${generateNonce(16)}&expires=${Date.now() + SIGNED_URL_TTL_SECONDS * 1000}`;

    fileMetadataMap.set(fileId, {
      fileId,
      pairingId,
      uploaderDeviceId,
      filename,
      fileSize,
      contentType,
      createdAt: Date.now(),
    });

    return {
      fileId,
      uploadUrl,
      expiresInSeconds: SIGNED_URL_TTL_SECONDS,
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

    // 2. Lookup file metadata
    const file = fileMetadataMap.get(fileId);
    if (!file || file.pairingId !== pairingId) {
      throw new AppError('FILE_NOT_FOUND', 'The requested file was not found or belongs to a different pairing.', 404);
    }

    const objectKey = `${pairingId}/${fileId}/${encodeURIComponent(file.filename)}`;
    const downloadUrl = `${STORAGE_ENDPOINT}/${STORAGE_BUCKET}/${objectKey}?token=${generateNonce(16)}&expires=${Date.now() + SIGNED_URL_TTL_SECONDS * 1000}`;

    return {
      downloadUrl,
      filename: file.filename,
      contentType: file.contentType,
      expiresInSeconds: SIGNED_URL_TTL_SECONDS,
    };
  }
}

export const storageService = new StorageService();
