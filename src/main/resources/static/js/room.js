/**
 * MeetApp — Meeting Room Coordinator (room.js)
 * Coordinates signaling, local media acquisition, screen sharing,
 * WebRTC peer connection mesh, responsive video grid, presenter stage,
 * keyboard shortcuts (M/V only), wake lock, and leak-free teardown.
 */

import { CONFIG, logDebug } from './config.js';
import { apiRequest } from './api.js';
import { getStoredUser, clearAuth } from './auth.js';
import { SignalingClient } from './socket.js';
import { MediaManager } from './media.js';
import { PeerMeshManager } from './peers.js';
import { VideoGridManager } from './grid.js';
import { ScreenShareManager } from './screenshare.js';
import { ChatManager } from './chat.js';
import { FileManager } from './files.js';
import { WhiteboardManager } from './whiteboard.js';

let signaling = null;
let mediaManager = null;
let peerMesh = null;
let videoGrid = null;
let screenShareManager = null;
let chatManager = null;
let fileManager = null;
let whiteboardManager = null;
let currentSidePanelTab = 'chat'; // 'chat' | 'files'
let wakeLock = null;
let isTearingDown = false;
let isHost = false;
let currentUser = null;
let normalizedCode = '';

// Verified active presenter: { userId, streamId, label } or null
let activePresenter = null;

// DOM Elements
const roomCodeDisplay = document.getElementById('roomCodeDisplay');
const btnCopyCode = document.getElementById('btnCopyCode');
const connStatusDot = document.getElementById('connStatusDot');
const participantCount = document.getElementById('participantCount');
const videoGridEl = document.getElementById('videoGrid');
const btnToggleMic = document.getElementById('btnToggleMic');
const btnToggleCam = document.getElementById('btnToggleCam');
const btnShareScreen = document.getElementById('btnShareScreen');
const btnLeave = document.getElementById('btnLeave');
const btnEndAll = document.getElementById('btnEndAll');
const ariaLiveEl = document.getElementById('roomAriaLive');

// SVG Icons
const ICON_MIC_ON = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path><path d="M19 10v2a7 7 0 0 1-14 0v-2"></path><line x1="12" y1="19" x2="12" y2="23"></line><line x1="8" y1="23" x2="16" y2="23"></line></svg>`;
const ICON_MIC_OFF = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="1" y1="1" x2="23" y2="23"></line><path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"></path><path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"></path><line x1="12" y1="19" x2="12" y2="23"></line><line x1="8" y1="23" x2="16" y2="23"></line></svg>`;
const ICON_CAM_ON = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>`;
const ICON_CAM_OFF = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="1" y1="1" x2="23" y2="23"></line><path d="M21 21l-3.34-3.34M23 7l-7 5 7 5V7z"></path><path d="M1 5h2m4 0h9a2 2 0 0 1 2 2v9m-4 4H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2"></path></svg>`;

async function initRoom() {
  const params = new URLSearchParams(window.location.search);
  const roomCode = params.get('code');
  if (!roomCode) {
    window.location.replace('dashboard.html');
    return;
  }

  normalizedCode = roomCode.trim().toUpperCase();
  roomCodeDisplay.textContent = normalizedCode;

  const initialMicPref = params.get('mic') !== '0';
  const initialCamPref = params.get('cam') !== '0';

  // 1. Check Authentication
  currentUser = getStoredUser();
  if (!currentUser && !CONFIG.MOCK) {
    window.location.replace(`login.html?redirect=${encodeURIComponent(window.location.href)}`);
    return;
  }

  // 2. Fetch Room Details (to check host privileges and active state)
  isHost = false;
  try {
    if (!CONFIG.MOCK) {
      const roomDetails = await apiRequest(`/rooms/${normalizedCode}`);
      if (roomDetails && roomDetails.host && currentUser && roomDetails.host.id === currentUser.id) {
        isHost = true;
        btnEndAll.style.display = 'inline-flex';
      }
    }
  } catch (err) {
    logDebug('Room', 'Could not fetch room details', err.message);
  }

  // 3. Fetch Dynamic ICE Server Configuration
  let iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
  try {
    if (!CONFIG.MOCK) {
      const iceConfig = await apiRequest('/webrtc/ice-config');
      if (iceConfig && iceConfig.iceServers && iceConfig.iceServers.length > 0) {
        iceServers = iceConfig.iceServers;
      }
    }
  } catch (err) {
    logDebug('Room', 'Falling back to default STUN', err.message);
  }

  // 4. Initialize Video Grid (with host / self stop callbacks)
  videoGrid = new VideoGridManager(videoGridEl, {
    onSelfStop: () => {
      stopLocalScreenShare('USER_STOPPED');
    },
    onHostStop: (targetPresenterUserId) => {
      requestHostStopScreenShare(targetPresenterUserId);
    }
  });

  // 5. Initialize Signaling Client
  signaling = new SignalingClient(normalizedCode, {
    onOpen: () => {
      connStatusDot.className = 'conn-status-dot connected';
      connStatusDot.title = 'Connected';
    },
    onClose: () => {
      connStatusDot.className = 'conn-status-dot disconnected';
      connStatusDot.title = 'Disconnected';
    },
    onError: (err) => {
      showToast(err.message || 'Connection problem');
    },
    onJoinAck: (payload) => {
      handleJoinAck(payload);
    },
    onPeerJoined: (peer) => {
      handlePeerJoined(peer);
    },
    onPeerLeft: (payload) => {
      handlePeerLeft(payload);
    },
    onOffer: (msg) => {
      if (peerMesh) peerMesh.handleOffer(msg.from, msg.payload);
    },
    onAnswer: (msg) => {
      if (peerMesh) peerMesh.handleAnswer(msg.from, msg.payload);
    },
    onIceCandidate: (msg) => {
      if (peerMesh) peerMesh.handleIceCandidate(msg.from, msg.payload);
    },
    onMuteStatus: (msg) => {
      handleMuteStatus(msg.from, msg.payload);
    },
    onScreenShareStart: (msg) => {
      handleRemoteScreenShareStart(msg.from, msg.payload);
    },
    onScreenShareStop: (msg) => {
      handleRemoteScreenShareStop(msg.from, msg.payload);
    },
    onRoomEnded: (payload) => {
      showToast('Meeting ended by host');
      teardown();
      window.location.replace(`ended.html?reason=${encodeURIComponent(payload?.reason || 'Room ended by host')}`);
    },
    onReplaced: () => {
      // Tear down all local media, screen capture, peer connections, and wake lock
      teardown();
      const modal = document.getElementById('replacedSessionModal');
      if (modal) {
        modal.style.display = 'flex';
        const returnBtn = document.getElementById('btnReplacedReturn');
        if (returnBtn) {
          returnBtn.focus();
          returnBtn.addEventListener('click', () => {
            window.location.replace('dashboard.html');
          });
        }
      } else {
        window.location.replace('dashboard.html');
      }
    },
    onReconnect: () => {
      // Lifecycle rule: if presenter's socket reconnects, presenter client stops sharing
      if (screenShareManager && screenShareManager.isSharing) {
        logDebug('Room', 'Socket reconnected while presenting: halting local screen share');
        stopLocalScreenShare('USER_STOPPED');
      }
      if (chatManager) {
        chatManager.handleSocketReconnect();
      }
    },
    onChat: (payload) => {
      if (chatManager) {
        chatManager.handleServerChat(payload);
      }
    },
    onSignalError: (payload) => {
      if (chatManager) {
        chatManager.handleSignalError(payload);
      }
      showToast(payload?.message || 'Signaling error');
    }
  });

  // 6. Initialize Chat & File Sharing Managers
  const btnToggleChat = document.getElementById('btnToggleChat');
  const btnToggleFiles = document.getElementById('btnToggleFiles');
  const btnCloseChat = document.getElementById('btnCloseChat');
  const chatDrawer = document.getElementById('chatDrawer');
  const chatUnreadBadge = document.getElementById('chatUnreadBadge');
  const tabBtnChat = document.getElementById('tabBtnChat');
  const tabBtnFiles = document.getElementById('tabBtnFiles');
  const chatTabPane = document.getElementById('chatTabPane');
  const filesTabPane = document.getElementById('filesTabPane');
  const chatTitle = document.getElementById('chatTitle');
  const chatPrivacyNotice = document.getElementById('chatPrivacyNotice');

  chatManager = new ChatManager({
    roomCode: normalizedCode,
    currentUser: currentUser,
    signalingClient: signaling,
    drawerEl: chatDrawer,
    toggleBtnEl: btnToggleChat,
    unreadBadgeEl: chatUnreadBadge,
    ariaLiveEl: ariaLiveEl
  });
  await chatManager.init();

  fileManager = new FileManager(normalizedCode, signaling, isHost, currentUser?.id);
  await fileManager.loadInitialFiles();

  // Tab switching coordination
  function switchSidePanelTab(tabName) {
    currentSidePanelTab = tabName;
    const isFiles = tabName === 'files';

    if (tabBtnChat) {
      tabBtnChat.classList.toggle('active', !isFiles);
      tabBtnChat.setAttribute('aria-selected', !isFiles ? 'true' : 'false');
    }
    if (tabBtnFiles) {
      tabBtnFiles.classList.toggle('active', isFiles);
      tabBtnFiles.setAttribute('aria-selected', isFiles ? 'true' : 'false');
    }

    if (chatTabPane) chatTabPane.style.display = !isFiles ? 'flex' : 'none';
    if (filesTabPane) filesTabPane.style.display = isFiles ? 'flex' : 'none';

    if (chatTitle) {
      chatTitle.textContent = isFiles ? 'Shared Files' : 'In-call messages';
    }
    if (chatPrivacyNotice) {
      chatPrivacyNotice.textContent = isFiles
        ? 'Files uploaded here are shared with everyone in the room.'
        : 'Saved for 30 days. Visible to participants.';
    }

    const drawerIsOpen = chatManager ? chatManager.isOpen : !chatDrawer.hasAttribute('aria-hidden');
    if (fileManager) {
      fileManager.setTabState(isFiles, drawerIsOpen);
    }
    if (!isFiles && drawerIsOpen && chatManager) {
      chatManager.clearUnread();
    }
  }

  function openSidePanel(tabName) {
    if (chatManager && !chatManager.isOpen) {
      chatManager.open();
    } else if (chatDrawer) {
      chatDrawer.removeAttribute('aria-hidden');
      chatDrawer.removeAttribute('inert');
    }
    switchSidePanelTab(tabName);
    if (tabName === 'files') {
      if (btnToggleFiles) btnToggleFiles.classList.add('btn-active-chat');
      if (btnToggleChat) btnToggleChat.classList.remove('btn-active-chat');
    } else {
      if (btnToggleChat) btnToggleChat.classList.add('btn-active-chat');
      if (btnToggleFiles) btnToggleFiles.classList.remove('btn-active-chat');
    }
  }

  function closeSidePanel() {
    if (chatManager && chatManager.isOpen) {
      chatManager.close();
    } else if (chatDrawer) {
      chatDrawer.setAttribute('aria-hidden', 'true');
      chatDrawer.setAttribute('inert', '');
    }
    if (btnToggleChat) btnToggleChat.classList.remove('btn-active-chat');
    if (btnToggleFiles) btnToggleFiles.classList.remove('btn-active-chat');
    if (fileManager) {
      fileManager.setTabState(currentSidePanelTab === 'files', false);
    }
  }

  if (tabBtnChat) {
    tabBtnChat.addEventListener('click', () => switchSidePanelTab('chat'));
  }
  if (tabBtnFiles) {
    tabBtnFiles.addEventListener('click', () => switchSidePanelTab('files'));
  }

  if (btnToggleFiles) {
    btnToggleFiles.addEventListener('click', () => {
      const isDrawerOpen = chatManager ? chatManager.isOpen : !chatDrawer.hasAttribute('aria-hidden');
      if (isDrawerOpen && currentSidePanelTab === 'files') {
        closeSidePanel();
      } else {
        openSidePanel('files');
      }
    });
  }

  if (btnToggleChat) {
    btnToggleChat.addEventListener('click', (e) => {
      if (chatManager && chatManager.isOpen && currentSidePanelTab === 'files') {
        e.stopImmediatePropagation();
        switchSidePanelTab('chat');
        if (btnToggleChat) btnToggleChat.classList.add('btn-active-chat');
        if (btnToggleFiles) btnToggleFiles.classList.remove('btn-active-chat');
      } else {
        switchSidePanelTab('chat');
        if (btnToggleFiles) btnToggleFiles.classList.remove('btn-active-chat');
      }
    }, true);
  }

  if (btnCloseChat) {
    btnCloseChat.addEventListener('click', () => {
      closeSidePanel();
    });
  }

  // 6c. Initialize Whiteboard Manager
  const btnToggleWhiteboard = document.getElementById('btnToggleWhiteboard');
  const whiteboardOverlay = document.getElementById('whiteboardOverlay');
  const whiteboardContainer = document.getElementById('whiteboardContainer');
  const wbBgCanvas = document.getElementById('wbBgCanvas');
  const wbScratchCanvas = document.getElementById('wbScratchCanvas');

  whiteboardManager = new WhiteboardManager({
    roomCode: normalizedCode,
    currentUser: currentUser,
    isHost: isHost,
    signalingClient: signaling,
    overlayEl: whiteboardOverlay,
    containerEl: whiteboardContainer,
    bgCanvasEl: wbBgCanvas,
    scratchCanvasEl: wbScratchCanvas,
    toggleBtnEl: btnToggleWhiteboard
  });

  if (btnToggleWhiteboard) {
    btnToggleWhiteboard.addEventListener('click', () => {
      whiteboardManager.toggle();
    });
  }

  // 6. Acquire Local Camera/Mic Media
  mediaManager = new MediaManager({
    onLocalStream: (stream, hardware) => {
      const selfName = currentUser?.name || 'You';
      videoGrid.setLocalStream('local', selfName, stream, hardware.hasCamera);
      if (peerMesh) {
        peerMesh.setLocalStream(stream);
      }
    },
    onSpeakingVolume: (volume, isSpeaking) => {
      videoGrid.setSpeakingState('local', isSpeaking);
    },
    onHardwareError: (err) => {
      showToast(err.message);
    }
  });

  await mediaManager.initLocalMedia(initialMicPref, initialCamPref);

  // Apply initial mute states if user requested them muted in prejoin
  if (!initialMicPref) {
    mediaManager.setAudioMuted(true);
    btnToggleMic.classList.add('btn-active-muted');
    btnToggleMic.innerHTML = ICON_MIC_OFF;
    videoGrid.setAudioMuteState('local', true);
  }
  if (!initialCamPref) {
    mediaManager.setVideoMuted(true);
    btnToggleCam.classList.add('btn-active-muted');
    btnToggleCam.innerHTML = ICON_CAM_OFF;
    videoGrid.setCameraState('local', false, currentUser?.name || 'You');
  }

  // 7. Initialize Peer Mesh Manager (with verified screen stream listener)
  const selfUserId = currentUser?.id || 999;
  peerMesh = new PeerMeshManager(selfUserId, signaling, iceServers, {
    onRemoteStream: (peerUserId, remoteStream, peerName) => {
      videoGrid.setRemoteStream(peerUserId, remoteStream, peerName);
    },
    onRemoteScreenStream: (presenterUserId, screenStream, presenterName) => {
      videoGrid.setPresenterStream(presenterUserId, presenterName, screenStream, false, isHost);
    },
    onPeerDisconnected: (peerUserId) => {
      videoGrid.removeTile(peerUserId);
      updateParticipantCount();
    }
  });

  if (mediaManager.localStream) {
    peerMesh.setLocalStream(mediaManager.localStream);
  }

  // 8. Initialize Screen Share Manager
  initScreenShareManager();

  // 9. Connect Signaling
  await signaling.connect();

  // 10. Wire UI Toolbar (no keyboard shortcut for screen share)
  bindControls(normalizedCode, isHost);

  // 11. Request Screen Wake Lock
  acquireWakeLock();
}

/**
 * Handles join confirmation from signaling server.
 * Seeds active presenter if present (late joiner discovery).
 */
function handleJoinAck(payload) {
  announce(`Joined room ${payload.roomCode}`);

  if (payload.peers && Array.isArray(payload.peers)) {
    for (const peer of payload.peers) {
      peerMesh.getOrCreatePeer(peer.userId, peer.name, true);
      if (peer.audioMuted !== undefined) {
        videoGrid.setAudioMuteState(peer.userId, peer.audioMuted);
      }
      if (peer.videoMuted !== undefined) {
        videoGrid.setCameraState(peer.userId, !peer.videoMuted, peer.name);
      }
    }
  }

  // Late joiner discovery: presenter seeded in JoinAckPayload
  if (payload.presenter) {
    const p = payload.presenter;
    activePresenter = {
      userId: Number(p.userId),
      streamId: p.streamId
    };
    peerMesh.setPresenter(activePresenter);
    updateShareButtonState();
    logDebug('Room', `Late joiner discovered active presenter (userId=${p.userId}, streamId=${p.streamId})`);
  }

  updateParticipantCount();
}

function handlePeerJoined(peer) {
  announce(`${peer.name || 'A participant'} joined the meeting`);
  if (chatManager) {
    chatManager.addSystemNotice(`${peer.name || 'A participant'} joined the meeting`);
  }
  peerMesh.getOrCreatePeer(peer.userId, peer.name, false);
  updateParticipantCount();
}

function handlePeerLeft(payload) {
  announce(`A participant left the meeting`);
  if (chatManager) {
    chatManager.addSystemNotice(`A participant left the meeting`);
  }
  if (peerMesh) peerMesh.closePeer(payload.userId);
  if (videoGrid) videoGrid.removeTile(payload.userId);

  // If the leaving peer was presenting, clear presenter state
  if (activePresenter && activePresenter.userId === Number(payload.userId)) {
    activePresenter = null;
    if (peerMesh) peerMesh.clearPresenter();
    if (videoGrid) videoGrid.clearPresenter();
    updateShareButtonState();
  }

  updateParticipantCount();
}

function handleMuteStatus(fromUserId, payload) {
  if (payload.audioMuted !== undefined) {
    videoGrid.setAudioMuteState(fromUserId, payload.audioMuted);
  }
  if (payload.videoMuted !== undefined) {
    const peerEntry = peerMesh?.peers?.get(Number(fromUserId));
    const name = peerEntry?.name || `Peer ${fromUserId}`;
    videoGrid.setCameraState(fromUserId, !payload.videoMuted, name);
  }
}

/**
 * Remote peer started screen sharing.
 */
function handleRemoteScreenShareStart(fromUserId, payload) {
  const id = Number(fromUserId);
  activePresenter = {
    userId: id,
    streamId: payload?.streamId,
    label: payload?.label
  };

  peerMesh.setPresenter(activePresenter);
  updateShareButtonState();

  const peerName = peerMesh?.peers?.get(id)?.name || 'A participant';
  announce(`${peerName} started sharing their screen`);
}

/**
 * Screen share stopped in the room.
 */
function handleRemoteScreenShareStop(fromUserId, payload) {
  const stoppedUserId = Number(payload?.userId || fromUserId);
  const reason = payload?.reason || 'USER_STOPPED';

  logDebug('Room', `Screen share stopped (userId=${stoppedUserId}, reason=${reason})`);

  // If local user was the presenter and stopped (e.g. host stopped us)
  if (screenShareManager && screenShareManager.isSharing && currentUser && stoppedUserId === currentUser.id) {
    stopLocalScreenShare('HOST_STOPPED');
    showToast('Your screen share was stopped by the host');
    return;
  }

  // Remote share ended
  activePresenter = null;
  if (peerMesh) peerMesh.clearPresenter();
  if (videoGrid) videoGrid.clearPresenter();
  updateShareButtonState();
  announce('Screen sharing ended');
}

/**
 * Initializes ScreenShareManager and evaluates browser support.
 */
function initScreenShareManager() {
  const supported = ScreenShareManager.isSupported() || CONFIG.MOCK;

  screenShareManager = new ScreenShareManager({
    onEnded: (reason) => {
      // Triggered by native browser floating stop bar (track.onended)
      stopLocalScreenShare(reason);
    },
    onError: (err) => {
      showToast(err.message || 'Screen sharing error');
    }
  });

  updateShareButtonState();
}

/**
 * Updates toolbar button text, tooltip, and disabled state based on feature support and active presenter.
 */
function updateShareButtonState() {
  if (!btnShareScreen) return;

  const supported = ScreenShareManager.isSupported() || CONFIG.MOCK;
  if (!supported) {
    btnShareScreen.disabled = true;
    btnShareScreen.title = 'Screen sharing is not available in this browser';
    btnShareScreen.setAttribute('aria-label', 'Screen sharing is not available in this browser');
    btnShareScreen.setAttribute('aria-pressed', 'false');
    btnShareScreen.classList.remove('btn-active-presenting');
    return;
  }

  if (screenShareManager && screenShareManager.isSharing) {
    // Current user is actively presenting
    btnShareScreen.disabled = false;
    btnShareScreen.title = 'Stop sharing';
    btnShareScreen.setAttribute('aria-label', 'Stop sharing');
    btnShareScreen.setAttribute('aria-pressed', 'true');
    btnShareScreen.classList.add('btn-active-presenting');
    return;
  }

  if (activePresenter && (!currentUser || activePresenter.userId !== currentUser.id)) {
    // Another participant is sharing
    btnShareScreen.disabled = true;
    const presenterName = peerMesh?.peers?.get(activePresenter.userId)?.name || 'Someone';
    const reasonText = `${presenterName} is presenting`;
    btnShareScreen.title = reasonText;
    btnShareScreen.setAttribute('aria-label', reasonText);
    btnShareScreen.setAttribute('aria-pressed', 'false');
    btnShareScreen.classList.remove('btn-active-presenting');
    return;
  }

  // Available to share
  btnShareScreen.disabled = false;
  btnShareScreen.title = 'Share screen';
  btnShareScreen.setAttribute('aria-label', 'Share screen');
  btnShareScreen.setAttribute('aria-pressed', 'false');
  btnShareScreen.classList.remove('btn-active-presenting');
}

/**
 * Starts local screen sharing.
 */
async function startLocalScreenShare() {
  if (activePresenter) {
    showToast('Another participant is already sharing their screen');
    return;
  }

  if (!screenShareManager) return;

  const capture = await screenShareManager.startCapture();
  if (!capture) {
    // User cancelled picker -> per requirement 4: not an error, no message, no server event
    return;
  }

  const selfId = currentUser?.id || 999;
  const selfName = currentUser?.name || 'You';

  activePresenter = {
    userId: selfId,
    streamId: capture.streamId,
    label: 'Screen'
  };

  // Inform signaling server
  signaling.send({
    type: 'screen-share-start',
    roomCode: normalizedCode,
    payload: {
      streamId: capture.streamId,
      label: 'Screen'
    }
  });

  // Attach track to mesh connections and scale bitrates
  peerMesh.setPresenter(activePresenter);
  peerMesh.addScreenTrack(capture.track, capture.stream);

  // Display on local presenter stage
  videoGrid.setPresenterStream(selfId, selfName, capture.stream, true, isHost);

  updateShareButtonState();
  announce('You started sharing your screen');
}

/**
 * Stops local screen sharing and cleans up across all channels.
 */
function stopLocalScreenShare(reason = 'USER_STOPPED') {
  if (!screenShareManager || !screenShareManager.isSharing) {
    return;
  }

  logDebug('Room', `Stopping local screen share (reason: ${reason})`);

  // Stop media tracks
  screenShareManager.stopCapture(reason);

  // Remove track from peer mesh & restore camera quality ladder
  peerMesh.removeScreenTrack();
  peerMesh.clearPresenter();

  // Send screen-share-stop to signaling server unless host already stopped us
  if (reason !== 'HOST_STOPPED') {
    signaling.send({
      type: 'screen-share-stop',
      roomCode: normalizedCode,
      payload: {
        reason: reason
      }
    });
  }

  activePresenter = null;
  videoGrid.clearPresenter();
  updateShareButtonState();
  announce('You stopped sharing your screen');
}

/**
 * Host authority action: stops a remote presenter's share.
 */
function requestHostStopScreenShare(targetPresenterUserId) {
  if (!isHost) return;
  logDebug('Room', `Host stopping presenter ${targetPresenterUserId}`);
  signaling.send({
    type: 'screen-share-stop',
    roomCode: normalizedCode,
    payload: {
      reason: 'HOST_STOPPED'
    }
  });
}

function updateParticipantCount() {
  const remoteCount = peerMesh ? peerMesh.peers.size : 0;
  const total = remoteCount + 1;
  participantCount.textContent = total === 1 ? '1 participant' : `${total} participants`;
}

function bindControls(roomCode, hostPrivileges) {
  // Mic Toggle
  btnToggleMic.addEventListener('click', toggleMic);

  // Camera Toggle
  btnToggleCam.addEventListener('click', toggleCamera);

  // Screen Share Toggle (button only, no keyboard shortcut)
  if (btnShareScreen) {
    btnShareScreen.addEventListener('click', () => {
      if (screenShareManager && screenShareManager.isSharing) {
        stopLocalScreenShare('USER_STOPPED');
      } else {
        startLocalScreenShare();
      }
    });
  }

  // Keyboard Shortcuts: M & V ONLY (No S shortcut per specification)
  window.addEventListener('keydown', (e) => {
    if (['INPUT', 'TEXTAREA'].includes(e.target.tagName)) return;

    if (e.key === 'm' || e.key === 'M') {
      e.preventDefault();
      toggleMic();
    } else if (e.key === 'v' || e.key === 'V') {
      e.preventDefault();
      toggleCamera();
    }
  });

  // Copy Code Button
  btnCopyCode.addEventListener('click', () => {
    navigator.clipboard.writeText(roomCode).then(() => {
      showToast('Meeting code copied to clipboard');
    });
  });

  // Leave Meeting Button
  btnLeave.addEventListener('click', async () => {
    try {
      if (!CONFIG.MOCK) {
        await apiRequest(`/rooms/${roomCode}/leave`, { method: 'POST' });
      }
    } catch (e) {}
    teardown();
    window.location.replace('ended.html?reason=You%20left%20the%20meeting');
  });

  // Host End Meeting for All Button
  if (hostPrivileges) {
    btnEndAll.addEventListener('click', async () => {
      if (!confirm('End meeting for everyone? All participants will be disconnected.')) {
        return;
      }
      try {
        if (!CONFIG.MOCK) {
          await apiRequest(`/rooms/${roomCode}/end`, { method: 'POST' });
        }
      } catch (e) {}
      teardown();
      window.location.replace('ended.html?reason=You%20ended%20the%20meeting');
    });
  }

  // Teardown triggers on tab close / navigation
  window.addEventListener('pagehide', teardown);
  window.addEventListener('beforeunload', teardown);
  window.addEventListener('popstate', teardown);
}

function toggleMic() {
  if (!mediaManager) return;
  const newMuted = mediaManager.setAudioMuted(!mediaManager.audioMuted);
  if (newMuted) {
    btnToggleMic.classList.add('btn-active-muted');
    btnToggleMic.innerHTML = ICON_MIC_OFF;
    videoGrid.setAudioMuteState('local', true);
  } else {
    btnToggleMic.classList.remove('btn-active-muted');
    btnToggleMic.innerHTML = ICON_MIC_ON;
    videoGrid.setAudioMuteState('local', false);
  }

  signaling.send({
    type: 'mute-status',
    roomCode: signaling.roomCode,
    payload: {
      audioMuted: newMuted,
      videoMuted: mediaManager.videoMuted
    }
  });
}

function toggleCamera() {
  if (!mediaManager) return;
  const newMuted = mediaManager.setVideoMuted(!mediaManager.videoMuted);
  if (newMuted) {
    btnToggleCam.classList.add('btn-active-muted');
    btnToggleCam.innerHTML = ICON_CAM_OFF;
    videoGrid.setCameraState('local', false, currentUser?.name || 'You');
  } else {
    btnToggleCam.classList.remove('btn-active-muted');
    btnToggleCam.innerHTML = ICON_CAM_ON;
    videoGrid.setCameraState('local', true, currentUser?.name || 'You');
  }

  signaling.send({
    type: 'mute-status',
    roomCode: signaling.roomCode,
    payload: {
      audioMuted: mediaManager.audioMuted,
      videoMuted: newMuted
    }
  });
}

async function acquireWakeLock() {
  try {
    if ('wakeLock' in navigator) {
      wakeLock = await navigator.wakeLock.request('screen');
      document.addEventListener('visibilitychange', async () => {
        if (wakeLock !== null && document.visibilityState === 'visible') {
          wakeLock = await navigator.wakeLock.request('screen');
        }
      });
    }
  } catch (err) {
    logDebug('Room', 'Screen Wake Lock could not be acquired', err.message);
  }
}

function announce(message) {
  if (ariaLiveEl) {
    ariaLiveEl.textContent = message;
  }
}

function showToast(message) {
  const toast = document.createElement('div');
  toast.className = 'room-alert-toast';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    if (toast.parentNode) toast.parentNode.removeChild(toast);
  }, 3200);
}

/**
 * Leak-free cleanup of every resource across all channels:
 * screen sharing track, media tracks, peer connections, listeners, wake lock.
 */
function teardown() {
  if (isTearingDown) return;
  isTearingDown = true;
  logDebug('Room', 'Executing full leak-free teardown');

  if (screenShareManager) {
    stopLocalScreenShare('USER_STOPPED');
    screenShareManager = null;
  }

  if (wakeLock) {
    try { wakeLock.release(); } catch (e) {}
    wakeLock = null;
  }

  if (mediaManager) {
    mediaManager.stopAll();
    mediaManager = null;
  }

  if (peerMesh) {
    peerMesh.closeAll();
    peerMesh = null;
  }

  if (signaling) {
    signaling.disconnect();
    signaling = null;
  }

  if (videoGrid) {
    videoGrid.clear();
    videoGrid = null;
  }

  if (chatManager) {
    chatManager.destroy();
    chatManager = null;
  }

  if (fileManager) {
    fileManager.destroy();
    fileManager = null;
  }

  if (whiteboardManager) {
    whiteboardManager.destroy();
    whiteboardManager = null;
  }
}

// Bootstrap room when DOM is ready
document.addEventListener('DOMContentLoaded', initRoom);
