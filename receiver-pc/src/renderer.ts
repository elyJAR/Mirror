/**
 * Renderer process logic for the Mirror Receiver.
 * 
 * Uses WebCodecs VideoDecoder to render the incoming H.264 stream.
 * 
 * Requirements: tasks.md §4.3
 */

const statusEl = document.getElementById('status')!;
const debugLogsEl = document.getElementById('debug-logs')!;

function logToScreen(msg: string) {
  if (debugLogsEl) {
    const div = document.createElement('div');
    div.textContent = `> ${msg}`;
    debugLogsEl.prepend(div);
  }
  console.log('[UI LOG]', msg);
}
const peerEl = document.getElementById('peer')!;
const statsEl = document.getElementById('stats')!;
const hudEl = document.getElementById('hud')!;
const pairingEl = document.getElementById('pairing')!;
const pinEl = document.getElementById('pin')!;
const btnProject = document.getElementById('btnProject') as HTMLButtonElement;
const btnRefresh = document.getElementById('btnRefresh') as HTMLButtonElement;
const canvas = document.getElementById('videoCanvas') as HTMLCanvasElement;

const isProjection = new URLSearchParams(window.location.search).get('projection') === 'true';

if (isProjection) {
  btnProject.style.display = 'none';
  statusEl.style.display = 'none';
  hudEl.style.display = 'none';
}

btnProject.onclick = async () => {
  const isNowProjecting = await window.electronAPI.projectToExtended();
  updateProjectionUI(isNowProjecting);
};

function updateProjectionUI(isProjecting: boolean) {
  btnProject.textContent = isProjecting ? 'Stop Projection' : 'Project to Extended';
  btnProject.style.backgroundColor = isProjecting ? '#ff4444' : '';
}

window.electronAPI.onProjectionState((isProjecting) => {
  updateProjectionUI(isProjecting);
});

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
let inputEnabled = false;

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
  // AudioContext can only be created once (rate-limited by browsers / Electron).
  if (!audioCtx) {
    audioCtx = new window.AudioContext({ latencyHint: 'interactive' });
    nextAudioTime = audioCtx.currentTime;
  }

  // Re-create AudioDecoder on every call so reconnects get a fresh decoder.
  if (audioDecoder && audioDecoder.state !== 'closed') {
    audioDecoder.close();
  }

  audioDecoder = new window.AudioDecoder({
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

      const now = audioCtx.currentTime;
      // Prevent unbounded latency: bound the playhead to within 100ms of real-time
      if (nextAudioTime < now || nextAudioTime > now + 0.1) {
        nextAudioTime = now + 0.05; // Force 50ms buffer
      }

      source.start(nextAudioTime);
      nextAudioTime += buffer.duration;

      data.close();
    },
    error: (e) => console.error('AudioDecoder error:', e),
  });

  // AudioSpecificConfig for AAC-LC, 44100 Hz, stereo:
  //   audioObjectType=2 (5 bits), samplingFreqIdx=4 (4 bits), channelConfig=2 (4 bits)
  //   => 00010 0100 0010 0000 => 0x12 0x10
  audioDecoder.configure({
    codec: 'mp4a.40.2',
    sampleRate: 44100,
    numberOfChannels: 2,
    description: new Uint8Array([0x12, 0x10]),
  });

  console.log('AudioDecoder configured (AAC-LC 44100Hz stereo)');
}

// Initialise audio on any user interaction (fallback / reconnect path)
window.addEventListener('click', () => {
  initAudio();
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

// --- State Management ---

function applyState(state: any) {
  logToScreen(`Applying state: peer=${state.currentPeer}, pin=${state.currentPin}, paired=${state.isPaired}`);
  
  if (state.currentPeer) {
    peerEl.textContent = state.currentPeer;
    statusEl.textContent = 'Connected, waiting for stream...';
    if (!isProjection) {
      btnProject.style.display = 'block';
    }
  } else {
    peerEl.textContent = 'No Peer';
    statusEl.textContent = 'Waiting for phone...';
    btnProject.style.display = 'none';
  }

  if (state.currentPin) {
    pinEl.textContent = state.currentPin;
    pairingEl.style.display = 'block';
  } else {
    // Only hide pairing if we are actually paired or disconnected
    if (state.isPaired || !state.currentPeer) {
      pairingEl.style.display = 'none';
    }
  }

  if (state.isPaired) {
    pairingEl.style.display = 'none';
    if (debugLogsEl) debugLogsEl.style.display = 'none';
    inputEnabled = true;
    statusEl.textContent = 'Authenticated. Starting stream...';
  } else {
    inputEnabled = false;
    if (debugLogsEl) debugLogsEl.style.display = 'block';
  }
}

if (btnRefresh) {
  btnRefresh.onclick = () => {
    logToScreen('Manual refresh requested');
    window.electronAPI.getPairingState().then(applyState);
  };
}

window.electronAPI.onSyncState((state: any) => {
  logToScreen('SYNC state received');
  applyState(state);
});

// Initial setup
try {
  initDecoder();
  logToScreen('Renderer initialized');
} catch (e) {
  logToScreen(`Init error: ${e}`);
}

// Fetch initial state with a slight delay
setTimeout(() => {
  logToScreen('Initial state fetch...');
  window.electronAPI.getPairingState().then(applyState);
}, 500);

// --- IPC Event Handlers ---

window.electronAPI.onLocalIp((ip) => {
  logToScreen(`Local IP received: ${ip}`);
  statusEl.textContent = `Waiting for phone at ${ip}...`;
});

window.electronAPI.onPeerConnected((peer) => {
  logToScreen(`Peer connected: ${peer.address}`);
  peerEl.textContent = peer.address;
  statusEl.textContent = 'Connected, waiting for stream...';
  initDecoder(); // Re-init on new connection
  inputEnabled = false;
  if (!isProjection) {
    btnProject.style.display = 'block';
  }
});

window.electronAPI.onPeerDisconnected(() => {
  logToScreen('Peer disconnected');
  peerEl.textContent = 'No Peer';
  statusEl.textContent = 'Waiting for phone...';
  isConfigured = false;
  pairingEl.style.display = 'none';
  inputEnabled = false;
  btnProject.style.display = 'none';
});

window.electronAPI.onControlMessage((msg) => {
  console.log('Control Message:', msg);
  if (msg.type === 'hello') {
    statusEl.textContent = `Streaming from ${msg.device}`;
  }
});

window.electronAPI.onVideoFrame((payload: Uint8Array) => {
  if (frameCount < 5) {
    logToScreen(`Video IPC received: ${payload.length} bytes (#${frameCount})`);
  }
  if (!decoder || decoder.state === 'closed') return;

  bytesReceived += payload.length;

  // Skip any leading Annex-B start code so nalOffset always points to the NAL type byte.
  // Combined HEVC payloads (SPS+PPS+IDR) start with 0x00 0x00 0x00 0x01 which would
  // make payload[0] look like 0x00 (not a NAL type) and break decoder configuration.
  let nalOffset = 0;
  if (payload.length >= 4 && payload[0] === 0 && payload[1] === 0 && payload[2] === 0 && payload[3] === 1) {
    nalOffset = 4;
  } else if (payload.length >= 3 && payload[0] === 0 && payload[1] === 0 && payload[2] === 1) {
    nalOffset = 3;
  }

  const firstByte = payload[nalOffset];
  const nalType   = firstByte & 0x1F;         // AVC 5-bit NAL type
  const hevcType  = (firstByte >> 1) & 0x3F;  // HEVC 6-bit NAL type

  // Configure decoder on first parameter-set NAL (AVC SPS=7, HEVC VPS=32, SPS=33)
  const isAVC  = nalType  === 7;
  const isHEVC = hevcType === 32 || hevcType === 33;

  if (!isConfigured && (isAVC || isHEVC)) {
    // Level 5.0 (L150) matches HEVCMainTierLevel5 reported by the Android encoder
    const codecStr = isHEVC ? 'hev1.1.6.L150.90' : 'avc1.42E01F';
    console.log(`Configuring decoder with ${codecStr}...`);
    decoder.configure({ codec: codecStr, optimizeForLatency: true });
    isConfigured = true;
  }

  if (!isConfigured) return;

  // Build Annex-B chunk. Don't prepend a start code if the payload already has one
  // (combined SPS+PPS+IDR chunks already start with 0x00 0x00 0x00 0x01).
  let annexB: Uint8Array;
  if (nalOffset > 0) {
    annexB = payload;
  } else {
    annexB = new Uint8Array(payload.length + 4);
    annexB.set([0, 0, 0, 1], 0);
    annexB.set(payload, 4);
  }

  // Mark as keyframe for IDR NAL types OR parameter-set NAL types
  // (parameter-set types are only sent prepended to IDR frames).
  // AVC:  IDR=5, SPS=7 (combined chunk)
  // HEVC: IDR/CRA=16-21, VPS/SPS/PPS=32-34 (combined chunk)
  const isKey = isHEVC
    ? ((hevcType >= 16 && hevcType <= 21) || (hevcType >= 32 && hevcType <= 34))
    : (nalType === 5 || nalType === 7);

  const chunk = new EncodedVideoChunk({
    type: isKey ? 'key' : 'delta',
    timestamp: performance.now() * 1000,
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
  if (!inputEnabled) {
    return;
  }

  const rect = canvas.getBoundingClientRect();
  const containerAspect = rect.width / rect.height;
  const videoAspect = canvas.width / canvas.height;

  let offsetX = 0;
  let offsetY = 0;
  let scaleX = 1;
  let scaleY = 1;

  if (containerAspect > videoAspect) {
    // Letterboxed on sides
    const actualWidth = rect.height * videoAspect;
    offsetX = (rect.width - actualWidth) / 2;
    scaleX = actualWidth;
    scaleY = rect.height;
  } else {
    // Letterboxed top/bottom
    const actualHeight = rect.width / videoAspect;
    offsetY = (rect.height - actualHeight) / 2;
    scaleX = rect.width;
    scaleY = actualHeight;
  }

  const x = (e.clientX - rect.left - offsetX) / scaleX;
  const y = (e.clientY - rect.top - offsetY) / scaleY;
  
  if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
    // Visual feedback
    showTouchFeedback(e.clientX, e.clientY);

    window.electronAPI.sendControl({
      type: 'touch',
      action,
      x,
      y
    });
  }
}

function showTouchFeedback(x: number, y: number) {
  const dot = document.createElement('div');
  dot.style.position = 'absolute';
  dot.style.left = `${x - 5}px`;
  dot.style.top = `${y - 5}px`;
  dot.style.width = '10px';
  dot.style.height = '10px';
  dot.style.background = '#00ff00';
  dot.style.borderRadius = '50%';
  dot.style.pointerEvents = 'none';
  dot.style.zIndex = '1000';
  dot.style.boxShadow = '0 0 10px #00ff00';
  document.body.appendChild(dot);
  setTimeout(() => dot.remove(), 200);
}

// Key events
window.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.key === 'h') {
    // Already handled above
    return;
  }

  if (!inputEnabled) {
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
  logToScreen(`Pairing PIN received: ${pin}`);
  pinEl.textContent = pin;
  pairingEl.style.display = 'block';
});

window.electronAPI.onPairingSuccess(() => {
  logToScreen('Pairing SUCCESS');
  pairingEl.style.display = 'none';
  statusEl.textContent = 'Authenticated. Starting stream...';
  inputEnabled = true;
  // Pairing success fires right after user interaction (PIN submit) — safe to
  // initialise AudioContext here to satisfy the browser autoplay policy.
  initAudio();
});
