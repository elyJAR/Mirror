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
let currentPin = '';
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

  mainWindow.webContents.on('did-finish-load', () => {
    const ip = getLocalIpAddress();
    if (ip) {
      mainWindow?.webContents.send('local-ip', `${ip}:8765`);
    }
    if (currentPin) {
      mainWindow?.webContents.send('pairing-pin', currentPin);
    }
  });

  startNetworkServices(mainWindow);
  createTray();
};

function createTray() {
  const refreshTray = () => {
    const ip = getLocalIpAddress() || 'Unknown IP';
    
    console.log('[Tray] Creating tray. Searching for icon...');
    
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
        console.log('[Tray] Found icon at:', p);
        icon = img;
        break;
      }
    }
    
    if (icon.isEmpty()) {
      console.warn('[Tray] All icon paths failed. Using colored fallback.');
      // Fallback: A bright blue circle (base64) to ensure visibility
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

  tcpServer = net.createServer((socket) => {
    const remoteAddress = socket.remoteAddress;
    console.log('Phone connected:', remoteAddress);
    
    // Generate fresh PIN and notify all windows
    currentPin = Math.floor(1000 + Math.random() * 9000).toString();
    console.log('New connection from', remoteAddress, 'PIN:', currentPin);
    
    broadcastToWindows('peer-connected', { address: remoteAddress });
    broadcastToWindows('pairing-pin', currentPin);
    
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
      broadcastToWindows('peer-disconnected');
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

    if (mainWindow) {
      broadcastToWindows('peer-disconnected');
    }
  });

  // Listen on all interfaces (IPv4 and IPv6)
  tcpServer.listen(8765, () => {
    console.log('TCP Server listening on port 8765 (all interfaces)');
  });

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
          broadcastToWindows('pairing-success');
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
          broadcastToWindows('pairing-success');
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

  ipcMain.removeHandler('send-control');
}

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
      if (currentPin) {
        projectionWindow?.webContents.send('pairing-pin', currentPin);
      }
    });

    return true;
  }
  return false;
});

function broadcastToWindows(channel: string, ...args: any[]) {
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
