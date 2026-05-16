import { contextBridge, ipcRenderer } from 'electron';

/**
 * Preload script to securely expose IPC methods to the renderer.
 * 
 * Requirements: tasks.md §4.2, §4.3
 */
contextBridge.exposeInMainWorld('electronAPI', {
  // Listeners for events from Main process
  onControlMessage: (callback: (msg: unknown) => void) => 
    ipcRenderer.on('control-message', (_, value) => callback(value)),
    
  onVideoFrame: (callback: (frame: Uint8Array) => void) => 
    ipcRenderer.on('video-frame', (_, value) => callback(value)),

  onAudioFrame: (callback: (frame: Uint8Array) => void) =>
    ipcRenderer.on('audio-frame', (_, value) => callback(value)),
    
  onPeerConnected: (callback: (peer: { address: string }) => void) =>
    ipcRenderer.on('peer-connected', (_, value) => callback(value)),
    
  onPeerDisconnected: (callback: () => void) =>
    ipcRenderer.on('peer-disconnected', () => callback()),

  onPairingPin: (callback: (pin: string) => void) =>
    ipcRenderer.on('pairing-pin', (_, value) => callback(value)),

  onLocalIp: (callback: (ip: string) => void) =>
    ipcRenderer.on('local-ip', (_, value) => callback(value)),

  onPairingSuccess: (callback: () => void) =>
    ipcRenderer.on('pairing-success', () => callback()),
    
  onProjectionState: (callback: (isProjecting: boolean) => void) =>
    ipcRenderer.on('projection-state', (_, value) => callback(value)),

  onSyncState: (callback: (state: unknown) => void) =>
    ipcRenderer.on('sync-state', (_, value) => callback(value)),

  // Actions to send to Main process
  sendControl: (msg: Record<string, unknown>) => ipcRenderer.invoke('send-control', msg),
  projectToExtended: () => ipcRenderer.invoke('project-to-extended'),
  getPairingState: () => ipcRenderer.invoke('get-pairing-state'),
});
