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
    this.accountId = process.env.R2_ACCOUNT_ID || '89b8f307c04b78bda5d75257e88c949b';
    const accessKeyId = process.env.R2_ACCESS_KEY_ID || '953214bccbdfd86f88d9bc47b97d18e8';
    const secretAccessKey = process.env.R2_SECRET_ACCESS_KEY || '3966523f29b8fe2963b64543bb987e7bb177b82e636c97d450e64dd4ebb3f575';
    this.bucket = process.env.R2_BUCKET_NAME || 'sentry';
    this.publicDomain = process.env.R2_PUBLIC_DOMAIN || 'https://89b8f307c04b78bda5d75257e88c949b.r2.cloudflarestorage.com/sentry';

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
        logger.info('Cloudflare R2 storage client initialized successfully for bucket: ' + this.bucket);
      } catch (err) {
        logger.warn({ err }, 'Failed to initialize Cloudflare R2 client');
      }
    } else {
      logger.info('Cloudflare R2 credentials not set.');
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
   * Streams an object directly from Cloudflare R2.
   */
  async getObjectStream(key: string): Promise<{ stream: any; contentType: string; contentLength?: number }> {
    if (!this.client) {
      throw new Error('Cloudflare R2 is not configured');
    }

    const command = new GetObjectCommand({
      Bucket: this.bucket,
      Key: key,
    });

    const res = await this.client.send(command);
    return {
      stream: res.Body,
      contentType: res.ContentType || 'image/jpeg',
      contentLength: res.ContentLength,
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
      logger.warn({ err, key }, 'Failed to delete object from Cloudflare R2');
      return false;
    }
  }
}

export const r2Service = new CloudflareR2Service();
