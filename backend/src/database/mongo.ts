import mongoose, { Schema, Document, Model } from 'mongoose';
import dns from 'dns';
import dotenv from 'dotenv';
import { logger } from '../logger';

dotenv.config();

// Ensure reliable SRV DNS resolution on Windows networks only
if (process.platform === 'win32') {
  try {
    dns.setServers(['8.8.8.8', '1.1.1.1', '8.8.4.4']);
  } catch (_) {}
}

const MONGODB_URI = process.env.MONGODB_URI || 'mongodb+srv://masudurrahamanrm_db_user:BEPvG0PaFOrB8Tht@cluster0.3xu8xlz.mongodb.net/kinetix_sentry?retryWrites=true&w=majority';

let isConnected = false;
let retryTimer: NodeJS.Timeout | null = null;

export async function connectMongo(): Promise<boolean> {
  if (isConnected && mongoose.connection.readyState === 1) return true;

  if (!MONGODB_URI) {
    logger.info('MONGODB_URI environment variable not set. Running with memory/fallback store.');
    return false;
  }

  try {
    await mongoose.connect(MONGODB_URI, {
      serverSelectionTimeoutMS: 5000,
      connectTimeoutMS: 5000,
      family: 4, // Force IPv4 for reliable cloud resolution
    });

    isConnected = true;
    logger.info('MongoDB connected successfully');
    if (retryTimer) {
      clearInterval(retryTimer);
      retryTimer = null;
    }
    return true;
  } catch (err: any) {
    isConnected = false;
    logger.warn({ err: err?.message || err }, 'MongoDB connection skipped/failed. Operating in resilient hybrid mode.');
    
    // Background retry every 30 seconds once IP is whitelisted
    if (!retryTimer) {
      retryTimer = setInterval(async () => {
        if (!isMongoConnected()) {
          try {
            await mongoose.connect(MONGODB_URI, {
              serverSelectionTimeoutMS: 5000,
              connectTimeoutMS: 5000,
              family: 4,
            });
            isConnected = true;
            logger.info('MongoDB connected successfully on retry');
            if (retryTimer) {
              clearInterval(retryTimer);
              retryTimer = null;
            }
          } catch (_) {}
        }
      }, 30000);
    }
    return false;
  }
}

export function isMongoConnected(): boolean {
  return isConnected && mongoose.connection.readyState === 1;
}

// -------------------------------------------------------------
// SCHEMAS & MODELS
// -------------------------------------------------------------

// 1. Device Document
export interface IDevice extends Document {
  deviceId: string;
  deviceName: string;
  platform: string;
  osVersion: string;
  appVersion: string;
  publicKey: string;
  capabilities: {
    camera: boolean;
    location: boolean;
    notifications: boolean;
    files: boolean;
    microphone: boolean;
    battery: boolean;
  };
  status: 'ONLINE' | 'OFFLINE' | 'UNPAIRED';
  lastSeenAt: Date;
  createdAt: Date;
  updatedAt: Date;
}

const DeviceSchema = new Schema<IDevice>(
  {
    deviceId: { type: String, required: true, unique: true, index: true },
    deviceName: { type: String, required: true },
    platform: { type: String, default: 'Android' },
    osVersion: { type: String, default: 'Android 14' },
    appVersion: { type: String, default: '1.0.0' },
    publicKey: { type: String, default: '' },
    capabilities: {
      camera: { type: Boolean, default: true },
      location: { type: Boolean, default: true },
      notifications: { type: Boolean, default: true },
      files: { type: Boolean, default: true },
      microphone: { type: Boolean, default: true },
      battery: { type: Boolean, default: true },
    },
    status: { type: String, enum: ['ONLINE', 'OFFLINE', 'UNPAIRED'], default: 'ONLINE' },
    lastSeenAt: { type: Date, default: Date.now },
  },
  { timestamps: true }
);

export const DeviceModel: Model<IDevice> =
  mongoose.models.Device || mongoose.model<IDevice>('Device', DeviceSchema);

// 2. GPS Location Document
export interface ILocation extends Document {
  deviceId: string;
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude: number;
  speed: number;
  address: string;
  timestamp: Date;
}

const LocationSchema = new Schema<ILocation>(
  {
    deviceId: { type: String, required: true, index: true },
    latitude: { type: Number, required: true },
    longitude: { type: Number, required: true },
    accuracy: { type: Number, default: 3.0 },
    altitude: { type: Number, default: 14.0 },
    speed: { type: Number, default: 0.0 },
    address: { type: String, default: 'Live GPS Location' },
    timestamp: { type: Date, default: Date.now, index: true },
  },
  { timestamps: true }
);

export const LocationModel: Model<ILocation> =
  mongoose.models.Location || mongoose.model<ILocation>('Location', LocationSchema);

// 3. Telemetry & Battery Document
export interface ITelemetry extends Document {
  deviceId: string;
  deviceName?: string;
  percentage: number;
  level: number;
  isCharging: boolean;
  chargingStatus: string;
  temperature: string | number;
  voltage: string | number;
  health: string;
  technology: string;
  powerSave: string | boolean;
  networkType: string;
  networkStatus: string;
  uptime: string;
  wallpaper?: string;
  hardware?: any;
  timestamp: Date | number;
}

const TelemetrySchema = new Schema<ITelemetry>(
  {
    deviceId: { type: String, required: true, index: true },
    deviceName: { type: String },
    percentage: { type: Number, default: 100 },
    level: { type: Number, default: 100 },
    isCharging: { type: Boolean, default: false },
    chargingStatus: { type: String, default: 'Good' },
    temperature: { type: Schema.Types.Mixed, default: '32.0 °C' },
    voltage: { type: Schema.Types.Mixed, default: '4,100 mV' },
    health: { type: String, default: 'Optimal' },
    technology: { type: String, default: 'Li-poly' },
    powerSave: { type: Schema.Types.Mixed, default: 'Disabled' },
    networkType: { type: String, default: '5G' },
    networkStatus: { type: String, default: 'Connected' },
    uptime: { type: String, default: '2h 15m' },
    wallpaper: { type: String, default: '' },
    hardware: { type: Schema.Types.Mixed, default: {} },
    timestamp: { type: Schema.Types.Mixed, default: Date.now, index: true },
  },
  { timestamps: true }
);

export const TelemetryModel: Model<ITelemetry> =
  mongoose.models.Telemetry || mongoose.model<ITelemetry>('Telemetry', TelemetrySchema);

// 4. Call Log Document
export interface ICallLog extends Document {
  id: string;
  deviceId: string;
  name?: string;
  number: string;
  type: string;
  date: string;
  duration: string;
  timestamp: number;
  createdAt: Date;
}

const CallLogSchema = new Schema<ICallLog>(
  {
    id: { type: String, required: true },
    deviceId: { type: String, required: true, index: true },
    name: { type: String },
    number: { type: String, required: true },
    type: { type: String, required: true },
    date: { type: String, required: true },
    duration: { type: String, required: true },
    timestamp: { type: Schema.Types.Mixed, default: Date.now },
  },
  { timestamps: true }
);

export const CallLogModel: Model<ICallLog> =
  mongoose.models.CallLog || mongoose.model<ICallLog>('CallLog', CallLogSchema);

// 5. Gallery Media Document
export interface IGalleryMedia extends Document {
  id: string;
  deviceId: string;
  name: string;
  album: string;
  mimeType: string;
  size: string;
  date: string;
  timestamp: number;
  width: number;
  height: number;
  thumbnail?: string;
  fullBase64?: string;
  r2Url?: string;
  createdAt: Date;
}

const GalleryMediaSchema = new Schema<IGalleryMedia>(
  {
    id: { type: String, required: true },
    deviceId: { type: String, required: true, index: true },
    name: { type: String, required: true },
    album: { type: String, default: 'Camera' },
    mimeType: { type: String, default: 'image/jpeg' },
    size: { type: String, default: '3.5 MB' },
    date: { type: String, default: 'Just now' },
    timestamp: { type: Schema.Types.Mixed, default: Date.now },
    width: { type: Number, default: 1080 },
    height: { type: Number, default: 1920 },
    thumbnail: { type: String },
    fullBase64: { type: String },
    r2Url: { type: String },
  },
  { timestamps: true }
);

export const GalleryMediaModel: Model<IGalleryMedia> =
  mongoose.models.GalleryMedia || mongoose.model<IGalleryMedia>('GalleryMedia', GalleryMediaSchema);

// 4. Photo & Snapshot Document
export interface IPhoto extends Document {
  id: string;
  deviceId: string;
  name: string;
  date: string;
  size: string;
  r2Key?: string;
  r2Url?: string;
  base64?: string;
  createdAt: Date;
}

const PhotoSchema = new Schema<IPhoto>(
  {
    id: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true, index: true },
    name: { type: String, required: true },
    date: { type: String, default: 'Just now' },
    size: { type: String, default: '4.8 MB' },
    r2Key: { type: String },
    r2Url: { type: String },
    base64: { type: String },
  },
  { timestamps: true }
);

export const PhotoModel: Model<IPhoto> =
  mongoose.models.Photo || mongoose.model<IPhoto>('Photo', PhotoSchema);

// 5. Audio Voice Memo Document
export interface IAudio extends Document {
  id: string;
  deviceId: string;
  name: string;
  duration: string;
  size: string;
  date: string;
  r2Key?: string;
  r2Url?: string;
  base64?: string;
  createdAt: Date;
}

const AudioSchema = new Schema<IAudio>(
  {
    id: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true, index: true },
    name: { type: String, required: true },
    duration: { type: String, default: '0:10' },
    size: { type: String, default: '160 KB' },
    date: { type: String, default: 'Just now' },
    r2Key: { type: String },
    r2Url: { type: String },
    base64: { type: String },
  },
  { timestamps: true }
);

export const AudioModel: Model<IAudio> =
  mongoose.models.Audio || mongoose.model<IAudio>('Audio', AudioSchema);

// 6. File Document
export interface IFile extends Document {
  fileId: string;
  pairingId: string;
  uploaderDeviceId: string;
  filename: string;
  fileSize: number;
  contentType: string;
  r2Key: string;
  r2Url?: string;
  createdAt: Date;
}

const FileSchema = new Schema<IFile>(
  {
    fileId: { type: String, required: true, unique: true, index: true },
    pairingId: { type: String, required: true, index: true },
    uploaderDeviceId: { type: String, required: true },
    filename: { type: String, required: true },
    fileSize: { type: Number, required: true },
    contentType: { type: String, default: 'application/octet-stream' },
    r2Key: { type: String, required: true },
    r2Url: { type: String },
  },
  { timestamps: true }
);

export const FileModel: Model<IFile> =
  mongoose.models.File || mongoose.model<IFile>('File', FileSchema);

// 7. Notification Document
export interface INotification extends Document {
  id: string;
  deviceId: string;
  packageName: string;
  title: string;
  body: string;
  image?: string;
  r2ImageUrl?: string;
  timestamp: number;
  createdAt: Date;
}

const NotificationSchema = new Schema<INotification>(
  {
    id: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true, index: true },
    packageName: { type: String, required: true },
    title: { type: String, default: '' },
    body: { type: String, default: '' },
    image: { type: String },
    r2ImageUrl: { type: String },
    timestamp: { type: Number, required: true, index: true },
  },
  { timestamps: true }
);

export const NotificationModel: Model<INotification> =
  mongoose.models.Notification || mongoose.model<INotification>('Notification', NotificationSchema);
