/**
 * MeetApp — Pre-join Hardware Check & Gating (prejoin.js)
 */

import { CONFIG, logDebug } from './config.js';
import { apiRequest } from './api.js';
import { getStoredUser } from './auth.js';
import { MediaManager } from './media.js';

let mediaManager = null;
let micEnabled = true;
let camEnabled = true;

const roomSummaryText = document.getElementById('roomSummaryText');
const previewVideo = document.getElementById('previewVideo');
const previewAvatar = document.getElementById('previewAvatar');
const micMeterFill = document.getElementById('micMeterFill');
const btnTogglePrejoinMic = document.getElementById('btnTogglePrejoinMic');
const labelPrejoinMic = document.getElementById('labelPrejoinMic');
const btnTogglePrejoinCam = document.getElementById('btnTogglePrejoinCam');
const labelPrejoinCam = document.getElementById('labelPrejoinCam');
const btnJoinMeeting = document.getElementById('btnJoinMeeting');

async function initPrejoin() {
  const params = new URLSearchParams(window.location.search);
  const roomCode = params.get('code');
  if (!roomCode) {
    window.location.replace('dashboard.html');
    return;
  }

  const normalizedCode = roomCode.trim().toUpperCase();

  // 1. Auth Guard
  const user = getStoredUser();
  if (!user && !CONFIG.MOCK) {
    window.location.replace(`login.html?redirect=${encodeURIComponent(window.location.href)}`);
    return;
  }

  // 2. Validate Room via REST
  try {
    if (!CONFIG.MOCK) {
      const room = await apiRequest(`/rooms/${normalizedCode}`);
      if (!room || !room.active) {
        roomSummaryText.textContent = `Room ${normalizedCode} is no longer active.`;
        return;
      }
      const count = room.activeParticipantCount || 0;
      roomSummaryText.textContent = `Room ${normalizedCode} • Hosted by ${room.host?.name || 'Host'} • ${count} in call`;
    } else {
      roomSummaryText.textContent = `Room ${normalizedCode} • Hosted by Demo Host • 2 in call (Mock Mode)`;
    }
    btnJoinMeeting.disabled = false;
  } catch (err) {
    roomSummaryText.textContent = `Unable to join room: ${err.message || 'Room not found'}`;
    return;
  }

  // 3. Start Camera & Microphone Preview
  mediaManager = new MediaManager({
    onLocalStream: (stream, hardware) => {
      previewVideo.srcObject = stream;
      if (!hardware.hasCamera) {
        camEnabled = false;
        updateCamUI();
      }
      if (!hardware.hasMicrophone) {
        micEnabled = false;
        updateMicUI();
      }
    },
    onSpeakingVolume: (volume) => {
      micMeterFill.style.width = `${volume}%`;
    },
    onHardwareError: (err) => {
      logDebug('Prejoin', err.message);
    }
  });

  await mediaManager.initLocalMedia(true, true);

  // 4. Bind Toggle Buttons
  btnTogglePrejoinMic.addEventListener('click', () => {
    micEnabled = !micEnabled;
    mediaManager.setAudioMuted(!micEnabled);
    updateMicUI();
  });

  btnTogglePrejoinCam.addEventListener('click', () => {
    camEnabled = !camEnabled;
    mediaManager.setVideoMuted(!camEnabled);
    updateCamUI();
  });

  // 5. Join Room Action
  btnJoinMeeting.addEventListener('click', async () => {
    btnJoinMeeting.disabled = true;
    btnJoinMeeting.textContent = 'Joining...';

    try {
      if (!CONFIG.MOCK) {
        // Register active participation in database
        await apiRequest(`/rooms/${normalizedCode}/join`, { method: 'POST' });
      }

      // Stop pre-join preview tracks before room page takes over
      if (mediaManager) {
        mediaManager.stopAll();
        mediaManager = null;
      }

      const micParam = micEnabled ? '1' : '0';
      const camParam = camEnabled ? '1' : '0';
      window.location.replace(`room.html?code=${normalizedCode}&mic=${micParam}&cam=${camParam}`);
    } catch (err) {
      alert(`Could not join room: ${err.message}`);
      btnJoinMeeting.disabled = false;
      btnJoinMeeting.textContent = 'Join Meeting';
    }
  });

  // Teardown preview on navigation
  window.addEventListener('pagehide', () => {
    if (mediaManager) {
      mediaManager.stopAll();
      mediaManager = null;
    }
  });
}

function updateMicUI() {
  if (micEnabled) {
    btnTogglePrejoinMic.classList.remove('pill-off');
    labelPrejoinMic.textContent = 'Microphone On';
  } else {
    btnTogglePrejoinMic.classList.add('pill-off');
    labelPrejoinMic.textContent = 'Microphone Off';
    micMeterFill.style.width = '0%';
  }
}

function updateCamUI() {
  if (camEnabled) {
    btnTogglePrejoinCam.classList.remove('pill-off');
    labelPrejoinCam.textContent = 'Camera On';
    previewVideo.style.display = 'block';
    previewAvatar.style.display = 'none';
  } else {
    btnTogglePrejoinCam.classList.add('pill-off');
    labelPrejoinCam.textContent = 'Camera Off';
    previewVideo.style.display = 'none';
    previewAvatar.style.display = 'flex';
  }
}

document.addEventListener('DOMContentLoaded', initPrejoin);
