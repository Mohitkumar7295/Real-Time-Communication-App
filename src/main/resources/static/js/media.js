/**
 * MeetApp — Media Stream Manager (media.js)
 * Manages getUserMedia stream acquisition, track muting, device changes,
 * Web Audio API speaking volume detection, and complete resource disposal.
 */

import { CONFIG, logDebug } from './config.js';

export class MediaManager {
  constructor(callbacks = {}) {
    this.callbacks = {
      onLocalStream: callbacks.onLocalStream || (() => {}),
      onSpeakingVolume: callbacks.onSpeakingVolume || (() => {}),
      onDeviceChange: callbacks.onDeviceChange || (() => {}),
      onTrackEnded: callbacks.onTrackEnded || (() => {}),
      onHardwareError: callbacks.onHardwareError || (() => {})
    };

    this.localStream = null;
    this.audioTrack = null;
    this.videoTrack = null;

    this.audioContext = null;
    this.analyser = null;
    this.meterSource = null;
    this.meterAnimationId = null;

    this.audioMuted = false;
    this.videoMuted = false;
    this.hasCamera = false;
    this.hasMicrophone = false;

    this._deviceChangeHandler = () => this._handleDeviceChange();
    if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
      navigator.mediaDevices.addEventListener('devicechange', this._deviceChangeHandler);
    }
  }

  /**
   * Initializes local media streams with requested preferences.
   * Gracefully degrades to audio-only, video-only, or view/listen-only.
   */
  async initLocalMedia(preferAudio = true, preferVideo = true) {
    if (CONFIG.MOCK) {
      return this._initMockStream();
    }

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      logDebug('Media', 'getUserMedia API not supported in this browser');
      this.callbacks.onHardwareError(new Error('Camera/Microphone access is not supported by your browser'));
      return null;
    }

    const audioConstraints = preferAudio ? {
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    } : false;

    const videoConstraints = preferVideo ? {
      width: { ideal: 640, max: 640 },
      height: { ideal: 360, max: 360 },
      frameRate: { ideal: 24, max: 24 },
      facingMode: 'user'
    } : false;

    try {
      // 1. Attempt both audio and video
      this.localStream = await navigator.mediaDevices.getUserMedia({
        audio: audioConstraints,
        video: videoConstraints
      });
      this.hasMicrophone = preferAudio;
      this.hasCamera = preferVideo;
    } catch (err) {
      logDebug('Media', 'Full media acquisition failed, attempting fallback', err.name);

      // 2. Fallback: try audio-only if video failed
      if (preferAudio && preferVideo) {
        try {
          this.localStream = await navigator.mediaDevices.getUserMedia({
            audio: audioConstraints,
            video: false
          });
          this.hasMicrophone = true;
          this.hasCamera = false;
          this.callbacks.onHardwareError(new Error('Camera unavailable or permission denied. Joined with microphone only.'));
        } catch (audioErr) {
          // 3. Fallback: try video-only if audio also failed
          try {
            this.localStream = await navigator.mediaDevices.getUserMedia({
              audio: false,
              video: videoConstraints
            });
            this.hasMicrophone = false;
            this.hasCamera = true;
            this.callbacks.onHardwareError(new Error('Microphone unavailable or permission denied. Joined with camera only.'));
          } catch (videoErr) {
            // 4. View/Listen-only mode
            this.localStream = new MediaStream();
            this.hasMicrophone = false;
            this.hasCamera = false;
            this.callbacks.onHardwareError(new Error('Joined in view-only mode (camera and microphone unavailable).'));
          }
        }
      } else {
        this.localStream = new MediaStream();
        this.hasMicrophone = false;
        this.hasCamera = false;
        this.callbacks.onHardwareError(new Error('Joined in view-only mode.'));
      }
    }

    this._setupTracks();
    this.callbacks.onLocalStream(this.localStream, {
      hasCamera: this.hasCamera,
      hasMicrophone: this.hasMicrophone
    });
    return this.localStream;
  }

  _setupTracks() {
    if (!this.localStream) return;

    const audioTracks = this.localStream.getAudioTracks();
    if (audioTracks.length > 0) {
      this.audioTrack = audioTracks[0];
      this.audioTrack.onended = () => {
        logDebug('Media', 'Microphone track ended');
        this.hasMicrophone = false;
        this.callbacks.onTrackEnded('audio');
      };
      this._startAudioMeter();
    } else {
      this.audioTrack = null;
    }

    const videoTracks = this.localStream.getVideoTracks();
    if (videoTracks.length > 0) {
      this.videoTrack = videoTracks[0];
      this.videoTrack.onended = () => {
        logDebug('Media', 'Camera track ended');
        this.hasCamera = false;
        this.callbacks.onTrackEnded('video');
      };
    } else {
      this.videoTrack = null;
    }
  }

  /**
   * Toggles microphone audio track.
   */
  setAudioMuted(muted) {
    this.audioMuted = muted;
    if (this.audioTrack) {
      this.audioTrack.enabled = !muted;
    }
    return this.audioMuted;
  }

  /**
   * Toggles camera video track.
   */
  setVideoMuted(muted) {
    this.videoMuted = muted;
    if (this.videoTrack) {
      this.videoTrack.enabled = !muted;
    }
    return this.videoMuted;
  }

  /**
   * Web Audio API live volume analysis for speaking indicator.
   */
  _startAudioMeter() {
    if (!this.localStream || this.localStream.getAudioTracks().length === 0) return;

    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;

      this.audioContext = new AudioCtx();
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 256;
      this.analyser.smoothingTimeConstant = 0.4;

      this.meterSource = this.audioContext.createMediaStreamSource(this.localStream);
      this.meterSource.connect(this.analyser);

      const buffer = new Uint8Array(this.analyser.frequencyBinCount);

      const sample = () => {
        if (!this.analyser) return;

        if (this.audioMuted || !this.audioTrack || !this.audioTrack.enabled) {
          this.callbacks.onSpeakingVolume(0, false);
          this.meterAnimationId = requestAnimationFrame(sample);
          return;
        }

        this.analyser.getByteFrequencyData(buffer);
        let sum = 0;
        for (let i = 0; i < buffer.length; i++) {
          sum += buffer[i];
        }
        const average = sum / buffer.length;
        // Normalize roughly to 0-100 scale
        const volume = Math.min(100, Math.round((average / 128) * 100));
        const isSpeaking = volume > 15;

        this.callbacks.onSpeakingVolume(volume, isSpeaking);
        this.meterAnimationId = requestAnimationFrame(sample);
      };

      this.meterAnimationId = requestAnimationFrame(sample);
    } catch (e) {
      logDebug('Media', 'Web Audio API analysis not available', e.message);
    }
  }

  _stopAudioMeter() {
    if (this.meterAnimationId) {
      cancelAnimationFrame(this.meterAnimationId);
      this.meterAnimationId = null;
    }
    if (this.meterSource) {
      try { this.meterSource.disconnect(); } catch (e) {}
      this.meterSource = null;
    }
    if (this.analyser) {
      try { this.analyser.disconnect(); } catch (e) {}
      this.analyser = null;
    }
    if (this.audioContext) {
      try {
        if (this.audioContext.state !== 'closed') {
          this.audioContext.close();
        }
      } catch (e) {}
      this.audioContext = null;
    }
  }

  _handleDeviceChange() {
    logDebug('Media', 'Hardware device change detected');
    this.callbacks.onDeviceChange();
  }

  /**
   * Complete teardown of all media resources and listeners.
   */
  stopAll() {
    logDebug('Media', 'Stopping all local media tracks and closing AudioContext');
    this._stopAudioMeter();

    if (navigator.mediaDevices && navigator.mediaDevices.removeEventListener) {
      navigator.mediaDevices.removeEventListener('devicechange', this._deviceChangeHandler);
    }

    if (this.localStream) {
      this.localStream.getTracks().forEach((track) => {
        try {
          track.stop();
        } catch (e) {}
      });
      this.localStream = null;
    }

    this.audioTrack = null;
    this.videoTrack = null;
  }

  // --- Mock Mode Stream Generator ---

  _initMockStream() {
    logDebug('Media', 'Generating simulated canvas/oscillator media stream for MOCK mode');
    const canvas = document.createElement('canvas');
    canvas.width = 640;
    canvas.height = 360;
    const ctx = canvas.getContext('2d');

    let frame = 0;
    const draw = () => {
      frame++;
      ctx.fillStyle = '#1f2937';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      ctx.fillStyle = '#10b981';
      ctx.beginPath();
      ctx.arc(320 + Math.sin(frame / 20) * 100, 180, 40, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = '#ffffff';
      ctx.font = '20px sans-serif';
      ctx.fillText('MeetApp Mock Stream', 220, 185);

      if (this.localStream) {
        requestAnimationFrame(draw);
      }
    };
    draw();

    const mockStream = canvas.captureStream(30);

    // Audio oscillator
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (AudioCtx) {
        const actx = new AudioCtx();
        const osc = actx.createOscillator();
        const dst = actx.createMediaStreamDestination();
        osc.connect(dst);
        osc.start();
        dst.stream.getAudioTracks().forEach(t => mockStream.addTrack(t));
      }
    } catch (e) {}

    this.localStream = mockStream;
    this.hasCamera = true;
    this.hasMicrophone = true;
    this._setupTracks();
    this.callbacks.onLocalStream(this.localStream, { hasCamera: true, hasMicrophone: true });
    return this.localStream;
  }
}
