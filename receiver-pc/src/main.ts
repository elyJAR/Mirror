import { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, clipboard, screen } from 'electron';
import path from 'node:path';
import started from 'electron-squirrel-startup';
import type { NetworkInterfaceInfo } from 'node:os';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const os = require('os');

// Monkey-patch os.networkInterfaces to exclude virtual/VPN adapters and APIPA addresses.
// This is critical because third-party libraries like bonjour-service query all interfaces
// directly to register DNS records, bypassing any local getLocalIpAddress filters.
const originalNetworkInterfaces = os.networkInterfaces;
os.networkInterfaces = () => {
  const interfaces = originalNetworkInterfaces();
  const filtered: NodeJS.Dict<NetworkInterfaceInfo[]> = {};
  for (const name of Object.keys(interfaces)) {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('veth') || 
        lowerName.includes('vethernet') || 
        lowerName.includes('vmware') || 
        lowerName.includes('virtual') ||
        lowerName.includes('virtualbox') ||
        lowerName.includes('vbox') ||
        lowerName.includes('wsl') ||
        lowerName.includes('hyper-v') ||
        lowerName.includes('docker') ||
        lowerName.includes('vpn') ||
        lowerName.includes('zerotier') ||
        lowerName.includes('tailscale') ||
        lowerName.includes('host-only') ||
        lowerName.includes('pseudo') ||
        lowerName.includes('loopback')) {
      continue;
    }
    const iface = interfaces[name];
    if (iface) {
      const filteredIface = iface.filter((net: NetworkInterfaceInfo) => {
        if (net.family === 'IPv4') {
          return !net.address.startsWith('169.254.');
        }
        return true;
      });
      if (filteredIface.length > 0) {
        filtered[name] = filteredIface;
      }
    }
  }
  return filtered;
};

import { Bonjour, ServiceConfig } from 'bonjour-service';
import * as net from 'net';
import * as dgram from 'node:dgram';

function getLocalIpAddress(): string | undefined {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    const iface = interfaces[name];
    if (!iface) continue;
    for (const net of iface) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address;
      }
    }
  }
  return undefined;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function sendToWindow(window: BrowserWindow | null, channel: string, ...args: any[]) {
  if (window && !window.isDestroyed()) {
    try {
      window.webContents.send(channel, ...args);
    } catch (e) {
      console.warn(`Failed to send to window on channel ${channel}:`, e);
    }
  }
}

let udpClient: dgram.Socket | null = null;
let udpTimer: NodeJS.Timeout | null = null;

function startUdpAdvertising() {
  stopUdpAdvertising();
  
  const client = dgram.createSocket('udp4');
  udpClient = client;
  
  client.on('error', (err) => {
    console.warn('[UDP Broadcast] Socket error:', err);
  });

  const sendBroadcast = () => {
    try {
      const ip = getLocalIpAddress();
      if (!ip) return;
      
      const message = JSON.stringify({
        name: advertisedName,
        port: 8765,
        ip: ip
      });
      
      const payload = Buffer.from(message);
      
      // Broadcast to local subnet on port 8768
      client.send(payload, 0, payload.length, 8768, '255.255.255.255', (err) => {
        if (err) {
          console.warn('[UDP Broadcast] Failed to send broadcast:', err);
        }
      });
    } catch (err) {
      console.warn('[UDP Broadcast] Error in advertising loop:', err);
    }
  };

  client.bind(0, () => {
    try {
      client.setBroadcast(true);
      console.log('[UDP Broadcast] Socket bound to random port and broadcast enabled');
      sendBroadcast();
    } catch (err) {
      console.warn('[UDP Broadcast] Failed to enable broadcast on bind:', err);
    }
  });
  
  udpTimer = setInterval(sendBroadcast, 2000);
}

function stopUdpAdvertising() {
  if (udpTimer) {
    clearInterval(udpTimer);
    udpTimer = null;
  }
  if (udpClient) {
    try {
      udpClient.close();
    } catch (e) {
      /* ignore socket close errors */
    }
    udpClient = null;
  }
}

// Handle creating/removing shortcuts on Windows when installing/uninstalling.
if (started) {
  app.quit();
}

const gotSingleInstanceLock = app.requestSingleInstanceLock();

if (!gotSingleInstanceLock) {
  app.quit();
}

let mainWindow: BrowserWindow | null = null;
let projectionWindow: BrowserWindow | null = null;
let lastSessionParams: Record<string, unknown> | null = null;
let currentPeerAddress: string | null = null;
let tray: Tray | null = null;
let isQuitting = false;
let activeSocket: net.Socket | null = null;

let bonjour: Bonjour | null = null;
let tcpServer: net.Server | null = null;
const currentPin = Math.floor(1000 + Math.random() * 9000).toString();
const advertisedName = `Mirror (${os.hostname()})`;
const trustedDevices = new Set<string>();

/**
 * Main application window setup.
 * 
 * Requirements: tasks.md §4.2
 */
const createWindow = () => {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 720,
    minWidth: 640,
    minHeight: 360,
    title: 'Mirror Receiver',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  // Load the renderer app (Vite dev server or static build)
  if (MAIN_WINDOW_VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(MAIN_WINDOW_VITE_DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/index.html`));
  }

  // Handle window close (hide instead of quit)
  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow?.hide();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  mainWindow.webContents.on('did-finish-load', () => {
    const ip = getLocalIpAddress();
    sendToWindow(mainWindow, 'local-ip', {
      ip: ip ? `${ip}:8765` : 'Unknown IP',
      deviceName: advertisedName,
      execPath: process.execPath,
      isPackaged: app.isPackaged
    });
  });

  startNetworkServices(mainWindow);
  createTray();
};

function createTray() {
  const refreshTray = () => {
    const ip = getLocalIpAddress() || 'Unknown IP';
    const iconPath = app.isPackaged 
      ? path.join(process.resourcesPath, 'assets', 'tray-icon.png')
      : path.join(__dirname, '..', 'src', 'assets', 'tray-icon.png');
    
    const icon = nativeImage.createFromPath(iconPath);
    const trayIcon = icon.resize({ width: 16, height: 16 });

    if (!tray) {
      tray = new Tray(trayIcon);
    } else {
      tray.setImage(trayIcon);
    }

    const contextMenu = Menu.buildFromTemplate([
      { label: `Mirror Receiver Active`, enabled: false },
      { label: `Local IP: ${ip}`, enabled: false },
      { label: 'Copy IP Address', click: () => {
        if (ip !== 'Unknown IP') {
          clipboard.writeText(ip);
        }
      }},
      { type: 'separator' },
      { label: 'Show Receiver', click: () => {
        mainWindow?.show();
        mainWindow?.focus();
      }},
      { label: 'Quit Mirror', click: () => {
        isQuitting = true;
        app.quit();
      }}
    ]);
    
    tray.setToolTip(`Mirror Receiver\nIP: ${ip}\nStatus: Waiting for connection`);
    tray.setContextMenu(contextMenu);
  };

  refreshTray();
  
  // Periodically refresh IP in case of network changes
  setInterval(refreshTray, 10000);
  
  tray?.on('double-click', () => {
    mainWindow?.show();
  });
}

app.on('second-instance', () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) {
      mainWindow.restore();
    }
    mainWindow.focus();
  }
});

/**
 * Starts the mDNS advertiser and TCP server for the Mirror protocol.
 */
function startNetworkServices(window: BrowserWindow) {
  if (tcpServer) return;

  const toggleProjection = async () => {
    console.log('toggleProjection triggered. Current window state:', !!projectionWindow);
    if (projectionWindow) {
      console.log('Closing existing projection window');
      projectionWindow.close();
      projectionWindow = null;
      sendToWindow(mainWindow, 'projection-state', false);
      if (activeSocket && !activeSocket.destroyed) {
        sendControl(activeSocket, { type: 'projection_state', active: false });
      }
      return false;
    }

    const displays = screen.getAllDisplays();
    console.log(`Found ${displays.length} displays`);
    if (displays.length < 2) {
      console.log('No secondary monitor found (displays.length < 2)');
      return false;
    }

    try {
      const primaryDisplay = screen.getPrimaryDisplay();
      const extendedDisplay = displays.find((d: Electron.Display) => d.id !== primaryDisplay.id) || displays[1];

      console.log('Opening projection window on display:', extendedDisplay.id, 'bounds:', extendedDisplay.bounds);

      projectionWindow = new BrowserWindow({
        x: extendedDisplay.bounds.x,
        y: extendedDisplay.bounds.y,
        width: extendedDisplay.bounds.width,
        height: extendedDisplay.bounds.height,
        fullscreen: true,
        autoHideMenuBar: true,
        title: 'Mirror Projection',
        webPreferences: {
          preload: path.join(__dirname, 'preload.js'),
        },
      });

      const url = MAIN_WINDOW_VITE_DEV_SERVER_URL
        ? `${MAIN_WINDOW_VITE_DEV_SERVER_URL}?mode=projection`
        : `file://${path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/index.html`)}?mode=projection`;

      projectionWindow.loadURL(url);

      projectionWindow.webContents.on('did-finish-load', () => {
        if (lastSessionParams) {
          sendToWindow(projectionWindow, 'control-message', {
            type: 'hello-ack',
            params: lastSessionParams,
            receiver: 'Mirror PC (Projection)'
          });
        }
        if (currentPeerAddress) {
          sendToWindow(projectionWindow, 'peer-connected', { address: currentPeerAddress });
        }
        if (activeSocket && !activeSocket.destroyed) {
          console.log('Requesting keyframe for new projection window...');
          sendControl(activeSocket, { type: 'request-keyframe' });
        }
      });

      projectionWindow.on('closed', () => {
        projectionWindow = null;
        sendToWindow(mainWindow, 'projection-state', false);
      });

      const isProjecting = !!projectionWindow;
      sendToWindow(mainWindow, 'projection-state', isProjecting);
      if (activeSocket && !activeSocket.destroyed) {
        sendControl(activeSocket, { type: 'projection_state', active: isProjecting });
      }
      return isProjecting;
    } catch (e) {
      console.error('Failed to toggle projection:', e);
      return false;
    }
  };

  ipcMain.handle('project-to-extended', toggleProjection);

  ipcMain.handle('send-sync-state', async (_event, state) => {
    if (projectionWindow && !projectionWindow.isDestroyed()) {
      projectionWindow.webContents.send('sync-state', state);
    }
  });

  tcpServer = net.createServer((socket) => {
    socket.setNoDelay(true);
    socket.setKeepAlive(true);
    activeSocket = socket;
    const remoteAddress = socket.remoteAddress;
    console.log('Phone connected:', remoteAddress);
    
    // Notify renderer of connection and current PIN
    sendToWindow(mainWindow, 'peer-connected', { address: remoteAddress });
    sendToWindow(mainWindow, 'pairing-pin', currentPin);
    
    let buffer = Buffer.alloc(0);
    let pinAttempts = 0;
    const MAX_PIN_ATTEMPTS = 3;

    // Remove any existing handler to avoid collision
    ipcMain.removeHandler('send-control');
    
    // Handle outgoing control messages from renderer
    ipcMain.handle('send-control', async (_event, msg) => {
      if (!socket.destroyed) {
        sendControl(socket, msg);
      }
    });
    
    socket.on('data', (data) => {
      // Accumulate data into buffer
      buffer = Buffer.concat([buffer, data]);
      
      // Parse length-prefixed frames: [Tag: 1b][Length: 4b][Payload: Nb]
      while (buffer.length >= 5) {
        const tag = buffer.readUInt8(0);
        const length = buffer.readInt32BE(1);
        
        // Safety check for length
        if (length < 0 || length > 1024 * 1024 * 8) { // 8MB limit
            console.error('Invalid frame length:', length);
            socket.destroy();
            return;
        }

        if (buffer.length < 5 + length) break;
        
        const payload = buffer.subarray(5, 5 + length);
        buffer = buffer.slice(5 + length);
        
        const shouldContinue = handleFrame(tag, payload, socket, mainWindow || window, toggleProjection, () => {
          pinAttempts++;
          return pinAttempts >= MAX_PIN_ATTEMPTS;
        });

        if (!shouldContinue) {
          console.warn('Too many PIN attempts, disconnecting');
          socket.destroy();
          return;
        }
      }
    });

    socket.on('close', () => {
      console.log('Phone disconnected');
      sendToWindow(mainWindow, 'peer-disconnected');
      ipcMain.removeHandler('send-control'); // Cleanup handler
    });

    socket.on('error', (err) => {
      console.error('Socket error:', err);
    });

  });

  tcpServer.on('error', (error: NodeJS.ErrnoException) => {
    if (error.code === 'EADDRINUSE') {
      console.error('TCP port 8765 is already in use. Another receiver instance is probably running.');
    } else {
      console.error('TCP server error:', error);
    }

    sendToWindow(mainWindow, 'peer-disconnected');
  });

  // Listen on all interfaces (IPv4 and IPv6)
  tcpServer.listen(8765, () => {
    console.log('TCP Server listening on port 8765 (all interfaces)');
    const ip = getLocalIpAddress();
    console.log(`[Network Setup] Detected physical local IP: ${ip || 'Unknown IP'}`);
    console.log(`[mDNS Bonjour] Advertising on interface IP: ${ip || 'Default (All Interfaces)'}`);
  });

  // Advertise service via mDNS
  try {
    const ip = getLocalIpAddress();
    bonjour = new Bonjour(ip ? { interface: ip } as unknown as Partial<ServiceConfig> : undefined);
    bonjour.publish({ 
      name: advertisedName,
      type: 'mirror', 
      protocol: 'tcp', 
      port: 8765,
      host: ip
    });
  } catch (error) {
    console.warn('mDNS advertise failed; continuing with TCP server only:', error);
  }

  // Start UDP broadcast advertising for hotspots/fallback discovery
  try {
    startUdpAdvertising();
    console.log('[UDP Broadcast] Started subnet advertising on port 8768');
  } catch (error) {
    console.warn('[UDP Broadcast] Failed to start UDP advertising:', error);
  }
}

function handleFrame(tag: number, payload: Buffer, socket: net.Socket, window: BrowserWindow, onToggleProjection: () => void, onPinFailure: () => boolean): boolean {
  if (tag !== 0x02 && tag !== 0x03) {
    console.log(`[TCP] Received frame tag: 0x${tag.toString(16)}, size: ${payload.length}`);
  }
  if (tag === 0x01) { // Control Message (JSON)
    try {
      const raw = payload.toString().trim();
      const msg = JSON.parse(raw);
      
      const inferredType =
        typeof msg.type === 'string'
          ? msg.type
          : (typeof msg.device === 'string' && Array.isArray(msg.codecs) ? 'hello' : undefined);
      
      sendToWindow(mainWindow, 'control-message', msg);
      sendToWindow(projectionWindow, 'control-message', msg);
      
      // Automatic handshake response
      if (inferredType === 'hello') {
        const supportedCodecs = msg.codecs || ['video/avc'];
        const chosenCodec = supportedCodecs.includes('video/hevc') ? 'video/hevc' : 'video/avc';
        const deviceIp = socket.remoteAddress || 'unknown';
        const isTrusted = trustedDevices.has(deviceIp);

        lastSessionParams = msg.params;
        currentPeerAddress = socket.remoteAddress || 'unknown';

        sendControl(socket, {
          type: 'hello-ack',
          receiver: 'Mirror PC',
          params: { width: 1280, height: 720, fps: 30, codec: chosenCodec },
          pinRequired: !isTrusted
        });
        
        lastSessionParams = { width: 1280, height: 720, fps: 30, codec: chosenCodec };
        
        if (isTrusted) {
          sendToWindow(mainWindow, 'pairing-success');
          sendToWindow(projectionWindow, 'pairing-success');
        }
      } else if (inferredType === 'verify-pin') {
        const isMatch = msg.pin === currentPin;
        sendControl(socket, {
          type: 'auth-result',
          success: isMatch,
          message: isMatch ? 'Pairing successful' : 'Incorrect PIN. Please try again.'
        });
        
        if (isMatch) {
          trustedDevices.add(socket.remoteAddress || 'unknown');
          sendToWindow(mainWindow, 'pairing-success');
          sendToWindow(projectionWindow, 'pairing-success');
        } else {
          if (onPinFailure()) {
            return false; // Signal to disconnect
          }
        }
      } else if (inferredType === 'extend_display') {
        // Trigger projection logic (from phone)
        console.log('Remote extend_display received');
        onToggleProjection(); 
      } else if (inferredType === 'ping') {
        sendControl(socket, {
          type: 'pong',
          timestamp: msg.timestamp
        });
      }
    } catch (e) {
      console.error('Failed to parse control message:', e);
    }
  } else if (tag === 0x02) { // Video NAL Unit (with 8-byte timestamp)
    if (payload.length >= 8) {
      const ts = payload.readBigInt64BE(0);
      const data = payload.subarray(8);
      sendToWindow(mainWindow, 'video-frame', data, Number(ts));
      sendToWindow(projectionWindow, 'video-frame', data, Number(ts));
    }
  } else if (tag === 0x03) { // Audio Data (with 8-byte timestamp)
    if (payload.length >= 8) {
      const ts = payload.readBigInt64BE(0);
      const data = payload.subarray(8);
      sendToWindow(mainWindow, 'audio-frame', data, Number(ts));
      sendToWindow(projectionWindow, 'audio-frame', data, Number(ts));
    }
  }
  return true;
}

function sendControl(socket: net.Socket, msg: Record<string, unknown>) {
  const json = JSON.stringify(msg);
  const payload = Buffer.from(json);
  const header = Buffer.alloc(5);
  header.writeUInt8(0x01, 0);
  header.writeInt32BE(payload.length, 1);
  const frame = Buffer.concat([header, payload]);
  console.log('Sending control message raw:', json);
  console.log('Sending control message bytes:', frame.length, 'socketWritable:', socket.writable);
  socket.write(frame, (err) => {
    if (err) {
      console.error('Failed to write control frame:', err);
    }
  });
}

app.on('ready', createWindow);

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    // We stay alive in the tray
  }
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});

app.on('before-quit', () => {
  isQuitting = true;
  
  if (activeSocket) {
    try {
      activeSocket.destroy();
      console.log('[Shutdown] Closed active TCP socket connection');
    } catch (e) {
      console.warn('[Shutdown] Failed to destroy active socket:', e);
    }
    activeSocket = null;
  }
  
  if (tcpServer) {
    try {
      tcpServer.close();
      console.log('[Shutdown] Closed TCP server');
    } catch (e) {
      console.warn('[Shutdown] Failed to close TCP server:', e);
    }
    tcpServer = null;
  }
});

app.on('will-quit', () => {
  stopUdpAdvertising();
  try {
    bonjour.destroy();
  } catch (e) {
    /* ignore bonjour destroy errors */
  }
});

