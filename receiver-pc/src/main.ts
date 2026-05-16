import { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, clipboard, screen } from 'electron';
import path from 'node:path';
import started from 'electron-squirrel-startup';
import { Bonjour } from 'bonjour-service';
import * as net from 'net';
import * as os from 'node:os';

function getLocalIpAddress(): string | undefined {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    // Skip virtual adapters commonly found on Windows/Linux
    if (name.toLowerCase().includes('veth') || 
        name.toLowerCase().includes('vmware') || 
        name.toLowerCase().includes('virtual') ||
        name.toLowerCase().includes('wsl')) {
      continue;
    }
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
let tray: Tray | null = null;
let isQuitting = false;

const bonjour = new Bonjour();
let tcpServer: net.Server | null = null;
let activeSocket: net.Socket | null = null;
let currentPin = '';
const advertisedName = `Mirror (${os.hostname()})`;
const trustedDevices = new Set<string>();

function broadcastSyncState() {
  broadcastToWindows('sync-state', {
    currentPin,
    currentPeer,
    isPaired,
    lastHelloMsg
  });
}
let lastConfigFrame: Buffer | null = null;
let lastHelloMsg: any = null;
let currentPeer: string | null = null;
let isPaired = false;
let debugFrameCount = 0;

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

  mainWindow.setMenuBarVisibility(false);

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

  mainWindow.webContents.on('did-finish-load', () => {
    const ip = getLocalIpAddress();
    if (ip) {
      mainWindow?.webContents.send('local-ip', `${ip}:8765`);
    }
    if (currentPeer) {
      mainWindow?.webContents.send('peer-connected', { address: currentPeer });
    }
    if (lastHelloMsg) {
      mainWindow?.webContents.send('control-message', lastHelloMsg);
    }
    if (currentPin) {
      mainWindow?.webContents.send('pairing-pin', currentPin);
    }
    if (isPaired) {
      mainWindow?.webContents.send('pairing-success');
    }
    if (lastConfigFrame) {
      mainWindow?.webContents.send('video-frame', lastConfigFrame);
    }
  });

  startNetworkServices(mainWindow);
  setupIpc();
  createTray();
};

function refreshTray() {
  const ip = getLocalIpAddress() || 'Unknown IP';
  
  const possiblePaths = [
    path.join(app.getAppPath(), 'src', 'assets', 'tray-icon.png'),
    path.join(process.resourcesPath, 'src', 'assets', 'tray-icon.png'),
    path.join(process.resourcesPath, 'assets', 'tray-icon.png'),
    path.join(__dirname, '..', 'src', 'assets', 'tray-icon.png')
  ];

  let icon = nativeImage.createEmpty();
  for (const p of possiblePaths) {
    const img = nativeImage.createFromPath(p);
    if (!img.isEmpty()) {
      icon = img;
      break;
    }
  }
  
  if (icon.isEmpty()) {
    icon = nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAACXBIWXMAAAsTAAALEwEAmpwYAAAAIGNIUk0AAHolAACAgwAA+f8AAIDpAAB1MAAA6mAAADqYAAAXcF5yXzUAAABWSURBVBgZ7c6xCQAgDMTAs99fT8AhfIuC/+78EFEuX993LidIAYJFAAAAABJRU5ErkJggg==');
  }
  
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
    { label: currentPin ? `Pairing PIN: ${currentPin}` : 'No active pairing', enabled: !!currentPin, click: () => {
      if (currentPin) clipboard.writeText(currentPin);
    }},
    { type: 'separator' },
    { label: 'Quit Mirror', click: () => {
      isQuitting = true;
      app.quit();
    }}
  ]);
  
  const status = currentPin ? `Waiting for PIN (${currentPin})` : 'Waiting for connection';
  tray.setToolTip(`Mirror Receiver\nIP: ${ip}\nStatus: ${status}`);
  tray.setContextMenu(contextMenu);
}

function createTray() {
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

  tcpServer = net.createServer((socket) => {
    const remoteAddress = socket.remoteAddress;
    console.log('Phone connected:', remoteAddress);
    activeSocket = socket;
    
    // Generate fresh PIN and notify all windows
    currentPin = Math.floor(1000 + Math.random() * 9000).toString();
    currentPeer = remoteAddress || 'unknown';
    isPaired = false;
    lastConfigFrame = null;
    lastHelloMsg = null;
    console.log('New connection from', remoteAddress, 'PIN:', currentPin);
    
    refreshTray();
    broadcastSyncState();
    
    let buffer = Buffer.alloc(0);
    let pinAttempts = 0;
    const MAX_PIN_ATTEMPTS = 3;

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
        
        const shouldContinue = handleFrame(tag, payload, socket, window, () => {
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
      if (activeSocket === socket) activeSocket = null;
      currentPin = '';
      currentPeer = null;
      isPaired = false;
      debugFrameCount = 0;
      lastConfigFrame = null;
      lastHelloMsg = null;
      refreshTray();
      broadcastSyncState();
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

    if (mainWindow) {
      broadcastToWindows('peer-disconnected');
    }
  });

  // Listen on all interfaces (IPv4 and IPv6)
  tcpServer.listen(8765, () => {
    console.log('TCP Server listening on port 8765 (all interfaces)');
  });

  // Keep-alive heartbeat to prevent phone from resetting to Scanning state
  setInterval(() => {
    if (activeSocket && !activeSocket.destroyed && isPaired) {
      sendControl(activeSocket, { type: 'ping', timestamp: Date.now() });
    }
  }, 5000);

  // Advertise service via mDNS
  try {
    bonjour.publish({ 
      name: advertisedName,
      type: 'mirror', 
      protocol: 'tcp', 
      port: 8765,
      host: getLocalIpAddress()
    });
  } catch (error) {
    console.warn('mDNS advertise failed; continuing with TCP server only:', error);
  }
}

function handleFrame(tag: number, payload: Buffer, socket: net.Socket, window: BrowserWindow, onPinFailure: () => boolean): boolean {
  if (tag === 0x01) { // Control Message (JSON)
    try {
      const raw = payload.toString();
      const msg = JSON.parse(raw);
      // ... (inferredType logic remains the same)
      const inferredType =
        typeof msg.type === 'string'
          ? msg.type
          : (typeof msg.device === 'string' && Array.isArray(msg.codecs) ? 'hello' : undefined);
      
      lastHelloMsg = msg;
      broadcastToWindows('control-message', msg);
      
      // Automatic handshake response
      if (inferredType === 'hello') {
        const supportedCodecs = msg.codecs || ['video/avc'];
        const chosenCodec = supportedCodecs.includes('video/hevc') ? 'video/hevc' : 'video/avc';
        const deviceIp = socket.remoteAddress || 'unknown';
        const isTrusted = trustedDevices.has(deviceIp);

        sendControl(socket, {
          type: 'hello-ack',
          receiver: 'Mirror PC',
          params: { width: 1280, height: 720, fps: 30, codec: chosenCodec },
          pinRequired: !isTrusted
        });
        
        if (isTrusted) {
          isPaired = true;
          broadcastSyncState();
        }
      }
      if (inferredType === 'verify-pin') {
        const isMatch = msg.pin === currentPin;
        sendControl(socket, {
          type: 'auth-result',
          success: isMatch,
          message: isMatch ? 'Pairing successful' : 'Incorrect PIN. Please try again.'
        });
        
        if (isMatch) {
          trustedDevices.add(socket.remoteAddress || 'unknown');
          isPaired = true;
          currentPin = '';
          refreshTray();
          broadcastSyncState();
        } else {
          if (onPinFailure()) {
            return false; // Signal to disconnect
          }
        }
      } else if (inferredType === 'ping') {
        sendControl(socket, {
          type: 'pong',
          timestamp: msg.timestamp
        });
      }
    } catch (e) {
      console.error('Failed to parse control message:', e);
    }
  } else if (tag === 0x02) { // Video NAL Unit
    // Detect if this is a config frame (SPS/PPS/VPS)
    let nalOffset = 0;
    if (payload.length >= 4 && payload[0] === 0 && payload[1] === 0 && payload[2] === 0 && payload[3] === 1) nalOffset = 4;
    else if (payload.length >= 3 && payload[0] === 0 && payload[1] === 0 && payload[2] === 1) nalOffset = 3;
    
    const firstByte = payload[nalOffset];
    const nalType = firstByte & 0x1F;
    const hevcType = (firstByte >> 1) & 0x3F;
    const isConfig = (nalType === 7) || (hevcType === 32 || hevcType === 33);
    
    if (isConfig) {
      lastConfigFrame = payload;
    }
    if (debugFrameCount < 5) {
      console.log(`Video frame #${debugFrameCount} received: ${payload.length} bytes`);
      debugFrameCount++;
    }
    if (!isPaired) {
      isPaired = true;
      currentPin = '';
      broadcastSyncState();
    }
    broadcastToWindows('video-frame', payload);
  } else if (tag === 0x03) { // Audio Data (AAC)
    broadcastToWindows('audio-frame', payload);
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

function cleanup() {
  console.log('Cleaning up services...');
  isQuitting = true;
  
  if (bonjour) {
    try {
      bonjour.destroy();
      console.log('mDNS advertiser destroyed');
    } catch (e) {
      console.error('Failed to destroy bonjour:', e);
    }
  }

  if (tcpServer) {
    try {
      tcpServer.close();
      console.log('TCP server closed');
    } catch (e) {
      console.error('Failed to close TCP server:', e);
    }
  }
}

function setupIpc() {
  ipcMain.removeHandler('get-pairing-state');
  ipcMain.removeHandler('send-control');
  ipcMain.removeHandler('project-to-extended');

  ipcMain.handle('get-pairing-state', () => {
    return {
      currentPin,
      currentPeer,
      isPaired,
      lastHelloMsg
    };
  });

  ipcMain.handle('send-control', async (_, msg) => {
    if (activeSocket && !activeSocket.destroyed) {
      sendControl(activeSocket, msg);
    }
  });

  ipcMain.handle('project-to-extended', () => {
    if (projectionWindow) {
      projectionWindow.close();
      projectionWindow = null;
      return false;
    }

    const displays = screen.getAllDisplays();
    const externalDisplay = displays.find((display) => {
      return display.bounds.x !== 0 || display.bounds.y !== 0;
    });

    if (externalDisplay) {
      projectionWindow = new BrowserWindow({
        x: externalDisplay.bounds.x,
        y: externalDisplay.bounds.y,
        fullscreen: true,
        autoHideMenuBar: true,
        webPreferences: {
          preload: path.join(__dirname, 'preload.js'),
        },
      });

      if (MAIN_WINDOW_VITE_DEV_SERVER_URL) {
        projectionWindow.loadURL(`${MAIN_WINDOW_VITE_DEV_SERVER_URL}?projection=true`);
      } else {
        projectionWindow.loadFile(path.join(__dirname, `../renderer/${MAIN_WINDOW_VITE_NAME}/index.html`), { query: { projection: 'true' } });
      }

      projectionWindow.on('closed', () => {
        projectionWindow = null;
        mainWindow?.webContents.send('projection-state', false);
      });

      projectionWindow.webContents.on('did-finish-load', () => {
        // Send unified state immediately
        projectionWindow?.webContents.send('sync-state', {
          currentPin,
          currentPeer,
          isPaired,
          lastHelloMsg
        });
        
        if (lastConfigFrame) {
          projectionWindow?.webContents.send('video-frame', lastConfigFrame);
        }
      });

      return true;
    }
    return false;
  });
}

function broadcastToWindows(channel: string, ...args: any[]) {
  if (channel !== 'video-frame' && channel !== 'audio-frame') {
    console.log(`Broadcasting ${channel} to ${mainWindow ? 'Main' : 'None'} and ${projectionWindow ? 'Projection' : 'None'}`);
  }
  if (mainWindow) {
    mainWindow.webContents.send(channel, ...args);
  }
  if (projectionWindow) {
    projectionWindow.webContents.send(channel, ...args);
  }
}

app.on('ready', createWindow);

app.on('before-quit', cleanup);

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
