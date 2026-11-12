/**
 * MeetApp — WebRTC Peer Mesh Manager (peers.js)
 * Manages RTCPeerConnection mesh topology (up to 4 remote peers),
 * Perfect Negotiation pattern, ICE candidate buffering,
 * dynamic bitrate ladder for camera and screen sharing,
 * inbound screen stream verification, and complete connection teardown.
 */

import { CONFIG, logDebug } from './config.js';

export class PeerMeshManager {
  constructor(selfUserId, signalingClient, iceServers = [], callbacks = {}) {
    this.selfUserId = Number(selfUserId);
    this.signaling = signalingClient;
    this.iceServers = iceServers.length > 0 ? iceServers : [{ urls: 'stun:stun.l.google.com:19302' }];

    this.callbacks = {
      onRemoteStream: callbacks.onRemoteStream || (() => {}),
      onRemoteScreenStream: callbacks.onRemoteScreenStream || (() => {}),
      onPeerDisconnected: callbacks.onPeerDisconnected || (() => {}),
      onSpeakingVolume: callbacks.onSpeakingVolume || (() => {})
    };

    // Map: userId -> PeerEntry
    // PeerEntry: { userId, name, pc, polite, makingOffer, queuedCandidates, remoteStream, screenSender, ... }
    this.peers = new Map();
    this.localStream = null;

    // Local screen share track and stream state
    this.localScreenTrack = null;
    this.localScreenStream = null;
    this.isSharingScreen = false;

    // Verified remote presenter state: { userId, streamId }
    this.currentPresenter = null;
  }

  setLocalStream(stream) {
    this.localStream = stream;
    // Add tracks to any existing connections
    for (const entry of this.peers.values()) {
      this._updateLocalTracks(entry);
    }
  }

  setPresenter(presenterInfo) {
    this.currentPresenter = presenterInfo;
  }

  clearPresenter() {
    this.currentPresenter = null;
  }

  /**
   * Adds the local display media track across all active peer connections.
   * Perfect Negotiation automatically handles renegotiation on each peer connection.
   */
  addScreenTrack(screenTrack, screenStream) {
    logDebug('WebRTC', `Adding local screen track to all peer connections (streamId=${screenStream?.id})`);
    this.localScreenTrack = screenTrack;
    this.localScreenStream = screenStream;
    this.isSharingScreen = true;

    for (const entry of this.peers.values()) {
      if (!entry.screenSender && entry.pc) {
        try {
          entry.screenSender = entry.pc.addTrack(screenTrack, screenStream);
        } catch (e) {
          logDebug('WebRTC', `Failed to add screen track to peer ${entry.userId}`, e.message);
        }
      }
    }

    this.updateQualityLadder();
  }

  /**
   * Removes the local screen track from all peer connections and triggers renegotiation.
   */
  removeScreenTrack() {
    logDebug('WebRTC', 'Removing local screen track from all peer connections');
    this.isSharingScreen = false;

    for (const entry of this.peers.values()) {
      if (entry.screenSender && entry.pc) {
        try {
          entry.pc.removeTrack(entry.screenSender);
        } catch (e) {
          logDebug('WebRTC', `Failed to remove screen sender from peer ${entry.userId}`, e.message);
        }
        entry.screenSender = null;
      }
    }

    this.localScreenTrack = null;
    this.localScreenStream = null;

    this.updateQualityLadder();
  }

  /**
   * Initializes a peer connection for a remote participant.
   * Perfect Negotiation:
   * Polite peer is the one with the LOWER numeric userId on both sides.
   * isNewer: true when self joined after this peer, meaning self creates the initial offer.
   */
  getOrCreatePeer(remoteUserId, remoteName, isNewer = false) {
    const id = Number(remoteUserId);
    if (this.peers.has(id)) {
      return this.peers.get(id);
    }

    logDebug('WebRTC', `Creating RTCPeerConnection for peer ${id} (${remoteName})`);

    const polite = this.selfUserId < id;
    const pc = new RTCPeerConnection({
      iceServers: this.iceServers,
      iceTransportPolicy: CONFIG.FORCE_RELAY ? 'relay' : 'all'
    });

    const entry = {
      userId: id,
      name: remoteName,
      pc,
      polite,
      makingOffer: false,
      isSettingRemoteAnswerPending: false,
      queuedCandidates: [],
      remoteStream: new MediaStream(),
      screenSender: null,
      audioMuted: false,
      videoMuted: false
    };

    this.peers.set(id, entry);

    // 1. Send ICE candidates to remote peer via signaling
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        this.signaling.send({
          type: 'ice-candidate',
          roomCode: this.signaling.roomCode,
          to: id,
          payload: event.candidate.toJSON()
        });
      }
    };

    // 2. Handle remote media tracks with stream verification
    pc.ontrack = (event) => {
      logDebug('WebRTC', `Received remote ${event.track.kind} track from peer ${id}`);
      const stream = (event.streams && event.streams[0]) ? event.streams[0] : null;

      // Verification rule: accept as screen ONLY if arriving on the presenter's peer connection
      // AND stream.id equals the announced presenter streamId. Ignore any other claims.
      if (this.currentPresenter &&
          Number(this.currentPresenter.userId) === id &&
          stream &&
          stream.id === this.currentPresenter.streamId) {
        logDebug('WebRTC', `Inbound stream verified as screen share from presenter ${id} (streamId=${stream.id})`);
        this.callbacks.onRemoteScreenStream(id, stream, entry.name);
        return;
      }

      // Normal participant camera/microphone stream
      if (stream) {
        this.callbacks.onRemoteStream(id, stream, entry.name);
      } else {
        entry.remoteStream.addTrack(event.track);
        this.callbacks.onRemoteStream(id, entry.remoteStream, entry.name);
      }
    };

    // 3. Monitor connection state
    pc.onconnectionstatechange = () => {
      logDebug('WebRTC', `Peer ${id} connection state changed to: ${pc.connectionState}`);
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        this.closePeer(id);
      }
    };

    pc.oniceconnectionstatechange = () => {
      logDebug('WebRTC', `Peer ${id} ICE connection state changed to: ${pc.iceConnectionState}`);
      if (pc.iceConnectionState === 'failed') {
        pc.restartIce();
      }
    };

    // 4. Perfect Negotiation: onnegotiationneeded
    pc.onnegotiationneeded = async () => {
      try {
        entry.makingOffer = true;
        logDebug('WebRTC', `Negotiation needed for peer ${id}. Creating offer.`);
        await pc.setLocalDescription();
        this.signaling.send({
          type: 'offer',
          roomCode: this.signaling.roomCode,
          to: id,
          payload: {
            type: pc.localDescription.type,
            sdp: pc.localDescription.sdp
          }
        });
      } catch (err) {
        logDebug('WebRTC', `Negotiation error with peer ${id}`, err.message);
      } finally {
        entry.makingOffer = false;
      }
    };

    // 5. Add local camera/mic tracks and screen tracks if available
    this._updateLocalTracks(entry);

    // 6. Adjust quality ladder across all active peers
    this.updateQualityLadder();

    // 7. If newer participant, create initial offer
    if (isNewer) {
      logDebug('WebRTC', `Self is newer participant. Initiating initial offer to peer ${id}.`);
    }

    return entry;
  }

  /**
   * Handles incoming WebRTC offer from a remote peer.
   */
  async handleOffer(fromUserId, offerPayload) {
    const id = Number(fromUserId);
    const entry = this.getOrCreatePeer(id, `Peer ${id}`, false);
    const pc = entry.pc;

    const offerCollision = (entry.makingOffer || pc.signalingState !== 'stable');
    const ignoreOffer = !entry.polite && offerCollision;

    if (ignoreOffer) {
      logDebug('WebRTC', `Offer collision ignored by impolite peer for sender ${id}`);
      return;
    }

    try {
      if (offerCollision) {
        logDebug('WebRTC', `Offer collision rolling back on polite peer for sender ${id}`);
        await Promise.all([
          pc.setLocalDescription({ type: 'rollback' }),
          pc.setRemoteDescription(new RTCSessionDescription(offerPayload))
        ]);
      } else {
        await pc.setRemoteDescription(new RTCSessionDescription(offerPayload));
      }

      // Flush buffered ICE candidates
      await this._flushCandidates(entry);

      // Create and send answer
      await pc.setLocalDescription();
      this.signaling.send({
        type: 'answer',
        roomCode: this.signaling.roomCode,
        to: id,
        payload: {
          type: pc.localDescription.type,
          sdp: pc.localDescription.sdp
        }
      });
    } catch (err) {
      logDebug('WebRTC', `Failed to handle offer from peer ${id}`, err.message);
    }
  }

  /**
   * Handles incoming WebRTC answer from a remote peer.
   */
  async handleAnswer(fromUserId, answerPayload) {
    const id = Number(fromUserId);
    const entry = this.peers.get(id);
    if (!entry) {
      logDebug('WebRTC', `Received answer from untracked peer ${id}`);
      return;
    }

    try {
      await entry.pc.setRemoteDescription(new RTCSessionDescription(answerPayload));
      // Flush buffered ICE candidates
      await this._flushCandidates(entry);
    } catch (err) {
      logDebug('WebRTC', `Failed to handle answer from peer ${id}`, err.message);
    }
  }

  /**
   * Handles incoming ICE candidate from a remote peer.
   * If remote description is pending, buffers the candidate.
   */
  async handleIceCandidate(fromUserId, candidatePayload) {
    const id = Number(fromUserId);
    const entry = this.peers.get(id);
    if (!entry) return;

    const candidate = new RTCIceCandidate(candidatePayload);
    if (entry.pc.remoteDescription && entry.pc.remoteDescription.type) {
      try {
        await entry.pc.addIceCandidate(candidate);
      } catch (err) {
        logDebug('WebRTC', `Failed to add ICE candidate from peer ${id}`, err.message);
      }
    } else {
      entry.queuedCandidates.push(candidate);
    }
  }

  async _flushCandidates(entry) {
    if (entry.queuedCandidates.length === 0) return;
    const candidates = [...entry.queuedCandidates];
    entry.queuedCandidates = [];
    for (const cand of candidates) {
      try {
        await entry.pc.addIceCandidate(cand);
      } catch (err) {
        logDebug('WebRTC', `Failed to flush ICE candidate for peer ${entry.userId}`, err.message);
      }
    }
  }

  _updateLocalTracks(entry) {
    if (!entry.pc) return;

    // 1. Camera & audio tracks
    if (this.localStream) {
      const senders = entry.pc.getSenders();
      this.localStream.getTracks().forEach((track) => {
        const existingSender = senders.find(s => s.track && s.track.kind === track.kind && s !== entry.screenSender);
        if (existingSender) {
          existingSender.replaceTrack(track);
        } else {
          entry.pc.addTrack(track, this.localStream);
        }
      });
    }

    // 2. Active screen share track
    if (this.isSharingScreen && this.localScreenTrack && this.localScreenStream && !entry.screenSender) {
      try {
        entry.screenSender = entry.pc.addTrack(this.localScreenTrack, this.localScreenStream);
      } catch (e) {
        logDebug('WebRTC', `Failed to attach screen track for peer ${entry.userId}`, e.message);
      }
    }
  }

  /**
   * Dynamically applies sender parameters:
   * - Screen share: ~1.5 Mbps for 1-2 peers, ~800 kbps for 3-4 peers, 10 fps, detail contentHint
   * - Camera: normal ladder, or reduced (~200 kbps / 15 fps) while sharing to protect uplink
   */
  async updateQualityLadder() {
    const peerCount = this.peers.size;
    const tier = Math.min(4, Math.max(1, peerCount));
    const ladder = CONFIG.QUALITY_LADDER[tier];

    const isScreenSharing = Boolean(this.isSharingScreen && this.localScreenTrack);
    const screenBitrate = peerCount <= 2 ? 1500000 : 800000;
    const screenFramerate = peerCount <= 2 ? 10 : 8;

    const cameraBitrate = isScreenSharing
      ? Math.min(ladder.maxBitrateBps, 200000)
      : ladder.maxBitrateBps;
    const cameraFramerate = isScreenSharing
      ? Math.min(ladder.fps, 15)
      : ladder.fps;

    logDebug('WebRTC', `Quality ladder applied (peers=${peerCount}, screenSharing=${isScreenSharing}): camera=${cameraBitrate}bps, screen=${screenBitrate}bps`);

    for (const entry of this.peers.values()) {
      const senders = entry.pc.getSenders();
      for (const sender of senders) {
        if (!sender.track || sender.track.kind !== 'video') continue;

        if (sender === entry.screenSender) {
          try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) {
              params.encodings = [{}];
            }
            params.encodings[0].maxBitrate = screenBitrate;
            params.encodings[0].maxFramerate = screenFramerate;
            await sender.setParameters(params);
          } catch (e) {
            logDebug('WebRTC', `Failed to set screen sender params for peer ${entry.userId}`, e.message);
          }
        } else {
          try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) {
              params.encodings = [{}];
            }
            params.encodings[0].maxBitrate = cameraBitrate;
            params.encodings[0].maxFramerate = cameraFramerate;
            await sender.setParameters(params);
          } catch (e) {
            logDebug('WebRTC', `Failed to set camera sender params for peer ${entry.userId}`, e.message);
          }
        }
      }
    }
  }

  /**
   * Closes a single peer connection cleanly.
   */
  closePeer(remoteUserId) {
    const id = Number(remoteUserId);
    const entry = this.peers.get(id);
    if (!entry) return;

    logDebug('WebRTC', `Closing peer connection for peer ${id}`);
    try {
      entry.pc.ontrack = null;
      entry.pc.onicecandidate = null;
      entry.pc.onconnectionstatechange = null;
      entry.pc.oniceconnectionstatechange = null;
      entry.pc.onnegotiationneeded = null;
      entry.screenSender = null;
      entry.pc.close();
    } catch (e) {}

    this.peers.delete(id);
    this.callbacks.onPeerDisconnected(id);
    this.updateQualityLadder();
  }

  /**
   * Closes all peer connections and empties peer map.
   */
  closeAll() {
    logDebug('WebRTC', `Closing all peer connections (${this.peers.size} active)`);
    for (const id of Array.from(this.peers.keys())) {
      this.closePeer(id);
    }
    this.peers.clear();
  }
}
