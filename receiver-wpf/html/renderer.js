// Get DOM elements
const statusEl = document.getElementById('status');
const peerEl = document.getElementById('peer');
const statsEl = document.getElementById('stats');
const hudEl = document.getElementById('hud');
const pairingEl = document.getElementById('pairing');
const pinEl = document.getElementById('pin');
const canvas = document.getElementById('videoCanvas');
const ctx = canvas.getContext('2d');
const btnProject = document.getElementById('btnProject');
const audioStatusEl = document.getElementById('audio-status');

// Waiting Panel elements
const waitingPanelEl = document.getElementById('waiting-panel');
const waitingIpEl = document.getElementById('waiting-ip');
const waitingDeviceNameEl = document.getElementById('waiting-device-name');
const btnCopyIp = document.getElementById('btnCopyIp');
const btnCopyPs = document.getElementById('btnCopyPs');
const troubleHeader = document.getElementById('troubleHeader');
const troubleContent = document.getElementById('troubleContent');
const troubleArrow = document.getElementById('troubleArrow');

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

let decoder = null;
let audioDecoder = null;
let audioCtx = null;
let inputEnabled = false;

let isConfigured = false;
let frameCount = 0;
let bytesReceived = 0;
let lastStatsTime = Date.now();

// Shared AV sync base (anchored to audio master clock)
let baseAndroidTs = null;
let baseAudioContextTime = 0;
let lastAudioFrameTime = 0;

// Video sync base (used when audio is inactive/absent)
let videoBaseAndroidTs = null;
let videoBaseAudioContextTime = 0;
let lastVideoFrameTime = 0;

// Wall clock sync (received from main window in projection mode)
let baseRenderWallClock = null;
let lastSyncSendTime = 0;
let forceSyncSend = false;

let nextAudioPlayTime = 0;
const SYNC_DELAY_US = 40000; // 40ms buffer for sync (ultra-low latency)

function initDecoder() {
  if (decoder) {
    try { decoder.close(); } catch(e) {}
  }

  decoder = new VideoDecoder({
    output: (frame) => {
      let delayMs = 0;

      if (isProjectionMode) {
        // Projection mode: synchronize exactly to the parent clock using wall time
        if (baseRenderWallClock !== null && baseAndroidTs !== null) {
          const targetWallClock = baseRenderWallClock + (frame.timestamp - baseAndroidTs) / 1000;
          delayMs = targetWallClock - Date.now();
        } else {
          delayMs = 0;
        }
      } else {
        // Main window mode: calculate delay based on local audio-master or fallback clock
        const isAudioActive = baseAndroidTs !== null && (Date.now() - lastAudioFrameTime < 1500);

        if (isAudioActive && audioCtx) {
          const now = audioCtx.currentTime;
          const targetTime = baseAudioContextTime + (frame.timestamp - baseAndroidTs) / 1000000;
          delayMs = (targetTime - now) * 1000;
        } else {
          const fallbackNow = performance.now() / 1000;
          if (videoBaseAndroidTs === null || (Date.now() - lastVideoFrameTime > 1500)) {
            videoBaseAndroidTs = frame.timestamp;
            videoBaseAudioContextTime = fallbackNow + SYNC_DELAY_US / 1000000;
          }
          const targetTime = videoBaseAudioContextTime + (frame.timestamp - videoBaseAndroidTs) / 1000000;
          lastVideoFrameTime = Date.now();
          delayMs = (targetTime - fallbackNow) * 1000;
        }

        // Broadcast sync state to C# (which will forward to projection window)
        const nowMs = Date.now();
        if (nowMs - lastSyncSendTime > 500 || forceSyncSend) {
          const activeAndroidTs = isAudioActive ? baseAndroidTs : videoBaseAndroidTs;
          const currentBaseRenderWallClock = isAudioActive && audioCtx
            ? Date.now() + (baseAudioContextTime - audioCtx.currentTime) * 1000
            : Date.now() + (videoBaseAudioContextTime - (performance.now() / 1000)) * 1000;

          if (activeAndroidTs !== null && currentBaseRenderWallClock !== null) {
            postToHost({
              type: 'sync-state',
              baseAndroidTs: activeAndroidTs,
              baseRenderWallClock: currentBaseRenderWallClock,
              lastAudioFrameTime: isAudioActive ? lastAudioFrameTime : Date.now(),
              isAudioActive
            });
            lastSyncSendTime = nowMs;
            forceSyncSend = false;
          }
        }
      }

      if (delayMs <= 5) {
        renderFrame(frame);
      } else if (delayMs > 1000) {
        console.warn(`Extreme video delay (${delayMs.toFixed(0)}ms), rendering immediately.`);
        renderFrame(frame);
      } else {
        setTimeout(() => {
          renderFrame(frame);
        }, delayMs);
      }
    },
    error: (e) => {
      console.error('VideoDecoder error:', e);
      statusEl.textContent = `Decoder Error: ${e.message}`;
    },
  });

  isConfigured = false;
}

function renderFrame(frame) {
  if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
    canvas.width = frame.displayWidth;
    canvas.height = frame.displayHeight;
  }
  ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
  frame.close();
  frameCount++;
}

function initAudio() {
  if (!audioCtx) {
    audioCtx = new window.AudioContext({ latencyHint: 'interactive' });
  }

  if (audioCtx.state === 'suspended') {
    audioCtx.resume();
  }

  if (audioDecoder && audioDecoder.state !== 'closed') {
    try { audioDecoder.close(); } catch(e) {}
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

      const duration = buffer.duration;
      const now = audioCtx.currentTime;

      let startTime = nextAudioPlayTime;
      if (startTime < now || startTime > now + 1.0) {
        startTime = now + SYNC_DELAY_US / 1000000;
        forceSyncSend = true;
      }

      source.start(startTime);
      nextAudioPlayTime = startTime + duration;

      baseAndroidTs = data.timestamp || 0;
      baseAudioContextTime = startTime;
      lastAudioFrameTime = Date.now();

      if (audioStatusEl) {
        audioStatusEl.textContent = 'Audio: Live';
        audioStatusEl.style.color = '#4ade80';
      }

      data.close();
    },
    error: (e) => console.error('AudioDecoder error:', e),
  });

  // AudioSpecificConfig for AAC-LC, 44100 Hz, stereo:
  // 0x12 0x10
  audioDecoder.configure({
    codec: 'mp4a.40.2',
    sampleRate: 44100,
    numberOfChannels: 2,
    description: new Uint8Array([0x12, 0x10]),
  });

  console.log('AudioDecoder configured (AAC-LC 44100Hz stereo)');
}

window.addEventListener('click', () => {
  initAudio();
});

// Helper: Convert Base64 string to Uint8Array
function base64ToBytes(base64) {
  const binString = atob(base64);
  const len = binString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binString.charCodeAt(i);
  }
  return bytes;
}

// Helper: Post messages back to the C# WPF Application
function postToHost(msg) {
  if (window.chrome && window.chrome.webview) {
    window.chrome.webview.postMessage(JSON.stringify(msg));
  }
}

// Setup initial UI states
initDecoder();
statusEl.textContent = 'Waiting for phone...';
if (waitingPanelEl && !isProjectionMode) {
  waitingPanelEl.style.display = 'flex';
}

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
    const powershellCmd = `Start-Process powershell -Verb runAs -ArgumentList "-NoExit -Command New-NetFirewallRule -DisplayName 'Mirror Receiver C# TCP' -Direction Inbound -Program '${escapedPath}' -Action Allow -Protocol TCP -LocalPort 8765; New-NetFirewallRule -DisplayName 'Mirror Receiver C# UDP' -Direction Inbound -Program '${escapedPath}' -Action Allow -Protocol UDP -LocalPort 8768"`;
    
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

// --- IPC Message Handler from C# ---
if (window.chrome && window.chrome.webview) {
  window.chrome.webview.addEventListener('message', (event) => {
    try {
      const msg = JSON.parse(event.data);
      handleIncomingMessage(msg);
    } catch (e) {
      console.error('Failed to parse host message:', e);
    }
  });
}

function handleIncomingMessage(msg) {
  switch (msg.type) {
    case 'local-ip':
      handleLocalIp(msg);
      break;

    case 'peer-connected':
      handlePeerConnected(msg.address);
      break;

    case 'peer-disconnected':
      handlePeerDisconnected();
      break;

    case 'pairing-pin':
      handlePairingPin(msg.pin);
      break;

    case 'pairing-success':
      handlePairingSuccess();
      break;

    case 'control-message':
      handleControlMessage(msg.payload);
      break;

    case 'sync-state':
      handleSyncState(msg);
      break;

    case 'video-frame':
      handleVideoFrame(msg.data, msg.pts);
      break;

    case 'audio-frame':
      handleAudioFrame(msg.data, msg.pts);
      break;
      
    case 'projection-state':
      handleProjectionState(msg.active);
      break;
  }
}

function handleLocalIp(data) {
  const ipStr = data.ip || 'Unknown IP';
  const deviceName = data.deviceName || 'Mirror Receiver';
  runningExecPath = data.execPath || '';
  
  if (waitingIpEl) waitingIpEl.textContent = ipStr;
  if (waitingDeviceNameEl) waitingDeviceNameEl.textContent = deviceName;
  if (btnCopyIp) btnCopyIp.style.display = 'block';
  
  statusEl.textContent = `Waiting for phone at ${ipStr}...`;
}

function handlePeerConnected(address) {
  peerEl.textContent = address;
  statusEl.textContent = 'Connected, waiting for stream...';
  if (waitingPanelEl) waitingPanelEl.style.display = 'none';
  baseAndroidTs = null;
  videoBaseAndroidTs = null;
  nextAudioPlayTime = 0;
  initDecoder();
  inputEnabled = false;
  if (btnProject) btnProject.style.display = 'block';
}

function handlePeerDisconnected() {
  peerEl.textContent = 'No Peer';
  statusEl.textContent = 'Waiting for phone...';
  if (waitingPanelEl && !isProjectionMode) {
    waitingPanelEl.style.display = 'flex';
  }
  isConfigured = false;
  pairingEl.style.display = 'none';
  inputEnabled = false;
  baseAndroidTs = null;
  videoBaseAndroidTs = null;
  nextAudioPlayTime = 0;
  if (btnProject) btnProject.style.display = 'none';
}

function handlePairingPin(pin) {
  pinEl.textContent = pin;
  pairingEl.style.display = 'block';
}

function handlePairingSuccess() {
  pairingEl.style.display = 'none';
  if (waitingPanelEl) waitingPanelEl.style.display = 'none';
  statusEl.textContent = 'Authenticated. Starting stream...';
  inputEnabled = true;
  initAudio();
}

function handleControlMessage(payload) {
  console.log('Control Message:', payload);
  if (payload.type === 'hello') {
    statusEl.textContent = `Streaming from ${payload.device}`;
  }
}

function handleSyncState(state) {
  if (isProjectionMode && state) {
    baseAndroidTs = state.baseAndroidTs;
    baseRenderWallClock = state.baseRenderWallClock;
    lastAudioFrameTime = state.lastAudioFrameTime;
  }
}

function handleVideoFrame(base64Data, timestamp) {
  if (!decoder || decoder.state === 'closed') return;

  const payload = base64ToBytes(base64Data);
  bytesReceived += payload.length;

  let nalOffset = 0;
  if (payload.length >= 4 && payload[0] === 0 && payload[1] === 0 && payload[2] === 0 && payload[3] === 1) {
    nalOffset = 4;
  } else if (payload.length >= 3 && payload[0] === 0 && payload[1] === 0 && payload[2] === 1) {
    nalOffset = 3;
  }

  const firstByte = payload[nalOffset];
  const nalType   = firstByte & 0x1F;
  const hevcType  = (firstByte >> 1) & 0x3F;

  const isAVC  = nalType  === 7;
  const isHEVC = hevcType === 32 || hevcType === 33;

  if (!isConfigured && (isAVC || isHEVC)) {
    const codecStr = isHEVC ? 'hev1.1.6.L150.90' : 'avc1.42E01F';
    console.log(`Configuring decoder with ${codecStr}...`);
    decoder.configure({ codec: codecStr, optimizeForLatency: true });
    isConfigured = true;
  }

  if (!isConfigured) return;

  let annexB;
  if (nalOffset > 0) {
    annexB = payload;
  } else {
    annexB = new Uint8Array(payload.length + 4);
    annexB.set([0, 0, 0, 1], 0);
    annexB.set(payload, 4);
  }

  const isKey = isHEVC
    ? ((hevcType >= 16 && hevcType <= 21) || (hevcType >= 32 && hevcType <= 34))
    : (nalType === 5 || nalType === 7);

  const chunk = new EncodedVideoChunk({
    type: isKey ? 'key' : 'delta',
    timestamp: timestamp,
    data: annexB,
  });

  try {
    decoder.decode(chunk);
  } catch (e) {
    console.error('Decode failed:', e);
  }
}

function handleAudioFrame(base64Data, timestamp) {
  if (!audioDecoder || audioDecoder.state === 'closed') return;

  const payload = base64ToBytes(base64Data);
  const chunk = new EncodedAudioChunk({
    type: 'key',
    timestamp: timestamp,
    data: payload,
  });

  try {
    audioDecoder.decode(chunk);
  } catch (e) {
    console.error('Audio decode failed:', e);
  }
}

function handleProjectionState(isProjected) {
  if (btnProject) {
    btnProject.textContent = isProjected ? 'Back to Primary' : 'Project to Extended';
    btnProject.style.background = isProjected ? 'rgba(255,68,68,0.7)' : 'rgba(0,122,255,0.7)';
  }
}

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

function sendTouch(action, e) {
  if (!inputEnabled) return;

  const rect = canvas.getBoundingClientRect();
  const x = (e.clientX - rect.left) / rect.width;
  const y = (e.clientY - rect.top) / rect.height;
  
  if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
    postToHost({
      type: 'touch',
      action,
      x,
      y
    });
  }
}

window.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.key === 'h') return;
  if (!inputEnabled) return;
  
  let code = -1;
  if (e.key === 'Backspace') code = 67; // KEYCODE_DEL
  if (e.key === 'Enter')     code = 66; // KEYCODE_ENTER
  if (e.key === 'Escape')    code = 4;  // KEYCODE_BACK
  
  if (code !== -1) {
    postToHost({
      type: 'key',
      code
    });
  }
});

// --- Projection Click Handlers ---
if (btnProject) {
  btnProject.addEventListener('click', () => {
    postToHost({ type: 'project' });
  });
}
