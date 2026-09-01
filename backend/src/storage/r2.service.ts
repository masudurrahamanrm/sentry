import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import dotenv from 'dotenv';
import { logger } from '../logger';

dotenv.config();

export class CloudflareR2Service {
  private client: S3Client | null = null;
  private bucket: string;
  private accountId: string;
  private publicDomain: string;

  constructor() {
    this.accountId = process.env.R2_ACCOUNT_ID || '';
    const accessKeyId = process.env.R2_ACCESS_KEY_ID || '';
    const secretAccessKey = process.env.R2_SECRET_ACCESS_KEY || '';
    this.bucket = process.env.R2_BUCKET_NAME || 'sentry-media';
    this.publicDomain = process.env.R2_PUBLIC_DOMAIN || '';

    if (this.accountId && accessKeyId && secretAccessKey) {
      try {
        this.client = new S3Client({
          region: 'auto',
          endpoint: `https://${this.accountId}.r2.cloudflarestorage.com`,
          credentials: {
            accessKeyId,
            secretAccessKey,
          },
        });
        logger.info('Cloudflare R2 storage client initialized');
      } catch (err) {
        logger.warn({ err }, 'Failed to initialize Cloudflare R2 client');
      }
    } else {
      logger.info('Cloudflare R2 credentials not fully set. Running with local fallback.');
    }
  }

  isConfigured(): boolean {
    return this.client !== null;
  }

  getBucketName(): string {
    return this.bucket;
  }

  getPublicUrl(key: string): string {
    if (this.publicDomain) {
      const domain = this.publicDomain.endsWith('/') ? this.publicDomain.slice(0, -1) : this.publicDomain;
      return `${domain}/${key}`;
    }
    return `https://${this.bucket}.${this.accountId}.r2.cloudflarestorage.com/${key}`;
  }

  /**
   * Uploads raw buffer or base64 data directly to Cloudflare R2.
   */
  async uploadBuffer(
    key: string,
    buffer: Buffer,
    contentType: string = 'application/octet-stream'
  ): Promise<{ key: string; url: string; size: number }> {
    if (!this.client) {
      throw new Error('Cloudflare R2 is not configured');
    }

    const command = new PutObjectCommand({
      Bucket: this.bucket,
      Key: key,
      Body: buffer,
      ContentType: contentType,
    });

    await this.client.send(command);

    let url = this.getPublicUrl(key);
    try {
      // Generate 7-day presigned URL for direct secure streaming
      const download = await this.generatePresignedDownloadUrl(key, 86400 * 7);
      url = download.downloadUrl;
    } catch (_) {}

    return {
      key,
      url,
      size: buffer.length,
    };
  }

  /**
   * Generates a presigned URL for direct client-to-R2 upload.
   */
  async generatePresignedUploadUrl(
    key: string,
    contentType: string = 'application/octet-stream',
    expiresInSeconds: number = 900
  ): Promise<{ uploadUrl: string; key: string; publicUrl: string; expiresInSeconds: number }> {
    if (!this.client) {
      throw new Error('Cloudflare R2 is not configured');
    }

    const command = new PutObjectCommand({
      Bucket: this.bucket,
      Key: key,
      ContentType: contentType,
    });

    const uploadUrl = await getSignedUrl(this.client, command, {
      expiresIn: expiresInSeconds,
    });

    return {
      uploadUrl,
      key,
      publicUrl: this.getPublicUrl(key),
      expiresInSeconds,
    };
  }

  /**
   * Generates a presigned URL for secure download from R2.
   */
  async generatePresignedDownloadUrl(
    key: string,
    expiresInSeconds: number = 900
  ): Promise<{ downloadUrl: string; expiresInSeconds: number }> {
    if (!this.client) {
      throw new Error('Cloudflare R2 is not configured');
    }

    const command = new GetObjectCommand({
      Bucket: this.bucket,
      Key: key,
    });

    const downloadUrl = await getSignedUrl(this.client, command, {
      expiresIn: expiresInSeconds,
    });

    return {
      downloadUrl,
      expiresInSeconds,
    };
  }

  /**
   * Deletes an object from Cloudflare R2.
   */
  async deleteObject(key: string): Promise<boolean> {
    if (!this.client) return false;

    try {
      const command = new DeleteObjectCommand({
        Bucket: this.bucket,
        Key: key,
      });
      await this.client.send(command);
      return true;
    } catch (err) {
      logger.error({ err, key }, 'Failed to delete object from Cloudflare R2');
      return false;
    }
  }
}

export const r2Service = new CloudflareR2Service();
