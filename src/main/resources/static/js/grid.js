/**
 * MeetApp — Video Grid Manager (grid.js)
 * Manages responsive 1-5 video tiles, active speaker indicators,
 * muted status badges, camera-off avatar states, pinning, autoplay recovery,
 * and the large presenter stage with side/bottom thumbnail strip.
 */

import { logDebug } from './config.js';

export class VideoGridManager {
  constructor(gridContainer, options = {}) {
    this.container = gridContainer;
    this.stage = gridContainer.parentElement;
    this.callbacks = {
      onSelfStop: options.onSelfStop || (() => {}),
      onHostStop: options.onHostStop || (() => {})
    };

    this.tiles = new Map(); // userId -> TileObject
    this.pinnedUserId = null;
    this.autoplayBanner = null;
    this.audioBlocked = false;

    // Presenter stage elements
    this.presenterEl = null;
    this.presenterVideoEl = null;
    this.presenterBadgeText = null;
    this.presenterStopBtn = null;
    this.presenterFsBtn = null;
    this.presenterInfo = null;

    this._setupAutoplayBanner();
    this._setupPresenterStage();
  }

  /**
   * Adds or updates the local user's video tile.
   */
  setLocalStream(userId, userName, stream, hasCamera = true) {
    let tile = this.tiles.get('local');
    if (!tile) {
      tile = this._createTileElement('local', userName || 'You', true);
      this.tiles.set('local', tile);
      this.container.appendChild(tile.tileEl);
    }

    tile.stream = stream;
    tile.videoEl.srcObject = stream;
    tile.videoEl.muted = true; // Local audio must always be muted to prevent feedback loop

    this.setCameraState('local', hasCamera, userName || 'You');
    this._updateGridLayout();
  }

  /**
   * Adds or updates a remote peer's video tile.
   */
  setRemoteStream(userId, stream, userName) {
    const id = String(userId);
    let tile = this.tiles.get(id);

    if (!tile) {
      tile = this._createTileElement(id, userName || `Peer ${id}`, false);
      this.tiles.set(id, tile);
      this.container.appendChild(tile.tileEl);
    }

    tile.stream = stream;
    tile.videoEl.srcObject = stream;
    tile.videoEl.muted = false;

    // Attempt playback and check for browser autoplay blocking
    const playPromise = tile.videoEl.play();
    if (playPromise !== undefined) {
      playPromise.catch((err) => {
        logDebug('Grid', `Autoplay prevented for remote peer ${id}`, err.name);
        this._showAutoplayPrompt();
      });
    }

    this._updateGridLayout();
  }

  /**
   * Displays the active screen share stream on the large presenter stage.
   */
  setPresenterStream(userId, userName, stream, isSelf = false, isHost = false) {
    logDebug('Grid', `Displaying presenter stream (userId=${userId}, isSelf=${isSelf}, isHost=${isHost})`);
    this.presenterInfo = { userId, userName, isSelf, isHost };

    this.presenterVideoEl.srcObject = stream;
    this.presenterVideoEl.muted = isSelf; // Mute self to prevent feedback

    const displayName = isSelf ? 'You' : (userName || 'Participant');
    this.presenterBadgeText.textContent = `${displayName} is presenting`;

    if (isSelf) {
      this.presenterStopBtn.textContent = 'Stop sharing';
      this.presenterStopBtn.style.display = 'inline-flex';
    } else if (isHost) {
      this.presenterStopBtn.textContent = 'Stop share';
      this.presenterStopBtn.style.display = 'inline-flex';
    } else {
      this.presenterStopBtn.style.display = 'none';
    }

    this.presenterEl.style.display = 'flex';
    if (this.stage) {
      this.stage.classList.add('presenter-mode');
    }
    this.container.classList.add('camera-strip');

    this.presenterVideoEl.play().catch(() => {});
  }

  /**
   * Clears the presenter stage and restores the standard grid layout.
   */
  clearPresenter() {
    logDebug('Grid', 'Clearing presenter stage');
    if (document.fullscreenElement === this.presenterEl) {
      try {
        document.exitFullscreen();
      } catch (e) {}
    }

    this.presenterInfo = null;
    if (this.presenterVideoEl) {
      this.presenterVideoEl.srcObject = null;
    }
    if (this.presenterEl) {
      this.presenterEl.style.display = 'none';
    }
    if (this.stage) {
      this.stage.classList.remove('presenter-mode');
    }
    this.container.classList.remove('camera-strip');
    this._updateGridLayout();
  }

  /**
   * Toggles native browser fullscreen on the presenter stage.
   */
  toggleFullscreen() {
    if (!this.presenterEl) return;
    if (document.fullscreenElement) {
      if (document.exitFullscreen) {
        document.exitFullscreen().catch(() => {});
      }
    } else {
      if (this.presenterEl.requestFullscreen) {
        this.presenterEl.requestFullscreen().catch(() => {});
      }
    }
  }

  /**
   * Removes a participant's tile from the grid.
   */
  removeTile(userId) {
    const id = String(userId);
    const tile = this.tiles.get(id);
    if (!tile) return;

    if (tile.videoEl) {
      tile.videoEl.srcObject = null;
    }
    if (tile.tileEl && tile.tileEl.parentNode) {
      tile.tileEl.parentNode.removeChild(tile.tileEl);
    }

    this.tiles.delete(id);
    if (this.pinnedUserId === id) {
      this.pinnedUserId = null;
    }
    this._updateGridLayout();
  }

  /**
   * Updates speaking indicator (solid 2px outline and soundwaves icon).
   */
  setSpeakingState(userId, isSpeaking) {
    const id = String(userId);
    const tile = this.tiles.get(id);
    if (!tile) return;

    tile.isSpeaking = isSpeaking;
    if (isSpeaking && !tile.isMuted) {
      tile.tileEl.classList.add('speaking');
    } else {
      tile.tileEl.classList.remove('speaking');
    }
    this._updateTileStatus(tile);
  }

  /**
   * Updates microphone mute state (crossed-out mic icon and screen reader text).
   */
  setAudioMuteState(userId, isMuted) {
    const id = String(userId);
    const tile = this.tiles.get(id);
    if (!tile) return;

    tile.isMuted = isMuted;
    if (isMuted) {
      tile.tileEl.classList.remove('speaking');
    } else if (tile.isSpeaking) {
      tile.tileEl.classList.add('speaking');
    }
    this._updateTileStatus(tile);
  }

  _updateTileStatus(tile) {
    if (!tile.statusBadge || !tile.iconEl || !tile.srTextEl) return;

    if (tile.isMuted) {
      tile.statusBadge.className = 'tile-status-badge status-muted';
      tile.statusBadge.title = 'Microphone Muted';
      // Icon shape: Microphone crossed out with prominent diagonal slash
      tile.iconEl.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="1" y1="1" x2="23" y2="23"></line><path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"></path><path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"></path><line x1="12" y1="19" x2="12" y2="23"></line><line x1="8" y1="23" x2="16" y2="23"></line></svg>`;
      tile.srTextEl.textContent = 'Microphone muted';
    } else if (tile.isSpeaking) {
      tile.statusBadge.className = 'tile-status-badge status-speaking';
      tile.statusBadge.title = 'Speaking';
      // Icon shape: Distinct radiating audio soundwaves
      tile.iconEl.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M11 5L6 9H2v6h4l5 4V5z"></path><path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path><path d="M19.07 4.93a10 10 0 0 1 0 14.14"></path></svg>`;
      tile.srTextEl.textContent = 'Speaking';
    } else {
      tile.statusBadge.className = 'tile-status-badge status-idle';
      tile.statusBadge.title = 'Microphone On';
      // Icon shape: Standard microphone capsule
      tile.iconEl.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path><path d="M19 10v2a7 7 0 0 1-14 0v-2"></path><line x1="12" y1="19" x2="12" y2="23"></line><line x1="8" y1="23" x2="16" y2="23"></line></svg>`;
      tile.srTextEl.textContent = 'Microphone on';
    }
  }

  /**
   * Toggles between video stream and camera-off avatar.
   */
  setCameraState(userId, isCameraOn, userName) {
    const id = String(userId);
    const tile = this.tiles.get(id);
    if (!tile) return;

    if (isCameraOn) {
      tile.videoEl.style.display = 'block';
      tile.avatarEl.style.display = 'none';
    } else {
      tile.videoEl.style.display = 'none';
      tile.avatarEl.style.display = 'flex';
      tile.avatarEl.textContent = this._getInitials(userName);
    }
  }

  /**
   * Pin or unpin a participant tile to take focus.
   */
  togglePin(userId) {
    const id = String(userId);
    if (this.pinnedUserId === id) {
      this.pinnedUserId = null;
    } else {
      this.pinnedUserId = id;
    }
    this._updateGridLayout();
  }

  _setupPresenterStage() {
    const stage = document.createElement('div');
    stage.className = 'presenter-tile';
    stage.id = 'presenterStage';
    stage.style.display = 'none';

    const video = document.createElement('video');
    video.autoplay = true;
    video.playsInline = true;
    video.setAttribute('playsinline', '');

    const badge = document.createElement('div');
    badge.className = 'presenter-badge';

    const badgeIcon = document.createElement('span');
    badgeIcon.className = 'presenter-badge-icon';
    badgeIcon.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>`;

    const badgeText = document.createElement('span');
    badgeText.className = 'presenter-badge-text';
    badgeText.textContent = '';

    badge.appendChild(badgeIcon);
    badge.appendChild(badgeText);

    const actions = document.createElement('div');
    actions.className = 'presenter-actions';

    // Stop button
    const stopBtn = document.createElement('button');
    stopBtn.type = 'button';
    stopBtn.className = 'btn-presenter-stop';
    stopBtn.textContent = 'Stop sharing';
    stopBtn.style.display = 'none';

    // Fullscreen button
    const fsBtn = document.createElement('button');
    fsBtn.type = 'button';
    fsBtn.className = 'btn-presenter-fullscreen';
    fsBtn.title = 'Toggle Fullscreen';
    fsBtn.setAttribute('aria-label', 'Toggle Fullscreen');
    fsBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"></path></svg>`;

    actions.appendChild(stopBtn);
    actions.appendChild(fsBtn);

    stage.appendChild(video);
    stage.appendChild(badge);
    stage.appendChild(actions);

    if (this.stage) {
      this.stage.insertBefore(stage, this.container);
    }

    this.presenterEl = stage;
    this.presenterVideoEl = video;
    this.presenterBadgeText = badgeText;
    this.presenterStopBtn = stopBtn;
    this.presenterFsBtn = fsBtn;

    // Event listeners
    stopBtn.addEventListener('click', () => {
      if (this.presenterInfo?.isSelf) {
        this.callbacks.onSelfStop();
      } else if (this.presenterInfo?.isHost) {
        this.callbacks.onHostStop(this.presenterInfo.userId);
      }
    });

    fsBtn.addEventListener('click', () => {
      this.toggleFullscreen();
    });

    document.addEventListener('fullscreenchange', () => {
      const isFs = Boolean(document.fullscreenElement === this.presenterEl);
      fsBtn.title = isFs ? 'Exit Fullscreen' : 'Toggle Fullscreen';
      fsBtn.setAttribute('aria-label', fsBtn.title);
      fsBtn.innerHTML = isFs
        ? `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3"></path></svg>`
        : `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"></path></svg>`;
    });
  }

  _createTileElement(id, name, isLocal) {
    const tileEl = document.createElement('div');
    tileEl.className = 'video-tile' + (isLocal ? ' local-tile' : '');
    tileEl.dataset.userId = id;

    const videoEl = document.createElement('video');
    videoEl.autoplay = true;
    videoEl.playsInline = true;
    videoEl.setAttribute('playsinline', '');
    if (isLocal) {
      videoEl.muted = true;
    }

    const avatarEl = document.createElement('div');
    avatarEl.className = 'tile-avatar';
    avatarEl.style.display = 'none';
    avatarEl.textContent = this._getInitials(name);

    const overlay = document.createElement('div');
    overlay.className = 'tile-overlay';

    const nameBadge = document.createElement('span');
    nameBadge.className = 'tile-name-badge';
    nameBadge.textContent = isLocal ? 'You' : name;

    const statusBadge = document.createElement('div');
    statusBadge.className = 'tile-status-badge status-idle';

    const iconEl = document.createElement('span');
    iconEl.className = 'tile-status-icon';
    iconEl.setAttribute('aria-hidden', 'true');

    const srTextEl = document.createElement('span');
    srTextEl.className = 'sr-only';
    srTextEl.textContent = 'Microphone on';

    statusBadge.appendChild(iconEl);
    statusBadge.appendChild(srTextEl);

    overlay.appendChild(nameBadge);
    overlay.appendChild(statusBadge);

    tileEl.appendChild(videoEl);
    tileEl.appendChild(avatarEl);
    tileEl.appendChild(overlay);

    const tileObj = {
      tileEl,
      videoEl,
      avatarEl,
      nameBadge,
      statusBadge,
      iconEl,
      srTextEl,
      isMuted: false,
      isSpeaking: false,
      stream: null
    };

    this._updateTileStatus(tileObj);

    return tileObj;
  }

  _updateGridLayout() {
    const totalCount = this.tiles.size;
    this.container.classList.remove('count-1', 'count-2', 'count-3', 'count-4', 'count-5', 'has-pinned');

    if (this.stage && this.stage.classList.contains('presenter-mode')) {
      // In presenter mode, camera tiles are in the strip
      return;
    }

    if (this.pinnedUserId && this.tiles.has(this.pinnedUserId)) {
      this.container.classList.add('has-pinned');
      for (const [id, tile] of this.tiles.entries()) {
        if (id === this.pinnedUserId) {
          tile.tileEl.classList.add('is-pinned');
        } else {
          tile.tileEl.classList.remove('is-pinned');
        }
      }
      return;
    }

    // Unpinned grid classes
    for (const tile of this.tiles.values()) {
      tile.tileEl.classList.remove('is-pinned');
    }

    if (totalCount === 1) this.container.classList.add('count-1');
    else if (totalCount === 2) this.container.classList.add('count-2');
    else if (totalCount === 3) this.container.classList.add('count-3');
    else if (totalCount === 4) this.container.classList.add('count-4');
    else if (totalCount >= 5) this.container.classList.add('count-5');
  }

  _getInitials(name) {
    if (!name) return '?';
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.slice(0, 2).toUpperCase();
  }

  _setupAutoplayBanner() {
    const banner = document.createElement('div');
    banner.className = 'autoplay-prompt-banner';
    banner.style.display = 'none';

    const text = document.createElement('span');
    text.textContent = 'Audio is muted by your browser.';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn-enable-audio';
    btn.textContent = 'Click to enable audio';

    btn.addEventListener('click', () => {
      this._resumeAllAudio();
      banner.style.display = 'none';
      this.audioBlocked = false;
    });

    banner.appendChild(text);
    banner.appendChild(btn);
    document.body.appendChild(banner);
    this.autoplayBanner = banner;
  }

  _showAutoplayPrompt() {
    this.audioBlocked = true;
    if (this.autoplayBanner) {
      this.autoplayBanner.style.display = 'flex';
    }
  }

  _resumeAllAudio() {
    for (const tile of this.tiles.values()) {
      if (tile.videoEl && !tile.videoEl.muted) {
        tile.videoEl.play().catch(() => {});
      }
    }
  }

  clear() {
    this.clearPresenter();
    for (const tile of this.tiles.values()) {
      if (tile.videoEl) tile.videoEl.srcObject = null;
      if (tile.tileEl && tile.tileEl.parentNode) {
        tile.tileEl.parentNode.removeChild(tile.tileEl);
      }
    }
    this.tiles.clear();
    this.pinnedUserId = null;
    if (this.autoplayBanner && this.autoplayBanner.parentNode) {
      this.autoplayBanner.parentNode.removeChild(this.autoplayBanner);
    }
    if (this.presenterEl && this.presenterEl.parentNode) {
      this.presenterEl.parentNode.removeChild(this.presenterEl);
    }
  }
}
