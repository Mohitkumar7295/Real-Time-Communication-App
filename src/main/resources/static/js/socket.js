/**
 * MeetApp — WebSocket Signaling Client (socket.js)
 * Manages WebSocket lifecycle, single-use ticket acquisition, heartbeat,
 * reconnection with exponential backoff and jitter, and message routing.
 */

import { CONFIG, logDebug } from './config.js';
import { apiRequest } from './api.js';

export class SignalingClient {
  constructor(roomCode, callbacks = {}) {
    this.roomCode = roomCode;
    this.callbacks = {
      onOpen: callbacks.onOpen || (() => {}),
      onClose: callbacks.onClose || (() => {}),
      onError: callbacks.onError || (() => {}),
      onJoinAck: callbacks.onJoinAck || (() => {}),
      onPeerJoined: callbacks.onPeerJoined || (() => {}),
      onPeerLeft: callbacks.onPeerLeft || (() => {}),
      onOffer: callbacks.onOffer || (() => {}),
      onAnswer: callbacks.onAnswer || (() => {}),
      onIceCandidate: callbacks.onIceCandidate || (() => {}),
      onMuteStatus: callbacks.onMuteStatus || (() => {}),
      onScreenShareStart: callbacks.onScreenShareStart || (() => {}),
      onScreenShareStop: callbacks.onScreenShareStop || (() => {}),
      onRoomEnded: callbacks.onRoomEnded || (() => {}),
      onReplaced: callbacks.onReplaced || (() => {}),
      onReconnect: callbacks.onReconnect || (() => {}),
      onSignalError: callbacks.onSignalError || (() => {}),
      onChat: callbacks.onChat || (() => {}),
      onFileShared: callbacks.onFileShared || (() => {}),
      onFileDeleted: callbacks.onFileDeleted || (() => {}),
      onDraw: callbacks.onDraw || (() => {}),
      onStrokeUndo: callbacks.onStrokeUndo || (() => {}),
      onClearBoard: callbacks.onClearBoard || (() => {}),
      onBoardState: callbacks.onBoardState || (() => {})
    };

    this.ws = null;
    this.selfUserId = null;
    this.selfName = null;
    this.reconnectAttempts = 0;
    this.hasConnectedOnce = false;
    this.isExplicitlyClosed = false;
    this.heartbeatTimer = null;
    this.reconnectTimer = null;
    this.isConnecting = false;
  }

  /**
   * Connects to the signaling server.
   * Acquires a fresh one-time ticket via POST /api/ws-ticket before every connect.
   * Tickets are single-use, valid for 30s, and NEVER stored or logged.
   */
  async connect() {
    if (this.isExplicitlyClosed) return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    if (CONFIG.MOCK) {
      this._connectMock();
      return;
    }

    this.isConnecting = true;
    try {
      // 1. Fetch one-time connection ticket right before connecting
      const ticketRes = await apiRequest('/ws-ticket', { method: 'POST' });
      const ticket = ticketRes.ticket;
      if (!ticket) {
        throw new Error('Failed to acquire single-use connection ticket');
      }

      // 2. Build WebSocket URL using ticket (never logged)
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}${CONFIG.WS_PATH}?ticket=${encodeURIComponent(ticket)}&room=${encodeURIComponent(this.roomCode)}`;

      logDebug('Signaling', `Connecting to room ${this.roomCode}`);
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        this.isConnecting = false;
        const isReconnection = this.hasConnectedOnce;
        this.hasConnectedOnce = true;
        this.reconnectAttempts = 0;
        logDebug('Signaling', isReconnection ? 'WebSocket reconnected' : 'WebSocket connection established');
        this._startHeartbeat();
        // Immediately send join message
        this.send({ type: 'join', roomCode: this.roomCode });
        this.callbacks.onOpen();
        if (isReconnection) {
          this.callbacks.onReconnect();
        }
      };

      this.ws.onmessage = (event) => {
        this._handleMessage(event.data);
      };

      this.ws.onerror = (err) => {
        logDebug('Signaling', 'WebSocket error occurred');
        this.callbacks.onError(err);
      };

      this.ws.onclose = (event) => {
        this.isConnecting = false;
        this._stopHeartbeat();
        logDebug('Signaling', `WebSocket closed with code ${event.code}: ${event.reason || 'No reason'}`);

        if (event.code === 4000) {
          // Replaced by newer session in another tab
          this.isExplicitlyClosed = true;
          this.callbacks.onReplaced();
          return;
        }

        if (event.code === 4010) {
          // Room ended by host
          this.isExplicitlyClosed = true;
          this.callbacks.onRoomEnded({ reason: 'Room ended by host' });
          return;
        }

        this.callbacks.onClose(event);

        if (!this.isExplicitlyClosed && event.code !== 1000) {
          this._scheduleReconnect();
        }
      };
    } catch (err) {
      this.isConnecting = false;
      logDebug('Signaling', 'Failed during handshake or ticket retrieval', err.message);
      this.callbacks.onError(err);
      this._scheduleReconnect();
    }
  }

  /**
   * Sends a JSON signaling envelope.
   */
  send(message) {
    if (CONFIG.MOCK) {
      this._sendMock(message);
      return;
    }

    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      logDebug('Signaling', 'Cannot send message: WebSocket is not open');
      return;
    }

    try {
      const payloadStr = JSON.stringify(message);
      this.ws.send(payloadStr);
    } catch (err) {
      logDebug('Signaling', 'Failed to serialize message', err.message);
    }
  }

  /**
   * Explicitly closes the connection and cancels timers.
   */
  disconnect() {
    this.isExplicitlyClosed = true;
    this._stopHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    if (this.ws) {
      try {
        if (this.ws.readyState === WebSocket.OPEN) {
          this.send({ type: 'leave', roomCode: this.roomCode });
        }
        this.ws.close(1000, 'Client left');
      } catch (e) {
        // Ignore during shutdown
      }
      this.ws = null;
    }
  }

  // --- Private Helpers ---

  _startHeartbeat() {
    this._stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send({ type: 'ping' });
      }
    }, CONFIG.HEARTBEAT_INTERVAL_MS);
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  _scheduleReconnect() {
    if (this.isExplicitlyClosed) return;
    if (this.reconnectAttempts >= CONFIG.MAX_RECONNECT_ATTEMPTS) {
      logDebug('Signaling', 'Max reconnection attempts reached. Giving up.');
      this.callbacks.onError(new Error('Connection lost. Please rejoin or refresh.'));
      return;
    }

    this.reconnectAttempts++;
    // Exponential backoff with jitter
    const delay = Math.min(
      CONFIG.RECONNECT_BASE_MS * Math.pow(1.5, this.reconnectAttempts) + Math.random() * 500,
      CONFIG.RECONNECT_MAX_MS
    );

    logDebug('Signaling', `Reconnecting attempt ${this.reconnectAttempts} in ${Math.round(delay)}ms`);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }

  _handleMessage(rawData) {
    let msg;
    try {
      msg = JSON.parse(rawData);
    } catch (e) {
      logDebug('Signaling', 'Malformed JSON message received');
      return;
    }

    switch (msg.type) {
      case 'join':
        if (msg.payload) {
          this.selfUserId = msg.payload.selfUserId;
          this.selfName = msg.payload.selfName;
          this.callbacks.onJoinAck(msg.payload);
        }
        break;

      case 'peer-joined':
        this.callbacks.onPeerJoined(msg.payload);
        break;

      case 'peer-left':
        this.callbacks.onPeerLeft(msg.payload);
        break;

      case 'offer':
        this.callbacks.onOffer({ from: msg.from, payload: msg.payload });
        break;

      case 'answer':
        this.callbacks.onAnswer({ from: msg.from, payload: msg.payload });
        break;

      case 'ice-candidate':
        this.callbacks.onIceCandidate({ from: msg.from, payload: msg.payload });
        break;

      case 'mute-status':
        this.callbacks.onMuteStatus({ from: msg.from, payload: msg.payload });
        break;

      case 'screen-share-start':
        this.callbacks.onScreenShareStart({ from: msg.from, payload: msg.payload });
        break;

      case 'screen-share-stop':
        this.callbacks.onScreenShareStop({ from: msg.from, payload: msg.payload });
        break;

      case 'room-ended':
        this.isExplicitlyClosed = true;
        this.callbacks.onRoomEnded(msg.payload);
        break;

      case 'chat':
        this.callbacks.onChat(msg.payload);
        break;

      case 'file-shared':
        this.callbacks.onFileShared(msg.payload);
        break;

      case 'file-deleted':
        this.callbacks.onFileDeleted(msg.payload);
        break;

      case 'draw':
        this.callbacks.onDraw({ from: msg.from, payload: msg.payload });
        break;

      case 'stroke-undo':
        this.callbacks.onStrokeUndo({ from: msg.from, payload: msg.payload });
        break;

      case 'clear-board':
        this.callbacks.onClearBoard({ from: msg.from, payload: msg.payload });
        break;

      case 'board-state':
        this.callbacks.onBoardState({ from: msg.from, payload: msg.payload });
        break;

      case 'pong':
        // Heartbeat acknowledged
        break;

      case 'error':
        logDebug('Signaling', 'Server error message received', msg.payload?.message);
        this.callbacks.onSignalError(msg.payload);
        break;

      default:
        logDebug('Signaling', `Ignored unknown message type: ${msg.type}`);
    }
  }

  // --- Mock Mode Implementation ---

  _connectMock() {
    logDebug('Signaling', 'Mock mode active. Simulating signaling session.');
    this.selfUserId = 999;
    this.selfName = 'Demo User';
    this._mockMessageSeq = 500;

    setTimeout(() => {
      this.callbacks.onOpen();
      this.callbacks.onJoinAck({
        selfUserId: 999,
        selfName: 'Demo User',
        roomCode: this.roomCode,
        peers: [
          { userId: 101, name: 'Priya Sharma', audioMuted: false, videoMuted: false },
          { userId: 102, name: 'Aarav Patel', audioMuted: true, videoMuted: false }
        ]
      });

      // Simulate a chat message from a peer after 3 seconds
      setTimeout(() => {
        if (!this.isExplicitlyClosed) {
          this.callbacks.onChat({
            id: ++this._mockMessageSeq,
            from: 101,
            name: 'Priya Sharma',
            text: 'Hello team! Can everyone hear me clearly?',
            sentAt: new Date().toISOString(),
            clientId: null
          });
        }
      }, 3000);
    }, 200);
  }

  _sendMock(message) {
    logDebug('Signaling', `Mock send: ${message.type}`);
    if (message.type === 'screen-share-start') {
      // Mock acknowledge
    } else if (message.type === 'screen-share-stop') {
      // Mock acknowledge
    } else if (message.type === 'chat') {
      const text = message.payload?.text || '';
      const clientId = message.payload?.clientId || null;

      if (text.toLowerCase().includes('fail')) {
        // Deliberate test case: drop message to test 10s echo timeout & retry button
        logDebug('Signaling', 'Mock mode: dropping chat message to test echo timeout');
        return;
      }

      if (text.toLowerCase().includes('rate')) {
        // Deliberate test case: simulate rate limit rejection
        setTimeout(() => {
          this.callbacks.onSignalError({
            code: 'RATE_LIMIT_EXCEEDED',
            message: 'Chat rate limit exceeded. Please slow down.'
          });
        }, 100);
        return;
      }

      // Standard authoritative echo back to sender
      setTimeout(() => {
        if (!this.isExplicitlyClosed) {
          this.callbacks.onChat({
            id: ++this._mockMessageSeq,
            from: this.selfUserId,
            name: this.selfName,
            text: text,
            sentAt: new Date().toISOString(),
            clientId: clientId
          });
        }
      }, 150);

      // Automated peer response after 2.5s
      if (!text.toLowerCase().includes('fast')) {
        setTimeout(() => {
          if (!this.isExplicitlyClosed) {
            this.callbacks.onChat({
              id: ++this._mockMessageSeq,
              from: 102,
              name: 'Aarav Patel',
              text: 'Got it, thank you!',
              sentAt: new Date().toISOString(),
              clientId: null
            });
          }
        }, 2500);
      }
    }
  }
}
