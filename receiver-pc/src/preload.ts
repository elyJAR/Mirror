import { contextBridge, ipcRenderer } from 'electron';

/**
 * Preload script to securely expose IPC methods to the renderer.
 * 
 * Requirements: tasks.md §4.2, §4.3
 */
contextBridge.exposeInMainWorld('electronAPI', {
  // Listeners for events from Main process
  onControlMessage: (callback: (msg: any) => void) => 
    ipcRenderer.on('control-message', (_event, value) => callback(value)),
    
  onVideoFrame: (callback: (frame: Uint8Array) => void) => 
    ipcRenderer.on('video-frame', (_event, value) => callback(value)),
    
  onPeerConnected: (callback: (peer: { address: string }) => void) =>
    ipcRenderer.on('peer-connected', (_event, value) => callback(value)),
    
  onPeerDisconnected: (callback: () => void) =>
    ipcRenderer.on('peer-disconnected', (_event) => callback()),

  // Actions to send to Main process
  sendControl: (msg: any) => ipcRenderer.invoke('send-control', msg),
});
