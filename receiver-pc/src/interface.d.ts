export interface IElectronAPI {
  onControlMessage: (callback: (msg: Record<string, unknown>) => void) => void;
  onVideoFrame: (callback: (frame: Uint8Array) => void) => void;
  onAudioFrame: (callback: (frame: Uint8Array) => void) => void;
  onPeerConnected: (callback: (peer: { address: string }) => void) => void;
  onPeerDisconnected: (callback: () => void) => void;
  onPairingPin: (callback: (pin: string) => void) => void;
  onLocalIp: (callback: (ip: string) => void) => void;
  onPairingSuccess: (callback: () => void) => void;
  onProjectionState: (callback: (isProjecting: boolean) => void) => void;
  onSyncState: (callback: (state: unknown) => void) => void;
  sendControl: (msg: Record<string, unknown>) => Promise<void>;
  projectToExtended: () => Promise<boolean>;
  getPairingState: () => Promise<unknown>;
}

declare global {
  interface Window {
    electronAPI: IElectronAPI;
    AudioDecoder: {
      new (init: {
        output: (data: unknown) => void;
        error: (e: Error) => void;
      }): unknown;
      isConfigSupported(config: unknown): Promise<unknown>;
    };
    EncodedAudioChunk: {
      new (init: {
        type: 'key' | 'delta';
        timestamp: number;
        data: Uint8Array;
      }): unknown;
    };
    AudioContext: typeof AudioContext;
  }
}
