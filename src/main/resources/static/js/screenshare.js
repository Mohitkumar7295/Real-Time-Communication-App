/**
 * MeetApp — Screen Sharing Manager (screenshare.js)
 * Manages display media acquisition via getDisplayMedia,
 * track constraints (contentHint = 'detail', 1920x1080 @ 10-15 fps, audio: false),
 * browser stop bar detection (track.onended), user cancellation handling,
 * and complete track disposal.
 */

import { CONFIG, logDebug } from './config.js';

export class ScreenShareManager {
  /**
   * Feature detection: checks if getDisplayMedia is supported.
   */
  static isSupported() {
    return Boolean(
      typeof navigator !== 'undefined' &&
      navigator.mediaDevices &&
      typeof navigator.mediaDevices.getDisplayMedia === 'function'
    );
  }

  constructor(callbacks = {}) {
    this.callbacks = {
      onEnded: callbacks.onEnded || (() => {}),
      onError: callbacks.onError || (() => {})
    };

    this.stream = null;
    this.track = null;
    this.streamId = null;
    this.isSharing = false;
  }

  /**
   * Prompts the user with the native browser screen picker and acquires display media.
   * Returns { stream, track, streamId } on success, or null if cancelled / failed.
   */
  async startCapture() {
    if (!ScreenShareManager.isSupported() && !CONFIG.MOCK) {
      const err = new Error('Screen sharing is not available in this browser');
      this.callbacks.onError(err);
      return null;
    }

    if (CONFIG.MOCK) {
      return this._startMockCapture();
    }

    try {
      // Constraints: crisp text rendering, capped framerate (10-15 fps), audio: false
      const displayMediaOptions = {
        video: {
          width: { ideal: 1920, max: 1920 },
          height: { ideal: 1080, max: 1080 },
          frameRate: { ideal: 10, max: 15 }
        },
        audio: false
      };

      this.stream = await navigator.mediaDevices.getDisplayMedia(displayMediaOptions);
    } catch (err) {
      // User cancelled picker (AbortError in Chrome/Edge, NotAllowedError in Firefox/Safari)
      if (err.name === 'AbortError' || err.name === 'NotAllowedError') {
        logDebug('ScreenShare', 'User cancelled screen picker (not an error)');
        return null;
      }
      logDebug('ScreenShare', 'Failed to acquire display media', err.message);
      this.callbacks.onError(err);
      return null;
    }

    const videoTracks = this.stream.getVideoTracks();
    if (videoTracks.length === 0) {
      logDebug('ScreenShare', 'No video track found in display media stream');
      this.stopCapture('USER_STOPPED');
      return null;
    }

    this.track = videoTracks[0];

    // Optimize track for high-legibility presentation of text and slides
    if ('contentHint' in this.track) {
      this.track.contentHint = 'detail';
    }

    this.streamId = this.stream.id || `scr-${Date.now()}`;
    this.isSharing = true;

    // Detect when the user clicks the browser's native floating "Stop sharing" bar
    this.track.onended = () => {
      logDebug('ScreenShare', 'Screen sharing track ended via browser UI');
      this.stopCapture('TRACK_ENDED');
    };

    return {
      stream: this.stream,
      track: this.track,
      streamId: this.streamId
    };
  }

  /**
   * Stops local display media capture and disposes tracks cleanly.
   */
  stopCapture(reason = 'USER_STOPPED') {
    if (!this.isSharing && !this.stream && !this.track) {
      return;
    }

    logDebug('ScreenShare', `Stopping screen capture (reason: ${reason})`);
    this.isSharing = false;

    if (this.track) {
      this.track.onended = null;
      try {
        this.track.stop();
      } catch (e) {
        // Ignore during track stop
      }
      this.track = null;
    }

    if (this.stream) {
      this.stream.getTracks().forEach((t) => {
        try {
          t.stop();
        } catch (e) {}
      });
      this.stream = null;
    }

    const endedStreamId = this.streamId;
    this.streamId = null;

    this.callbacks.onEnded(reason, endedStreamId);
  }

  /**
   * Mock screen share for automated testing and demo environments without display hardware.
   */
  _startMockCapture() {
    logDebug('ScreenShare', 'Acquiring mock canvas display stream');
    const canvas = document.createElement('canvas');
    canvas.width = 1280;
    canvas.height = 720;
    const ctx = canvas.getContext('2d');

    let frame = 0;
    const drawMock = () => {
      if (!this.isSharing) return;
      frame++;
      ctx.fillStyle = '#1B1A17';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.strokeStyle = '#1F6F5B';
      ctx.lineWidth = 4;
      ctx.strokeRect(40, 40, canvas.width - 80, canvas.height - 80);

      ctx.fillStyle = '#F6F4EF';
      ctx.font = 'bold 36px sans-serif';
      ctx.fillText('MeetApp Screen Presentation (Demo)', 80, 140);

      ctx.fillStyle = '#9E9A90';
      ctx.font = '24px sans-serif';
      ctx.fillText(`Simulated presentation feed — Frame #${frame}`, 80, 200);
      requestAnimationFrame(drawMock);
    };

    this.stream = canvas.captureStream(10);
    this.track = this.stream.getVideoTracks()[0];
    if (this.track && 'contentHint' in this.track) {
      this.track.contentHint = 'detail';
    }

    this.streamId = `mock-scr-${Date.now()}`;
    this.isSharing = true;
    drawMock();

    this.track.onended = () => {
      this.stopCapture('TRACK_ENDED');
    };

    return {
      stream: this.stream,
      track: this.track,
      streamId: this.streamId
    };
  }
}
