/**
 * Renderer process logic for the Mirror Receiver.
 * 
 * Uses WebCodecs VideoDecoder to render the incoming H.264 stream.
 * 
 * Requirements: tasks.md §4.3
 */

const statusEl = document.getElementById('status')!;
const peerEl = document.getElementById('peer')!;
const statsEl = document.getElementById('stats')!;
const hudEl = document.getElementById('hud')!;
const pairingEl = document.getElementById('pairing')!;
const pinEl = document.getElementById('pin')!;
const canvas = document.getElementById('videoCanvas') as HTMLCanvasElement;
const ctx = canvas.getContext('2d')!;

let hudVisible = false;

window.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.key === 'h') {
    hudVisible = !hudVisible;
    const display = hudVisible ? 'block' : 'none';
    statusEl.style.display = display;
    hudEl.style.display = display;
  }
});

let decoder: VideoDecoder | null = null;
let audioDecoder: AudioDecoder | null = null;
let audioCtx: AudioContext | null = null;
let nextAudioTime = 0;

let isConfigured = false;
let frameCount = 0;
let bytesReceived = 0;
let lastStatsTime = Date.now();

function initDecoder() {
  if (decoder) {
    decoder.close();
  }

  decoder = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      frame.close();
      frameCount++;
    },
    error: (e) => {
      console.error('VideoDecoder error:', e);
      statusEl.textContent = `Decoder Error: ${e.message}`;
    },
  });

  isConfigured = false;
}

function initAudio() {
  if (audioCtx) return;
  audioCtx = new AudioContext();
  nextAudioTime = audioCtx.currentTime;
  
  audioDecoder = new AudioDecoder({
    output: (data) => {
      if (!audioCtx) return;
      
      const buffer = audioCtx.createBuffer(
        data.numberOfChannels,
        data.numberOfFrames,
        data.sampleRate
      );
      
      for (let i = 0; i < data.numberOfChannels; i++) {
        data.copyTo(buffer.getChannelData(i), { planeIndex: i });
      }
      
      const source = audioCtx.createBufferSource();
      source.buffer = buffer;
      source.connect(audioCtx.destination);
      
      // Schedule playback
      const startTime = Math.max(nextAudioTime, audioCtx.currentTime);
      source.start(startTime);
      nextAudioTime = startTime + buffer.duration;
      
      data.close();
    },
    error: (e) => console.error('AudioDecoder error:', e),
  });
  
  audioDecoder.configure({
    codec: 'mp4a.40.2', // AAC-LC
    sampleRate: 44100,
    numberOfChannels: 2,
  });
}

// ...
window.addEventListener('click', () => {
  initAudio();
  statusEl.textContent = 'Audio enabled';
});

// ...
window.electronAPI.onAudioFrame((payload: Uint8Array) => {
  if (!audioDecoder || audioDecoder.state === 'closed') return;

  const chunk = new EncodedAudioChunk({
    type: 'key',
    timestamp: performance.now() * 1000,
    data: payload,
  });

  try {
    audioDecoder.decode(chunk);
  } catch (e) {
    console.error('Audio decode failed:', e);
  }
});

// Initial setup
initDecoder();
statusEl.textContent = 'Waiting for phone...';

// --- IPC Event Handlers ---

window.electronAPI.onPeerConnected((peer) => {
  peerEl.textContent = peer.address;
  statusEl.textContent = 'Connected, waiting for stream...';
  initDecoder(); // Re-init on new connection
});

window.electronAPI.onPeerDisconnected(() => {
  peerEl.textContent = 'No Peer';
  statusEl.textContent = 'Waiting for phone...';
  isConfigured = false;
});

window.electronAPI.onControlMessage((msg) => {
  console.log('Control Message:', msg);
  if (msg.type === 'hello') {
    statusEl.textContent = `Streaming from ${msg.device}`;
  }
});

window.electronAPI.onVideoFrame((payload: Uint8Array) => {
  if (!decoder || decoder.state === 'closed') return;

  let nalType = payload[0] & 0x1F;
  // If it's HEVC, the type is in bits 1-6 of the first byte
  const hevcType = (payload[0] >> 1) & 0x3F;
  
  // 1. Configure decoder on first SPS (AVC: 7) or VPS/SPS (HEVC: 32, 33)
  const isAVC = nalType === 7;
  const isHEVC = hevcType === 32 || hevcType === 33;

  if (!isConfigured && (isAVC || isHEVC)) {
    const codecStr = isHEVC ? 'hev1.1.6.L120.90' : 'avc1.42E01F';
    console.log(`Configuring decoder with ${codecStr}...`);
    decoder.configure({
      codec: codecStr,
      optimizeForLatency: true,
    });
    isConfigured = true;
  }

  if (!isConfigured) return;

  // 2. Prepare chunk
  // Prepend Annex-B start code because our Android encoder strips them
  const annexB = new Uint8Array(payload.length + 4);
  annexB.set([0, 0, 0, 1], 0);
  annexB.set(payload, 4);

  const chunk = new EncodedVideoChunk({
    type: (nalType === 5) ? 'key' : 'delta',
    timestamp: performance.now() * 1000, // Microseconds
    data: annexB,
  });

  try {
    decoder.decode(chunk);
  } catch (e) {
    console.error('Decode failed:', e);
  }
});

// --- Stats Loop ---

setInterval(() => {
  const now = Date.now();
  const elapsed = (now - lastStatsTime) / 1000;
  if (elapsed >= 1) {
    const fps = Math.round(frameCount / elapsed);
    const kbps = Math.round((bytesReceived * 8) / (1000 * elapsed));
    statsEl.textContent = `${fps} FPS / ${kbps} kbps`;
    frameCount = 0;
    bytesReceived = 0;
    lastStatsTime = now;
  }
}, 1000);

// --- Touch-back (Input Injection) ---

canvas.addEventListener('mousedown', (e) => {
  sendTouch(0, e);
});

canvas.addEventListener('mouseup', (e) => {
  sendTouch(1, e);
});

canvas.addEventListener('mousemove', (e) => {
  if (e.buttons > 0) {
    sendTouch(2, e);
  }
});

function sendTouch(action: number, e: MouseEvent) {
  const rect = canvas.getBoundingClientRect();
  const x = (e.clientX - rect.left) / rect.width;
  const y = (e.clientY - rect.top) / rect.height;
  
  if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
    window.electronAPI.sendControl({
      type: 'touch',
      action,
      x,
      y
    });
  }
}

// Key events
window.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.key === 'h') {
    // Already handled above
    return;
  }
  
  // Map some keys to Android keys (optional, but good for demo)
  let code = -1;
  if (e.key === 'Backspace') code = 67; // KEYCODE_DEL
  if (e.key === 'Enter') code = 66;     // KEYCODE_ENTER
  if (e.key === 'Escape') code = 4;      // KEYCODE_BACK
  
  if (code !== -1) {
    window.electronAPI.sendControl({
      type: 'key',
      code
    });
  }
});

// --- Pairing IPC Handlers ---

window.electronAPI.onPairingPin((pin) => {
  pinEl.textContent = pin;
  pairingEl.style.display = 'block';
});

window.electronAPI.onPairingSuccess(() => {
  pairingEl.style.display = 'none';
  statusEl.textContent = 'Authenticated. Starting stream...';
});
