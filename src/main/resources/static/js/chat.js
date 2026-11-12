/**
 * MeetApp — Group Chat Manager (chat.js)
 * Implements real-time messaging over WebSocket signaling, saved history paging,
 * optimistic pending delivery with clientId correlation, 10s echo timeout with retry,
 * unread counter badge (capped at 99+), polite announcements when closed/unfocused,
 * auto-scroll management with "New messages" jump button, and zero-innerHTML XSS safety.
 */

import { CONFIG, logDebug } from './config.js';
import { apiRequest } from './api.js';

export class ChatManager {
  /**
   * @param {Object} options
   * @param {string} options.roomCode
   * @param {Object} options.currentUser - { id, name, email }
   * @param {Object} options.signalingClient - instance of SignalingClient
   * @param {HTMLElement} options.drawerEl - #chatDrawer
   * @param {HTMLButtonElement} options.toggleBtnEl - #btnToggleChat
   * @param {HTMLElement} options.unreadBadgeEl - #chatUnreadBadge
   * @param {HTMLElement} options.ariaLiveEl - #roomAriaLive
   */
  constructor(options) {
    this.roomCode = options.roomCode;
    this.currentUser = options.currentUser;
    this.signaling = options.signalingClient;
    this.drawerEl = options.drawerEl;
    this.toggleBtnEl = options.toggleBtnEl;
    this.unreadBadgeEl = options.unreadBadgeEl;
    this.ariaLiveEl = options.ariaLiveEl;

    // Internal DOM references
    this.closeBtnEl = document.getElementById('btnCloseChat');
    this.bodyEl = document.getElementById('chatBody');
    this.messagesListEl = document.getElementById('chatMessagesList');
    this.loadEarlierWrapEl = document.getElementById('loadEarlierWrap');
    this.loadEarlierBtnEl = document.getElementById('btnLoadEarlier');
    this.jumpLatestBtnEl = document.getElementById('btnJumpLatest');
    this.composerFormEl = document.getElementById('chatComposerForm');
    this.inputEl = document.getElementById('chatInput');
    this.sendBtnEl = document.getElementById('btnSendMessage');
    this.charCounterEl = document.getElementById('chatCharCounter');

    // State
    this.isOpen = false;
    this.unreadCount = 0;
    this.isDrawerFocused = false;
    this.knownMessageIds = new Set();
    this.pendingMessages = new Map(); // clientId -> { clientId, text, bubbleRow, metaStatusEl, timerId, failed }
    this.oldestMessageId = null;
    this.lastKnownMessageId = 0;
    this.isLoadingHistory = false;
    this.hasMoreHistory = false;

    // Bound handlers for cleanup
    this._handleKeyDown = this._handleKeyDown.bind(this);
    this._handleInput = this._handleInput.bind(this);
    this._handleFormSubmit = this._handleFormSubmit.bind(this);
    this._handleBodyScroll = this._handleBodyScroll.bind(this);
    this._handleJumpLatestClick = this._handleJumpLatestClick.bind(this);
    this._handleLoadEarlierClick = this._handleLoadEarlierClick.bind(this);
    this._handleDrawerFocusIn = this._handleDrawerFocusIn.bind(this);
    this._handleDrawerFocusOut = this._handleDrawerFocusOut.bind(this);

    this._initEvents();
  }

  /**
   * Initializes event listeners and loads the initial message history.
   */
  async init() {
    this._updateCharCounter();
    this._updateSendButtonState();
    await this.loadInitialHistory();
  }

  /**
   * Loads initial 50 messages from the REST endpoint or mock data.
   */
  async loadInitialHistory() {
    if (CONFIG.MOCK) {
      this._loadMockHistory();
      return;
    }

    this.isLoadingHistory = true;
    try {
      const data = await apiRequest(`/rooms/${encodeURIComponent(this.roomCode)}/messages?limit=${CONFIG.CHAT.HISTORY_LIMIT}`);
      const messages = (data && data.messages) ? data.messages : [];

      if (messages.length > 0) {
        messages.forEach(msg => {
          if (!this.knownMessageIds.has(msg.id)) {
            this.knownMessageIds.add(msg.id);
            if (this.oldestMessageId === null || msg.id < this.oldestMessageId) {
              this.oldestMessageId = msg.id;
            }
            if (msg.id > this.lastKnownMessageId) {
              this.lastKnownMessageId = msg.id;
            }
            this._renderMessageNode(msg, false); // append
          }
        });

        if (messages.length >= CONFIG.CHAT.HISTORY_LIMIT) {
          this.hasMoreHistory = true;
          this.loadEarlierWrapEl.style.display = 'flex';
        }
      }
      this._scrollToBottom(false);
    } catch (err) {
      logDebug('Chat', 'Failed to load initial chat history', err.message);
    } finally {
      this.isLoadingHistory = false;
    }
  }

  /**
   * Loads earlier historical messages before oldestMessageId.
   */
  async loadEarlierMessages() {
    if (this.isLoadingHistory || !this.oldestMessageId) return;

    if (CONFIG.MOCK) {
      this._loadEarlierMock();
      return;
    }

    this.isLoadingHistory = true;
    this.loadEarlierBtnEl.disabled = true;
    this.loadEarlierBtnEl.textContent = 'Loading...';

    const previousScrollHeight = this.bodyEl.scrollHeight;
    const previousScrollTop = this.bodyEl.scrollTop;

    try {
      const data = await apiRequest(`/rooms/${encodeURIComponent(this.roomCode)}/messages?limit=${CONFIG.CHAT.HISTORY_LIMIT}&before=${this.oldestMessageId}`);
      const messages = (data && data.messages) ? data.messages : [];

      if (messages.length > 0) {
        // Messages arrive oldest first. Insert them at the top in order
        const fragment = document.createDocumentFragment();
        for (const msg of messages) {
          if (!this.knownMessageIds.has(msg.id)) {
            this.knownMessageIds.add(msg.id);
            if (msg.id < this.oldestMessageId) {
              this.oldestMessageId = msg.id;
            }
            const node = this._createMessageNode(msg);
            fragment.appendChild(node);
          }
        }

        if (this.messagesListEl.firstChild) {
          this.messagesListEl.insertBefore(fragment, this.messagesListEl.firstChild);
        } else {
          this.messagesListEl.appendChild(fragment);
        }

        if (messages.length < CONFIG.CHAT.HISTORY_LIMIT) {
          this.hasMoreHistory = false;
          this.loadEarlierWrapEl.style.display = 'none';
        }

        // Restore scroll position so content doesn't jump
        requestAnimationFrame(() => {
          const newScrollHeight = this.bodyEl.scrollHeight;
          this.bodyEl.scrollTop = previousScrollTop + (newScrollHeight - previousScrollHeight);
        });
      } else {
        this.hasMoreHistory = false;
        this.loadEarlierWrapEl.style.display = 'none';
      }
    } catch (err) {
      logDebug('Chat', 'Failed to load earlier chat history', err.message);
    } finally {
      this.isLoadingHistory = false;
      this.loadEarlierBtnEl.disabled = false;
      this.loadEarlierBtnEl.textContent = 'Load earlier messages';
    }
  }

  /**
   * Fetches missing messages after a socket reconnect.
   */
  async handleSocketReconnect() {
    if (CONFIG.MOCK) return;
    try {
      const data = await apiRequest(`/rooms/${encodeURIComponent(this.roomCode)}/messages?limit=${CONFIG.CHAT.HISTORY_LIMIT}`);
      const messages = (data && data.messages) ? data.messages : [];
      let addedAny = false;

      messages.forEach(msg => {
        if (!this.knownMessageIds.has(msg.id) && msg.id > this.lastKnownMessageId) {
          this.knownMessageIds.add(msg.id);
          this.lastKnownMessageId = msg.id;
          this._renderMessageNode(msg, false);
          addedAny = true;
        }
      });

      if (addedAny) {
        if (this._isScrolledNearBottom() || this.isOpen) {
          this._scrollToBottom(true);
        } else {
          this._showJumpLatestButton();
        }
      }
    } catch (err) {
      logDebug('Chat', 'Failed to refresh chat after reconnect', err.message);
    }
  }

  /**
   * Toggles drawer open/closed state.
   */
  toggle() {
    if (this.isOpen) {
      this.close();
    } else {
      this.open();
    }
  }

  /**
   * Opens the chat drawer panel, manages focus, and clears unread state.
   */
  open() {
    if (this.isOpen) return;
    this.isOpen = true;

    this.drawerEl.removeAttribute('aria-hidden');
    this.drawerEl.removeAttribute('inert');
    this.toggleBtnEl.setAttribute('aria-expanded', 'true');
    this.toggleBtnEl.classList.add('btn-active-chat');

    this.clearUnread();
    this._scrollToBottom(false);

    // Focus into the drawer composer
    requestAnimationFrame(() => {
      this.inputEl.focus();
    });
  }

  /**
   * Closes the chat drawer panel, restores inertness, and returns focus to toggle button.
   */
  close() {
    if (!this.isOpen) return;
    this.isOpen = false;

    this.drawerEl.setAttribute('aria-hidden', 'true');
    this.drawerEl.setAttribute('inert', '');
    this.toggleBtnEl.setAttribute('aria-expanded', 'false');
    this.toggleBtnEl.classList.remove('btn-active-chat');

    // Return focus to the Chat toggle button
    this.toggleBtnEl.focus();
  }

  /**
   * Clears unread badge counter and updates accessible label.
   */
  clearUnread() {
    this.unreadCount = 0;
    this.unreadBadgeEl.style.display = 'none';
    this.unreadBadgeEl.textContent = '0';
    this.toggleBtnEl.setAttribute('aria-label', 'Chat');
  }

  /**
   * Increments unread count, updates badge (capped at 99+), and politely announces to screen readers.
   */
  recordUnread(senderName, previewText) {
    if (this.isOpen && this.isDrawerFocused) {
      return; // Actively looking at chat
    }

    this.unreadCount++;
    const displayCount = this.unreadCount > CONFIG.CHAT.MAX_UNREAD_DISPLAY
      ? `${CONFIG.CHAT.MAX_UNREAD_DISPLAY}+`
      : String(this.unreadCount);

    this.unreadBadgeEl.textContent = displayCount;
    this.unreadBadgeEl.style.display = 'inline-flex';

    const plural = this.unreadCount === 1 ? '' : 's';
    this.toggleBtnEl.setAttribute('aria-label', `Chat, ${displayCount} unread message${plural}`);

    // Polite live announcement only when closed or unfocused
    if (this.ariaLiveEl && senderName && previewText) {
      const sanitizedName = senderName.replace(/[<>]/g, '');
      const snippet = previewText.length > 80 ? `${previewText.slice(0, 80)}…` : previewText;
      this.ariaLiveEl.textContent = `New message from ${sanitizedName}: ${snippet}`;
    }
  }

  /**
   * Sends a message over WebSocket, creates an optimistic pending bubble,
   * and starts a 10-second echo correlation timer.
   */
  sendMessage(text, retryClientId = null) {
    const trimmed = text.trim();
    if (!trimmed) return;

    if (trimmed.length > CONFIG.CHAT.MAX_TEXT_LENGTH) {
      this._showLocalError(`Message exceeds ${CONFIG.CHAT.MAX_TEXT_LENGTH} character limit`);
      return;
    }

    const lineCount = trimmed.split('\n').length;
    if (lineCount > CONFIG.CHAT.MAX_LINES) {
      this._showLocalError(`Message exceeds ${CONFIG.CHAT.MAX_LINES} lines limit`);
      return;
    }

    // Reuse known failed clientId or generate a fresh one
    const clientId = retryClientId || this._generateClientId();

    // 1. If this is a retry, find existing bubble or create new optimistic bubble
    let pendingEntry = this.pendingMessages.get(clientId);
    if (!pendingEntry) {
      const bubbleRow = this._createPendingBubble(trimmed, clientId);
      this.messagesListEl.appendChild(bubbleRow);
      this._scrollToBottom(true);

      const statusEl = bubbleRow.querySelector('.chat-status');
      pendingEntry = {
        clientId,
        text: trimmed,
        bubbleRow,
        metaStatusEl: statusEl,
        timerId: null,
        failed: false
      };
      this.pendingMessages.set(clientId, pendingEntry);
    } else {
      // Transition back to sending state
      pendingEntry.failed = false;
      pendingEntry.metaStatusEl.textContent = 'Sending...';
      pendingEntry.metaStatusEl.className = 'chat-status chat-status-pending';
      pendingEntry.bubbleRow.classList.add('is-pending');
      const retryBtn = pendingEntry.bubbleRow.querySelector('.chat-retry-btn');
      if (retryBtn) retryBtn.remove();
    }

    // 2. Set 10-second echo timeout timer
    if (pendingEntry.timerId) {
      clearTimeout(pendingEntry.timerId);
    }
    pendingEntry.timerId = setTimeout(() => {
      this._handleEchoTimeout(clientId);
    }, CONFIG.CHAT.ECHO_TIMEOUT_MS);

    // 3. Clear draft and reset textarea height
    if (!retryClientId) {
      this.inputEl.value = '';
      this._updateCharCounter();
      this._autoResizeInput();
      this._updateSendButtonState();
    }

    // 4. Transmit over WebSocket
    const sent = this.signaling.send({
      type: 'chat',
      roomCode: this.roomCode,
      payload: {
        text: trimmed,
        clientId: clientId
      }
    });

    if (sent === false) {
      // Immediate failure (socket closed)
      this._handleEchoTimeout(clientId);
    }
  }

  /**
   * Handles incoming server broadcast 'chat' envelope.
   * Correlates echo with pending message by clientId, or appends peer message.
   */
  handleServerChat(payload) {
    if (!payload || !payload.id) return;

    const messageId = payload.id;
    const clientId = payload.clientId;
    const isFromSelf = Boolean(this.currentUser && payload.from === this.currentUser.id);

    // 1. Check for optimistic pending correlation
    if (clientId && this.pendingMessages.has(clientId)) {
      const pending = this.pendingMessages.get(clientId);
      clearTimeout(pending.timerId);
      this.pendingMessages.delete(clientId);

      this.knownMessageIds.add(messageId);
      if (messageId > this.lastKnownMessageId) {
        this.lastKnownMessageId = messageId;
      }

      // Upgrade optimistic bubble into confirmed message
      this._confirmPendingBubble(pending.bubbleRow, payload);
      this._scrollToBottom(true);
      return;
    }

    // 2. Check de-duplication
    if (this.knownMessageIds.has(messageId)) {
      return;
    }

    this.knownMessageIds.add(messageId);
    if (messageId > this.lastKnownMessageId) {
      this.lastKnownMessageId = messageId;
    }

    // 3. Render and handle unread notifications
    const wasNearBottom = this._isScrolledNearBottom();
    this._renderMessageNode(payload, false);

    if (isFromSelf || wasNearBottom) {
      this._scrollToBottom(true);
    } else {
      this._showJumpLatestButton();
    }

    if (!isFromSelf) {
      this.recordUnread(payload.name, payload.text);
    }
  }

  /**
   * Handles server-side signaling errors directed at chat (e.g. RATE_LIMIT_EXCEEDED).
   */
  handleSignalError(errorPayload) {
    if (!errorPayload) return;
    const code = errorPayload.code || 'ERROR';
    const message = errorPayload.message || 'An error occurred';

    if (code === 'RATE_LIMIT_EXCEEDED' || code === 'MESSAGE_SAVE_FAILED' || code === 'INVALID_PAYLOAD') {
      this._showLocalError(message);
    }
  }

  /**
   * Adds a local-only system notice (e.g. "Priya Sharma joined the meeting").
   * Strictly built with createElement/textContent, never stored, never sent over WS.
   */
  addSystemNotice(text) {
    const row = document.createElement('div');
    row.className = 'chat-message-row msg-system';

    const span = document.createElement('span');
    span.className = 'chat-system-text';
    span.textContent = text;
    row.appendChild(span);

    this.messagesListEl.appendChild(row);
    if (this._isScrolledNearBottom()) {
      this._scrollToBottom(true);
    }
  }

  /**
   * Cleans up all timers, listeners, and references.
   */
  destroy() {
    // Clear all pending message timeout timers
    this.pendingMessages.forEach(entry => {
      if (entry.timerId) clearTimeout(entry.timerId);
    });
    this.pendingMessages.clear();
    this.knownMessageIds.clear();

    document.removeEventListener('keydown', this._handleKeyDown);
    if (this.inputEl) {
      this.inputEl.removeEventListener('input', this._handleInput);
      this.inputEl.removeEventListener('keydown', this._handleKeyDown);
    }
    if (this.composerFormEl) {
      this.composerFormEl.removeEventListener('submit', this._handleFormSubmit);
    }
    if (this.bodyEl) {
      this.bodyEl.removeEventListener('scroll', this._handleBodyScroll);
    }
    if (this.jumpLatestBtnEl) {
      this.jumpLatestBtnEl.removeEventListener('click', this._handleJumpLatestClick);
    }
    if (this.loadEarlierBtnEl) {
      this.loadEarlierBtnEl.removeEventListener('click', this._handleLoadEarlierClick);
    }
    if (this.closeBtnEl) {
      this.closeBtnEl.removeEventListener('click', () => this.close());
    }
    if (this.toggleBtnEl) {
      this.toggleBtnEl.removeEventListener('click', () => this.toggle());
    }
    if (this.drawerEl) {
      this.drawerEl.removeEventListener('focusin', this._handleDrawerFocusIn);
      this.drawerEl.removeEventListener('focusout', this._handleDrawerFocusOut);
    }
  }

  // --- Private DOM & Event Methods ---

  _initEvents() {
    this.toggleBtnEl.addEventListener('click', () => this.toggle());
    if (this.closeBtnEl) {
      this.closeBtnEl.addEventListener('click', () => this.close());
    }

    document.addEventListener('keydown', this._handleKeyDown);
    this.inputEl.addEventListener('input', this._handleInput);
    this.composerFormEl.addEventListener('submit', this._handleFormSubmit);
    this.bodyEl.addEventListener('scroll', this._handleBodyScroll);
    this.jumpLatestBtnEl.addEventListener('click', this._handleJumpLatestClick);
    this.loadEarlierBtnEl.addEventListener('click', this._handleLoadEarlierClick);

    this.drawerEl.addEventListener('focusin', this._handleDrawerFocusIn);
    this.drawerEl.addEventListener('focusout', this._handleDrawerFocusOut);
  }

  _handleKeyDown(event) {
    // Escape key closes the chat drawer
    if (event.key === 'Escape' && this.isOpen) {
      event.preventDefault();
      this.close();
      return;
    }

    // Composer textarea: Enter sends, Shift+Enter inserts newline
    if (event.target === this.inputEl && event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this._submitComposer();
    }
  }

  _handleInput() {
    this._updateCharCounter();
    this._autoResizeInput();
    this._updateSendButtonState();
  }

  _handleFormSubmit(event) {
    event.preventDefault();
    this._submitComposer();
  }

  _submitComposer() {
    const text = this.inputEl.value;
    if (text.trim().length > 0) {
      this.sendMessage(text);
    }
  }

  _handleBodyScroll() {
    if (this._isScrolledNearBottom()) {
      this._hideJumpLatestButton();
    }
  }

  _handleJumpLatestClick() {
    this._scrollToBottom(true);
    this._hideJumpLatestButton();
  }

  _handleLoadEarlierClick() {
    this.loadEarlierMessages();
  }

  _handleDrawerFocusIn() {
    this.isDrawerFocused = true;
    if (this.unreadCount > 0) {
      this.clearUnread();
    }
  }

  _handleDrawerFocusOut(event) {
    // If focus moves completely outside the drawer
    if (!this.drawerEl.contains(event.relatedTarget)) {
      this.isDrawerFocused = false;
    }
  }

  _updateCharCounter() {
    const len = this.inputEl.value.length;
    this.charCounterEl.textContent = `${len}/${CONFIG.CHAT.MAX_TEXT_LENGTH}`;
    if (len > CONFIG.CHAT.MAX_TEXT_LENGTH * 0.9) {
      this.charCounterEl.classList.add('counter-warning');
    } else {
      this.charCounterEl.classList.remove('counter-warning');
    }
  }

  _autoResizeInput() {
    this.inputEl.style.height = 'auto';
    const newHeight = Math.min(this.inputEl.scrollHeight, 120);
    this.inputEl.style.height = `${newHeight}px`;
  }

  _updateSendButtonState() {
    const hasText = this.inputEl.value.trim().length > 0;
    this.sendBtnEl.disabled = !hasText;
  }

  _isScrolledNearBottom() {
    const threshold = 60;
    const distance = this.bodyEl.scrollHeight - this.bodyEl.scrollTop - this.bodyEl.clientHeight;
    return distance <= threshold;
  }

  _scrollToBottom(smooth = false) {
    if (smooth) {
      this.bodyEl.scrollTo({ top: this.bodyEl.scrollHeight, behavior: 'smooth' });
    } else {
      this.bodyEl.scrollTop = this.bodyEl.scrollHeight;
    }
  }

  _showJumpLatestButton() {
    this.jumpLatestBtnEl.style.display = 'block';
  }

  _hideJumpLatestButton() {
    this.jumpLatestBtnEl.style.display = 'none';
  }

  /**
   * Generates a unique client correlation ID.
   */
  _generateClientId() {
    return `c-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
  }

  /**
   * Safely builds text and anchor nodes for plain text and http/https URLs.
   * NEVER uses innerHTML.
   */
  _appendSanitizedContent(container, text) {
    const urlPattern = /(https?:\/\/[^\s<]+)/g;
    let lastIdx = 0;
    let match;

    while ((match = urlPattern.exec(text)) !== null) {
      const matchStart = match.index;
      if (matchStart > lastIdx) {
        const plainPiece = text.substring(lastIdx, matchStart);
        container.appendChild(document.createTextNode(plainPiece));
      }

      const rawUrl = match[0];
      try {
        const parsed = new URL(rawUrl);
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
          const anchor = document.createElement('a');
          anchor.href = parsed.href;
          anchor.textContent = rawUrl;
          anchor.target = '_blank';
          anchor.rel = 'noopener noreferrer';
          anchor.className = 'chat-link';
          container.appendChild(anchor);
        } else {
          container.appendChild(document.createTextNode(rawUrl));
        }
      } catch (e) {
        container.appendChild(document.createTextNode(rawUrl));
      }

      lastIdx = urlPattern.lastIndex;
    }

    if (lastIdx < text.length) {
      const remainder = text.substring(lastIdx);
      container.appendChild(document.createTextNode(remainder));
    }
  }

  /**
   * Creates an optimistic pending message bubble for the local user.
   */
  _createPendingBubble(text, clientId) {
    const row = document.createElement('div');
    row.className = 'chat-message-row msg-local is-pending';
    row.dataset.clientId = clientId;

    const senderName = document.createElement('span');
    senderName.className = 'chat-sender-name';
    senderName.textContent = 'You';
    row.appendChild(senderName);

    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble';
    this._appendSanitizedContent(bubble, text);
    row.appendChild(bubble);

    const meta = document.createElement('div');
    meta.className = 'chat-meta';

    const status = document.createElement('span');
    status.className = 'chat-status chat-status-pending';
    status.textContent = 'Sending...';
    meta.appendChild(status);

    row.appendChild(meta);
    return row;
  }

  /**
   * Confirms an optimistic pending bubble upon receiving authoritative server echo.
   */
  _confirmPendingBubble(bubbleRow, payload) {
    bubbleRow.classList.remove('is-pending');
    bubbleRow.dataset.messageId = payload.id;

    const meta = bubbleRow.querySelector('.chat-meta');
    if (meta) {
      // Clear status ("Sending...")
      while (meta.firstChild) {
        meta.removeChild(meta.firstChild);
      }

      const timeSpan = document.createElement('span');
      timeSpan.className = 'chat-timestamp';
      timeSpan.textContent = this._formatTimestamp(payload.sentAt);
      meta.appendChild(timeSpan);
    }
  }

  /**
   * Called when echo is not received within 10 seconds.
   */
  _handleEchoTimeout(clientId) {
    const pending = this.pendingMessages.get(clientId);
    if (!pending || pending.failed) return;

    pending.failed = true;
    pending.metaStatusEl.textContent = 'Failed to send.';
    pending.metaStatusEl.className = 'chat-status chat-status-failed';
    pending.bubbleRow.classList.remove('is-pending');

    // Add Retry button
    const retryBtn = document.createElement('button');
    retryBtn.type = 'button';
    retryBtn.className = 'chat-retry-btn';
    retryBtn.textContent = 'Retry';
    retryBtn.setAttribute('aria-label', 'Retry sending message');
    retryBtn.addEventListener('click', () => {
      // Re-send reusing the same clientId
      this.sendMessage(pending.text, clientId);
    });

    const meta = pending.bubbleRow.querySelector('.chat-meta');
    if (meta) {
      meta.appendChild(retryBtn);
    }
  }

  /**
   * Creates a confirmed message DOM node.
   */
  _createMessageNode(payload) {
    const isLocal = Boolean(this.currentUser && (payload.from === this.currentUser.id || payload.senderId === this.currentUser.id));

    const row = document.createElement('div');
    row.className = `chat-message-row ${isLocal ? 'msg-local' : 'msg-remote'}`;
    row.dataset.messageId = payload.id;

    const senderName = document.createElement('span');
    senderName.className = 'chat-sender-name';
    senderName.textContent = isLocal ? 'You' : (payload.name || payload.senderName || 'Participant');
    row.appendChild(senderName);

    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble';
    this._appendSanitizedContent(bubble, payload.text || '');
    row.appendChild(bubble);

    const meta = document.createElement('div');
    meta.className = 'chat-meta';

    const timeSpan = document.createElement('span');
    timeSpan.className = 'chat-timestamp';
    timeSpan.textContent = this._formatTimestamp(payload.sentAt);
    meta.appendChild(timeSpan);

    row.appendChild(meta);
    return row;
  }

  _renderMessageNode(payload, prepend = false) {
    const node = this._createMessageNode(payload);
    if (prepend && this.messagesListEl.firstChild) {
      this.messagesListEl.insertBefore(node, this.messagesListEl.firstChild);
    } else {
      this.messagesListEl.appendChild(node);
    }
  }

  _showLocalError(text) {
    const row = document.createElement('div');
    row.className = 'chat-message-row msg-system';

    const span = document.createElement('span');
    span.className = 'chat-system-text chat-status-failed';
    span.textContent = text;
    row.appendChild(span);

    this.messagesListEl.appendChild(row);
    this._scrollToBottom(true);
  }

  _formatTimestamp(isoString) {
    if (!isoString) return '';
    try {
      const date = new Date(isoString);
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch (e) {
      return '';
    }
  }

  // --- Mock Mode Helpers ---

  _loadMockHistory() {
    const mockMessages = [
      { id: 101, senderId: 2, senderName: 'Priya Sharma', text: 'Welcome everyone to the sprint review!', sentAt: new Date(Date.now() - 300000).toISOString() },
      { id: 102, senderId: 3, senderName: 'Aarav Patel', text: 'Glad to be here. Audio is working well.', sentAt: new Date(Date.now() - 180000).toISOString() }
    ];

    mockMessages.forEach(msg => {
      this.knownMessageIds.add(msg.id);
      this.oldestMessageId = 101;
      this.lastKnownMessageId = 102;
      this._renderMessageNode(msg, false);
    });

    this.loadEarlierWrapEl.style.display = 'flex';
    this._scrollToBottom(false);
  }

  _loadEarlierMock() {
    this.isLoadingHistory = true;
    this.loadEarlierBtnEl.disabled = true;
    this.loadEarlierBtnEl.textContent = 'Loading...';

    setTimeout(() => {
      const earlier = [
        { id: 99, senderId: 2, senderName: 'Priya Sharma', text: 'Shared the agenda on https://meetapp.com/agenda', sentAt: new Date(Date.now() - 600000).toISOString() }
      ];

      earlier.forEach(msg => {
        if (!this.knownMessageIds.has(msg.id)) {
          this.knownMessageIds.add(msg.id);
          this.oldestMessageId = msg.id;
          this._renderMessageNode(msg, true);
        }
      });

      this.hasMoreHistory = false;
      this.loadEarlierWrapEl.style.display = 'none';
      this.isLoadingHistory = false;
      this.loadEarlierBtnEl.disabled = false;
      this.loadEarlierBtnEl.textContent = 'Load earlier messages';
    }, 400);
  }
}
