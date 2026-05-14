import { app, BrowserWindow, ipcMain } from 'electron';
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
const bonjour = new Bonjour();
let tcpServer: net.Server | null = null;
const currentPin = Math.floor(1000 + Math.random() * 9000).toString();
const advertisedName = `Mirror PC ${process.pid}`;
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
    autoHideMenuBar: true,
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

  // Open the DevTools for development
  // mainWindow.webContents.openDevTools();

  startNetworkServices(mainWindow);
};

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
    
    // Notify renderer of connection and current PIN
    window.webContents.send('peer-connected', { address: remoteAddress });
    window.webContents.send('pairing-pin', currentPin);
    
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
      window.webContents.send('peer-disconnected');
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
      window.webContents.send('peer-disconnected');
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
      
      window.webContents.send('control-message', msg);
      
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
          window.webContents.send('pairing-success');
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
          window.webContents.send('pairing-success');
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
    window.webContents.send('video-frame', payload);
  } else if (tag === 0x03) { // Audio Data (AAC)
    window.webContents.send('audio-frame', payload);
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
  bonjour.destroy();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});
