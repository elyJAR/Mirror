import { app, BrowserWindow, ipcMain } from 'electron';
import path from 'node:path';
import started from 'electron-squirrel-startup';
import { Bonjour } from 'bonjour-service';
import * as net from 'net';

// Handle creating/removing shortcuts on Windows when installing/uninstalling.
if (started) {
  app.quit();
}

let mainWindow: BrowserWindow | null = null;
const bonjour = new Bonjour();
let tcpServer: net.Server | null = null;

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

  // Open the DevTools for development
  // mainWindow.webContents.openDevTools();

  startNetworkServices(mainWindow);
};

/**
 * Starts the mDNS advertiser and TCP server for the Mirror protocol.
 */
function startNetworkServices(window: BrowserWindow) {
  if (tcpServer) return;

  tcpServer = net.createServer((socket) => {
    const remoteAddress = socket.remoteAddress;
    console.log('Phone connected:', remoteAddress);
    
    // Notify renderer of connection
    window.webContents.send('peer-connected', { address: remoteAddress });
    
    let buffer = Buffer.alloc(0);
    
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
        
        handleFrame(tag, payload, socket, window);
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

    // Handle outgoing control messages from renderer
    ipcMain.handle('send-control', async (_event, msg) => {
      if (!socket.destroyed) {
        sendControl(socket, msg);
      }
    });
  });

  // Listen on all interfaces
  tcpServer.listen(8765, '0.0.0.0', () => {
    console.log('TCP Server listening on port 8765');
  });

  // Advertise service via mDNS
  bonjour.publish({ 
    name: 'Mirror PC', 
    type: 'mirror-stream', 
    protocol: 'tcp', 
    port: 8765 
  });
}

function handleFrame(tag: number, payload: Buffer, socket: net.Socket, window: BrowserWindow) {
  if (tag === 0x01) { // Control Message (JSON)
    try {
      const msg = JSON.parse(payload.toString());
      window.webContents.send('control-message', msg);
      
      // Automatic handshake response
      if (msg.type === 'hello') {
        sendControl(socket, {
          type: 'hello-ack',
          receiver: 'Mirror PC',
          params: { width: 1280, height: 720, fps: 30 }
        });
      }
    } catch (e) {
      console.error('Failed to parse control message:', e);
    }
  } else if (tag === 0x02) { // Video NAL Unit
    // Forward raw bytes to renderer for WebCodecs
    window.webContents.send('video-frame', payload);
  }
}

function sendControl(socket: net.Socket, msg: any) {
  const json = JSON.stringify(msg);
  const payload = Buffer.from(json);
  const header = Buffer.alloc(5);
  header.writeUInt8(0x01, 0);
  header.writeInt32BE(payload.length, 1);
  socket.write(Buffer.concat([header, payload]));
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
