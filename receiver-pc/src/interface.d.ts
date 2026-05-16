export interface IElectronAPI {
  onControlMessage: (callback: (msg: any) => void) => void;
  onVideoFrame: (callback: (frame: Uint8Array) => void) => void;
  onAudioFrame: (callback: (frame: Uint8Array) => void) => void;
  onPeerConnected: (callback: (peer: { address: string }) => void) => void;
  onPeerDisconnected: (callback: () => void) => void;
  onPairingPin: (callback: (pin: string) => void) => void;
  onLocalIp: (callback: (ip: string) => void) => void;
  onPairingSuccess: (callback: () => void) => void;
  onProjectionState: (callback: (isProjecting: boolean) => void) => void;
  onSyncState: (callback: (state: any) => void) => void;
  sendControl: (msg: any) => Promise<void>;
  projectToExtended: () => Promise<boolean>;
  getPairingState: () => Promise<any>;
}

declare global {
  interface Window {
    electronAPI: IElectronAPI;
  }
  // WebCodecs types
  var AudioDecoder: any;
  var EncodedAudioChunk: any;
}
