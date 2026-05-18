/**
 * Renderer process logic for the Mirror Receiver.
 * 
 * Uses WebCodecs VideoDecoder to render the incoming H.264 stream.
 * 
 * Requirements: tasks.md §4.3
 */
import type { IAudioData, IAudioDecoder } from './interface';

const statusEl = document.getElementById('status') as HTMLElement;
const peerEl = document.getElementById('peer') as HTMLElement;
const statsEl = document.getElementById('stats') as HTMLElement;
const hudEl = document.getElementById('hud') as HTMLElement;
const pairingEl = document.getElementById('pairing') as HTMLElement;
const pinEl = document.getElementById('pin') as HTMLElement;
const canvas = document.getElementById('videoCanvas') as HTMLCanvasElement;
const ctx = canvas.getContext('2d') as CanvasRenderingContext2D;
const btnProject = document.getElementById('btnProject') as HTMLButtonElement;
const audioStatusEl = document.getElementById('audio-status') as HTMLElement;

// Waiting Panel elements
const waitingPanelEl = document.getElementById('waiting-panel') as HTMLElement;
const waitingIpEl = document.getElementById('waiting-ip') as HTMLElement;
const waitingDeviceNameEl = document.getElementById('waiting-device-name') as HTMLElement;
const btnCopyIp = document.getElementById('btnCopyIp') as HTMLButtonElement;
const btnCopyPs = document.getElementById('btnCopyPs') as HTMLButtonElement;
const troubleHeader = document.getElementById('troubleHeader') as HTMLElement;
const troubleContent = document.getElementById('troubleContent') as HTMLElement;
const troubleArrow = document.getElementById('troubleArrow') as HTMLElement;

let hudVisible = false;
const isProjectionMode = new URLSearchParams(window.location.search).get('mode') === 'projection';

if (isProjectionMode) {
  pairingEl.style.display = 'none';
  hudEl.style.display = 'none';
  statusEl.style.display = 'none';
  if (waitingPanelEl) waitingPanelEl.style.display = 'none';
  const controls = document.getElementById('controls');
  if (controls) controls.style.display = 'none';
  const logs = document.getElementById('debug-logs');
  if (logs) logs.style.display = 'none';
}

window.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.key === 'h') {
    hudVisible = !hudVisible;
    const display = hudVisible ? 'block' : 'none';
    statusEl.style.display = display;
    hudEl.style.display = display;
  }
});

let decoder: VideoDecoder | null = null;
let audioDecoder: IAudioDecoder | null = null;
let audioCtx: AudioContext | null = null;
let inputEnabled = false;

let isConfigured = false;
let frameCount = 0;
let bytesReceived = 0;
let lastStatsTime = Date.now();

// Shared AV sync base (anchored to audio master clock)
let baseAndroidTs: number | null = null;
let baseAudioContextTime = 0;
let lastAudioFrameTime = 0; // JS timestamp when last audio frame was processed

// Video sync base (used when audio is inactive/absent)
let videoBaseAndroidTs: number | null = null;
let videoBaseAudioContextTime = 0;
let lastVideoFrameTime = 0; // JS timestamp when last video frame was processed

let nextAudioPlayTime = 0;
const SYNC_DELAY_US = 40000; // 40ms buffer for sync (ultra-low latency)

function initDecoder() {
  if (decoder) {
    decoder.close();
  }

  decoder = new VideoDecoder({
    output: (frame) => {
      let targetTime = 0;
      let delayMs = 0;

      // Determine if audio is actively driving the master clock (within the last 1.5 seconds)
      const isAudioActive = baseAndroidTs !== null && (Date.now() - lastAudioFrameTime < 1500);

      if (isAudioActive && audioCtx) {
        // Audio-master clock: calculate video target time relative to the audio playback anchor
        const now = audioCtx.currentTime;
        targetTime = baseAudioContextTime + (frame.timestamp - baseAndroidTs) / 1000000;
        delayMs = (targetTime - now) * 1000;
      } else {
        // Fallback: Video drives its own clock (uses performance.now() high-res ticking timeline)
        const fallbackNow = performance.now() / 1000;
        if (videoBaseAndroidTs === null || (Date.now() - lastVideoFrameTime > 1500)) {
          videoBaseAndroidTs = frame.timestamp;
          videoBaseAudioContextTime = fallbackNow + SYNC_DELAY_US / 1000000;
        }
        targetTime = videoBaseAudioContextTime + (frame.timestamp - videoBaseAndroidTs) / 1000000;
        lastVideoFrameTime = Date.now();
        delayMs = (targetTime - fallbackNow) * 1000;
      }

      if (delayMs <= 5) {
        // Render immediately if delay is tiny (under 5ms)
        if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
          canvas.width = frame.displayWidth;
          canvas.height = frame.displayHeight;
        }
        ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
        frame.close();
        frameCount++;
      } else if (delayMs > 1000) {
        // Extreme delay - likely a sync jump/reset, render immediately
        console.warn(`Extreme video delay (${delayMs.toFixed(0)}ms), rendering immediately.`);
        if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
          canvas.width = frame.displayWidth;
          canvas.height = frame.displayHeight;
        }
        ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
        frame.close();
        frameCount++;
      } else {
        setTimeout(() => {
          if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
            canvas.width = frame.displayWidth;
            canvas.height = frame.displayHeight;
          }
          ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
          frame.close();
          frameCount++;
        }, delayMs);
      }
    },
    error: (e: Error) => {
      console.error('VideoDecoder error:', e);
      statusEl.textContent = `Decoder Error: ${e.message}`;
    },
  });

  isConfigured = false;
}

function initAudio() {
  if (!audioCtx) {
    audioCtx = new window.AudioContext({ latencyHint: 'interactive' });
  }

  if (audioCtx.state === 'suspended') {
    audioCtx.resume();
  }

  // Re-create AudioDecoder on every call so reconnects get a fresh decoder.
  if (audioDecoder && audioDecoder.state !== 'closed') {
    audioDecoder.close();
  }

  audioDecoder = new window.AudioDecoder({
    output: (data: IAudioData) => {
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

      const duration = buffer.duration;
      const now = audioCtx.currentTime;

      // Schedule seamlessly back-to-back without overlapping or leaving silent gaps
      let startTime = nextAudioPlayTime;
      
      // If we haven't scheduled yet, or if we have fallen behind (e.g. network stall)
      if (startTime < now || startTime > now + 1.0) {
        startTime = now + SYNC_DELAY_US / 1000000;
      }

      source.start(startTime);
      nextAudioPlayTime = startTime + duration;

      // Update the master sync base using this audio frame's exact scheduled playback time
      baseAndroidTs = data.timestamp || 0;
      baseAudioContextTime = startTime;
      lastAudioFrameTime = Date.now();

      if (audioStatusEl) {
        audioStatusEl.textContent = 'Audio: Live';
        audioStatusEl.style.color = '#4ade80';
      }

      data.close();
    },
    error: (e: Error) => console.error('AudioDecoder error:', e),
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

window.electronAPI.onAudioFrame((payload: Uint8Array, timestamp: number) => {
  if (!audioDecoder || audioDecoder.state === 'closed') return;

  const chunk = new window.EncodedAudioChunk({
    type: 'key',
    timestamp: timestamp,
    data: payload,
  });

  try {
    audioDecoder.decode(chunk);
  } catch (e: unknown) {
    console.error('Audio decode failed:', e);
  }
});

// Initial setup and element visibility
initDecoder();
statusEl.textContent = 'Waiting for phone...';
if (waitingPanelEl && !isProjectionMode) {
  waitingPanelEl.style.display = 'flex';
}

// Variables for dynamic commands
let runningExecPath = '';

// --- Waiting Panel Interaction Listeners ---
if (troubleHeader && troubleContent && troubleArrow) {
  troubleHeader.addEventListener('click', () => {
    const isExpanded = troubleContent.classList.toggle('expanded');
    troubleArrow.style.transform = isExpanded ? 'rotate(180deg)' : 'rotate(0deg)';
  });
}

if (btnCopyIp && waitingIpEl) {
  btnCopyIp.addEventListener('click', () => {
    const ipText = waitingIpEl.textContent || '';
    navigator.clipboard.writeText(ipText).then(() => {
      btnCopyIp.textContent = 'Copied!';
      btnCopyIp.style.background = '#4ade80';
      setTimeout(() => {
        btnCopyIp.textContent = 'Copy';
        btnCopyIp.style.background = '#007AFF';
      }, 2000);
    });
  });
}

if (btnCopyPs) {
  btnCopyPs.addEventListener('click', () => {
    if (!runningExecPath) {
      btnCopyPs.textContent = 'Error: Path not resolved!';
      return;
    }
    const escapedPath = runningExecPath.replace(/'/g, "''");
    const powershellCmd = `Start-Process powershell -Verb runAs -ArgumentList "-NoExit -Command New-NetFirewallRule -DisplayName 'Mirror Receiver TCP' -Direction Inbound -Program '${escapedPath}' -Action Allow -Protocol TCP -LocalPort 8765; New-NetFirewallRule -DisplayName 'Mirror Receiver UDP' -Direction Inbound -Program '${escapedPath}' -Action Allow -Protocol UDP -LocalPort 8768"`;
    
    navigator.clipboard.writeText(powershellCmd).then(() => {
      btnCopyPs.textContent = 'Copied! Run in Admin PowerShell';
      btnCopyPs.style.background = 'rgba(74, 222, 128, 0.15)';
      btnCopyPs.style.borderColor = '#4ade80';
      btnCopyPs.style.color = '#4ade80';
      setTimeout(() => {
        btnCopyPs.textContent = 'Copy Firewall Fix Command';
        btnCopyPs.style.background = 'rgba(0, 122, 255, 0.15)';
        btnCopyPs.style.borderColor = 'rgba(0, 122, 255, 0.4)';
        btnCopyPs.style.color = '#007AFF';
      }, 4000);
    });
  });
}

// --- IPC Event Handlers ---

window.electronAPI.onLocalIp((data: { ip: string; deviceName: string; execPath: string; isPackaged: boolean } | string) => {
  let ipStr = '';
  let deviceName = 'Mirror Receiver';
  
  if (data && typeof data === 'object') {
    ipStr = data.ip || 'Unknown IP';
    deviceName = data.deviceName || 'Mirror Receiver';
    runningExecPath = data.execPath || '';
    
    // Update waiting screen dynamic values
    if (waitingIpEl) waitingIpEl.textContent = ipStr;
    if (waitingDeviceNameEl) waitingDeviceNameEl.textContent = deviceName;
    if (btnCopyIp) btnCopyIp.style.display = 'block';
    
    // Adjust firewall troubleshooting based on OS
    const psSection = document.querySelector('.powershell-section') as HTMLElement;
    const tipEl = document.querySelector('.tip') as HTMLElement;
    const explanationEl = document.querySelector('.explanation') as HTMLElement;
    
    if (runningExecPath.includes('\\') || runningExecPath.includes(':')) {
      if (psSection) psSection.style.display = 'block';
    } else {
      if (psSection) psSection.style.display = 'none';
      if (tipEl) tipEl.textContent = 'Make sure your firewall allows incoming connections on TCP port 8765 and UDP port 8768.';
      if (explanationEl) explanationEl.textContent = 'Your system firewall may block incoming network packets by default. Please configure your system firewall to allow incoming traffic on ports 8765 and 8768.';
    }
  } else {
    ipStr = data;
    if (waitingIpEl) waitingIpEl.textContent = ipStr;
  }
  
  statusEl.textContent = `Waiting for phone at ${ipStr}...`;
});

window.electronAPI.onPeerConnected((peer) => {
  peerEl.textContent = peer.address;
  statusEl.textContent = 'Connected, waiting for stream...';
  if (waitingPanelEl) waitingPanelEl.style.display = 'none'; // Hide waiting screen
  baseAndroidTs = null; // Reset sync
  videoBaseAndroidTs = null; // Reset fallback sync
  nextAudioPlayTime = 0; // Reset play schedule
  initDecoder(); // Re-init on new connection
  inputEnabled = false;
  if (btnProject) btnProject.style.display = 'block';
});

window.electronAPI.onPeerDisconnected(() => {
  peerEl.textContent = 'No Peer';
  statusEl.textContent = 'Waiting for phone...';
  if (waitingPanelEl && !isProjectionMode) {
    waitingPanelEl.style.display = 'flex'; // Show waiting screen
  }
  isConfigured = false;
  pairingEl.style.display = 'none';
  inputEnabled = false;
  baseAndroidTs = null; // Reset sync
  videoBaseAndroidTs = null; // Reset fallback sync
  nextAudioPlayTime = 0; // Reset play schedule
  if (btnProject) btnProject.style.display = 'none';
});

window.electronAPI.onControlMessage((msg) => {
  console.log('Control Message:', msg);
  if (msg.type === 'hello') {
    statusEl.textContent = `Streaming from ${msg.device}`;
  }
});

window.electronAPI.onVideoFrame((payload: Uint8Array, timestamp: number) => {
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

  const chunk = new window.EncodedVideoChunk({
    type: isKey ? 'key' : 'delta',
    timestamp: timestamp,
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
    
    // Calculate current A/V drift for debugging (optional)
    // const now = audioCtx?.currentTime || 0;
    // const lastFrameTs = baseAndroidTs ? (baseAndroidTs + (now - baseAudioContextTime) * 1000000) : 0;
    
    statsEl.textContent = `${fps} FPS / ${kbps} kbps`;
    // console.log(`A/V Sync: delay=${SYNC_DELAY_US}us, currentTime=${now.toFixed(3)}`);
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
  pinEl.textContent = pin;
  pairingEl.style.display = 'block';
});

window.electronAPI.onPairingSuccess(() => {
  pairingEl.style.display = 'none';
  if (waitingPanelEl) waitingPanelEl.style.display = 'none'; // Hide waiting screen
  statusEl.textContent = 'Authenticated. Starting stream...';
  inputEnabled = true;
  // Pairing success fires right after user interaction (PIN submit) — safe to
  // initialise AudioContext here to satisfy the browser autoplay policy.
  initAudio();
});

// --- Projection UI ---

if (btnProject) {
  btnProject.addEventListener('click', () => {
    window.electronAPI.projectToExtended();
  });
}

window.electronAPI.onProjectionState((isProjected) => {
  if (btnProject) {
    btnProject.textContent = isProjected ? 'Back to Primary' : 'Project to Extended';
    btnProject.style.background = isProjected ? 'rgba(255,68,68,0.7)' : 'rgba(0,122,255,0.7)';
  }
});
