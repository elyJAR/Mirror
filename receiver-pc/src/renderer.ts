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
let isConfigured = false;
let frameCount = 0;
let bytesReceived = 0;
let lastStatsTime = Date.now();

/**
 * Initializes the WebCodecs VideoDecoder.
 */
function initDecoder() {
  if (decoder) {
    decoder.close();
  }

  decoder = new VideoDecoder({
    output: (frame) => {
      // Scale canvas to match frame size if it changes
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

  bytesReceived += payload.length;
  const nalType = payload[0] & 0x1F;
  
  // 1. Configure decoder on first SPS (type 7)
  if (!isConfigured && (nalType === 7)) {
    console.log('Configuring decoder with SPS...');
    decoder.configure({
      codec: 'avc1.42E01F', // H.264 Baseline 3.1
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
