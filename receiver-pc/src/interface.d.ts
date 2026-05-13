export interface IElectronAPI {
  onControlMessage: (callback: (msg: any) => void) => void;
  onVideoFrame: (callback: (frame: Uint8Array) => void) => void;
  onPeerConnected: (callback: (peer: { address: string }) => void) => void;
  onPeerDisconnected: (callback: () => void) => void;
  sendControl: (msg: any) => Promise<void>;
}

declare global {
  interface Window {
    electronAPI: IElectronAPI;
  }
}
