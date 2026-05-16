export interface IElectronAPI {
  onControlMessage: (callback: (msg: Record<string, unknown>) => void) => void;
  onVideoFrame: (callback: (frame: Uint8Array, timestamp: number) => void) => void;
  onAudioFrame: (callback: (frame: Uint8Array, timestamp: number) => void) => void;
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

export interface IAudioData {
  numberOfChannels: number;
  numberOfFrames: number;
  sampleRate: number;
  timestamp: number;
  copyTo: (dest: Float32Array, options: { planeIndex: number }) => void;
  close: () => void;
}

export interface IAudioDecoder {
  decode: (chunk: unknown) => void;
  close: () => void;
  state: string;
  configure: (config: { codec: string; sampleRate: number; numberOfChannels: number; description?: Uint8Array }) => void;
}

declare global {
  interface Window {
    electronAPI: IElectronAPI;
    AudioDecoder: {
      new (init: {
        output: (data: IAudioData) => void;
        error: (e: Error) => void;
      }): IAudioDecoder;
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
