/**
 * MeetApp — Device Check & Hardware Preview (devicecheck.js)
 * Tests camera and microphone strictly on user trigger, with guaranteed track cleanup.
 */

export class DeviceChecker {
  constructor(dialogEl, videoEl, meterFillEl, statusEl) {
    this.dialog = dialogEl;
    this.videoEl = videoEl;
    this.meterFillEl = meterFillEl;
    this.statusEl = statusEl;

    this.stream = null;
    this.audioContext = null;
    this.analyser = null;
    this.animFrameId = null;

    if (this.dialog) {
      this.dialog.addEventListener('close', () => this.stop());
    }
  }

  async openAndStart() {
    if (!this.dialog) return;
    this.dialog.showModal();
    if (this.statusEl) {
      this.statusEl.textContent = 'Requesting camera and microphone access...';
    }

    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 360 } },
        audio: true
      });

      if (this.videoEl) {
        this.videoEl.srcObject = this.stream;
        await this.videoEl.play().catch(() => {});
      }

      this._initAudioMeter();
      if (this.statusEl) {
        this.statusEl.textContent = 'Hardware active. Speak into your microphone to verify volume.';
      }
    } catch (err) {
      console.warn('Device permission rejected or hardware unavailable', err);
      if (this.statusEl) {
        this.statusEl.textContent = 'Permission denied or media hardware not found: ' + err.message;
      }
    }
  }

  _initAudioMeter() {
    if (!this.stream) return;
    const audioTrack = this.stream.getAudioTracks()[0];
    if (!audioTrack) return;

    try {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) return;

      this.audioContext = new AudioContextClass();
      const source = this.audioContext.createMediaStreamSource(this.stream);
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 64;
      source.connect(this.analyser);

      const bufferLength = this.analyser.frequencyBinCount;
      const dataArray = new Uint8Array(bufferLength);

      const updateMeter = () => {
        if (!this.analyser || !this.meterFillEl) return;
        this.analyser.getByteFrequencyData(dataArray);

        let sum = 0;
        for (let i = 0; i < bufferLength; i++) {
          sum += dataArray[i];
        }
        const avg = sum / bufferLength;
        const percent = Math.min(100, Math.round((avg / 128) * 100));

        this.meterFillEl.style.width = `${percent}%`;
        this.animFrameId = requestAnimationFrame(updateMeter);
      };

      updateMeter();
    } catch (err) {
      console.warn('Could not initialize audio visualizer', err);
    }
  }

  stop() {
    if (this.animFrameId) {
      cancelAnimationFrame(this.animFrameId);
      this.animFrameId = null;
    }
    if (this.stream) {
      this.stream.getTracks().forEach(track => {
        try {
          track.stop();
        } catch {}
      });
      this.stream = null;
    }
    if (this.videoEl) {
      this.videoEl.srcObject = null;
    }
    if (this.audioContext) {
      try {
        this.audioContext.close();
      } catch {}
      this.audioContext = null;
    }
    if (this.meterFillEl) {
      this.meterFillEl.style.width = '0%';
    }
  }
}
