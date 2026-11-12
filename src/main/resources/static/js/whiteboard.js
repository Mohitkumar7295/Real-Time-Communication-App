/**
 * MeetApp — Collaborative Whiteboard Controller (whiteboard.js)
 * Implements dual-layer HTML5 Canvas, letterboxed 16:9 coordinate normalization,
 * streaming ~40ms stroke batching, shape tools (line, rect, ellipse), ownership-based undo,
 * host board clearing, chunked late-joiner board state synchronization, and PNG export.
 */

import { CONFIG, logDebug } from './config.js';

export class WhiteboardManager {
  /**
   * @param {Object} options
   * @param {string} options.roomCode
   * @param {Object} options.currentUser - { id, name }
   * @param {boolean} options.isHost
   * @param {Object} options.signalingClient - instance of SignalingClient
   * @param {HTMLElement} options.overlayEl - #whiteboardOverlay
   * @param {HTMLElement} options.containerEl - #whiteboardContainer (letterbox stage)
   * @param {HTMLCanvasElement} options.bgCanvasEl - #wbBgCanvas
   * @param {HTMLCanvasElement} options.scratchCanvasEl - #wbScratchCanvas
   * @param {HTMLButtonElement} options.toggleBtnEl - #btnToggleWhiteboard
   */
  constructor(options) {
    this.roomCode = options.roomCode;
    this.currentUser = options.currentUser;
    this.isHost = Boolean(options.isHost);
    this.signaling = options.signalingClient;
    this.overlayEl = options.overlayEl;
    this.containerEl = options.containerEl;
    this.bgCanvas = options.bgCanvasEl;
    this.scratchCanvas = options.scratchCanvasEl;
    this.toggleBtn = options.toggleBtnEl;

    this.bgCtx = this.bgCanvas ? this.bgCanvas.getContext('2d') : null;
    this.scratchCtx = this.scratchCanvas ? this.scratchCanvas.getContext('2d') : null;

    // Tool Controls
    this.activeTool = 'pen'; // 'pen' | 'eraser' | 'line' | 'rect' | 'ellipse'
    this.activeColor = '#1F6F5B';
    this.activeWidth = 4;
    this.isOpen = false;

    // Drawing State
    this.isDrawing = false;
    this.currentStrokeId = null;
    this.currentPointsBatch = [];
    this.currentStrokeAllPoints = [];
    this.shapeStartPoint = null;
    this.lastEmitTimestamp = 0;
    this.emitThrottleMs = 40;

    // Board Model
    this.strokes = []; // Array of { strokeId, userId, tool, color, width, points, final }
    this.strokeMap = new Map(); // strokeId -> stroke
    this.myStrokeIds = []; // Stack of strokeIds created by self for undo
    this.pendingChunks = new Map(); // chunkIndex -> strokes[]

    // Letterbox & High DPI metrics
    this.renderWidth = 960;
    this.renderHeight = 540;
    this.dpr = window.devicePixelRatio || 1;

    // Bound handlers for cleanup
    this._handlePointerDown = this._handlePointerDown.bind(this);
    this._handlePointerMove = this._handlePointerMove.bind(this);
    this._handlePointerUp = this._handlePointerUp.bind(this);
    this._handleResize = this._handleResize.bind(this);
    this._handleKeyDown = this._handleKeyDown.bind(this);

    this.resizeObserver = null;
    this._init();
  }

  _init() {
    this._bindDOMControls();
    this._bindSignaling();
    this._setupResizeListener();
  }

  _bindDOMControls() {
    if (!this.overlayEl) return;

    // Tool selection buttons
    const toolBtns = this.overlayEl.querySelectorAll('[data-wb-tool]');
    toolBtns.forEach(btn => {
      btn.addEventListener('click', (e) => {
        const tool = btn.getAttribute('data-wb-tool');
        if (tool) {
          this.setTool(tool);
          toolBtns.forEach(b => b.classList.toggle('active', b === btn));
        }
      });
    });

    // Color swatches
    const colorBtns = this.overlayEl.querySelectorAll('[data-wb-color]');
    colorBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        const color = btn.getAttribute('data-wb-color');
        if (color) {
          this.setColor(color);
          colorBtns.forEach(b => b.classList.toggle('active', b === btn));
        }
      });
    });

    // Color picker input
    const colorPicker = this.overlayEl.querySelector('#wbColorPicker');
    if (colorPicker) {
      colorPicker.addEventListener('input', (e) => {
        this.setColor(e.target.value);
        colorBtns.forEach(b => b.classList.remove('active'));
      });
    }

    // Stroke width slider
    const widthSlider = this.overlayEl.querySelector('#wbStrokeWidth');
    const widthDisplay = this.overlayEl.querySelector('#wbStrokeWidthVal');
    if (widthSlider) {
      widthSlider.addEventListener('input', (e) => {
        const val = parseFloat(e.target.value) || 4;
        this.activeWidth = val;
        if (widthDisplay) widthDisplay.textContent = `${Math.round(val)}px`;
      });
    }

    // Undo button
    const undoBtn = this.overlayEl.querySelector('#btnWhiteboardUndo');
    if (undoBtn) {
      undoBtn.addEventListener('click', () => this.undoOwnStroke());
    }

    // Clear Board button (Host only)
    const clearBtn = this.overlayEl.querySelector('#btnWhiteboardClear');
    if (clearBtn) {
      if (this.isHost) {
        clearBtn.style.display = 'inline-flex';
        clearBtn.addEventListener('click', () => {
          if (confirm('Clear the entire whiteboard for all participants?')) {
            this.clearBoard();
          }
        });
      } else {
        clearBtn.style.display = 'none';
      }
    }

    // Export PNG button
    const exportBtn = this.overlayEl.querySelector('#btnWhiteboardExport');
    if (exportBtn) {
      exportBtn.addEventListener('click', () => this.exportPNG());
    }

    // Close button
    const closeBtn = this.overlayEl.querySelector('#btnCloseWhiteboard');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => this.close());
    }

    // Pointer events on scratch canvas
    if (this.scratchCanvas) {
      this.scratchCanvas.addEventListener('pointerdown', this._handlePointerDown);
      this.scratchCanvas.addEventListener('pointermove', this._handlePointerMove);
      this.scratchCanvas.addEventListener('pointerup', this._handlePointerUp);
      this.scratchCanvas.addEventListener('pointercancel', this._handlePointerUp);
      this.scratchCanvas.addEventListener('pointerleave', this._handlePointerUp);
    }

    document.addEventListener('keydown', this._handleKeyDown);
  }

  _bindSignaling() {
    if (!this.signaling) return;

    this.signaling.callbacks.onDraw = (event) => {
      this.handleRemoteDraw(event.payload);
    };

    this.signaling.callbacks.onStrokeUndo = (event) => {
      this.handleRemoteUndo(event.payload);
    };

    this.signaling.callbacks.onClearBoard = () => {
      this.handleRemoteClear();
    };

    this.signaling.callbacks.onBoardState = (event) => {
      this.handleRemoteBoardState(event.payload);
    };
  }

  _setupResizeListener() {
    if (window.ResizeObserver && this.containerEl) {
      this.resizeObserver = new ResizeObserver(() => {
        if (this.isOpen) {
          this._handleResize();
        }
      });
      this.resizeObserver.observe(this.containerEl);
    } else {
      window.addEventListener('resize', this._handleResize);
    }
  }

  /**
   * Recalculates canvas dimensions to maintain a strict 16:9 aspect ratio
   * letterboxed within containerEl, accounting for devicePixelRatio.
   */
  _handleResize() {
    if (!this.containerEl || !this.bgCanvas || !this.scratchCanvas) return;

    const rect = this.containerEl.getBoundingClientRect();
    const availableW = rect.width - 24; // padding
    const availableH = rect.height - 24;

    if (availableW <= 0 || availableH <= 0) return;

    const targetRatio = 16 / 9;
    let targetW = availableW;
    let targetH = targetW / targetRatio;

    if (targetH > availableH) {
      targetH = availableH;
      targetW = targetH * targetRatio;
    }

    this.renderWidth = Math.floor(targetW);
    this.renderHeight = Math.floor(targetH);
    this.dpr = window.devicePixelRatio || 1;

    const cssW = `${this.renderWidth}px`;
    const cssH = `${this.renderHeight}px`;

    // Size CSS display
    this.bgCanvas.style.width = cssW;
    this.bgCanvas.style.height = cssH;
    this.scratchCanvas.style.width = cssW;
    this.scratchCanvas.style.height = cssH;

    // Size internal bitmap for High DPI
    const pixelW = Math.round(this.renderWidth * this.dpr);
    const pixelH = Math.round(this.renderHeight * this.dpr);

    this.bgCanvas.width = pixelW;
    this.bgCanvas.height = pixelH;
    this.scratchCanvas.width = pixelW;
    this.scratchCanvas.height = pixelH;

    if (this.bgCtx) {
      this.bgCtx.resetTransform();
      this.bgCtx.scale(this.dpr, this.dpr);
    }
    if (this.scratchCtx) {
      this.scratchCtx.resetTransform();
      this.scratchCtx.scale(this.dpr, this.dpr);
    }

    this.redrawAll();
  }

  _handleKeyDown(e) {
    if (e.key === 'Escape' && this.isOpen) {
      e.preventDefault();
      this.close();
    }
  }

  // --- Pointer & Drawing Interaction ---

  _toNormalized(clientX, clientY) {
    const rect = this.scratchCanvas.getBoundingClientRect();
    const rawX = (clientX - rect.left) / rect.width;
    const rawY = (clientY - rect.top) / rect.height;
    const nx = Math.max(0.0, Math.min(1.0, rawX));
    const ny = Math.max(0.0, Math.min(1.0, rawY));
    return [Number(nx.toFixed(4)), Number(ny.toFixed(4))];
  }

  _handlePointerDown(e) {
    if (e.button !== 0) return; // Primary pointer button only
    e.preventDefault();
    this.scratchCanvas.setPointerCapture(e.pointerId);

    const normPt = this._toNormalized(e.clientX, e.clientY);
    this.isDrawing = true;
    this.currentStrokeId = `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
    this.currentPointsBatch = [normPt];
    this.currentStrokeAllPoints = [normPt];
    this.shapeStartPoint = normPt;
    this.lastEmitTimestamp = Date.now();

    if (this.activeTool === 'pen' || this.activeTool === 'eraser') {
      // Draw initial dot on scratch canvas
      this._renderSegment(this.scratchCtx, [normPt, normPt], this.activeTool, this.activeColor, this.activeWidth);
    }
  }

  _handlePointerMove(e) {
    if (!this.isDrawing) return;
    e.preventDefault();

    const normPt = this._toNormalized(e.clientX, e.clientY);

    if (this.activeTool === 'pen' || this.activeTool === 'eraser') {
      const prevPt = this.currentStrokeAllPoints[this.currentStrokeAllPoints.length - 1];
      this.currentStrokeAllPoints.push(normPt);
      this.currentPointsBatch.push(normPt);

      // Render local segment on scratch canvas immediately
      this._renderSegment(this.scratchCtx, [prevPt, normPt], this.activeTool, this.activeColor, this.activeWidth);

      // Throttle streaming network emission (~40ms)
      const now = Date.now();
      if (now - this.lastEmitTimestamp >= this.emitThrottleMs && this.currentPointsBatch.length > 0) {
        this._emitDrawBatch(false);
        this.lastEmitTimestamp = now;
      }
    } else {
      // Shape tool: interactive preview on scratch canvas
      this._clearCanvas(this.scratchCtx);
      this._renderShape(this.scratchCtx, this.shapeStartPoint, normPt, this.activeTool, this.activeColor, this.activeWidth);
    }
  }

  _handlePointerUp(e) {
    if (!this.isDrawing) return;
    e.preventDefault();
    this.isDrawing = false;
    try {
      this.scratchCanvas.releasePointerCapture(e.pointerId);
    } catch {}

    const normPt = this._toNormalized(e.clientX, e.clientY);

    if (this.activeTool === 'pen' || this.activeTool === 'eraser') {
      this.currentStrokeAllPoints.push(normPt);
      this.currentPointsBatch.push(normPt);

      // Emit final batch
      this._emitDrawBatch(true);

      // Commit to local model & background canvas
      const strokeObj = {
        strokeId: this.currentStrokeId,
        userId: this.currentUser?.id || 1,
        tool: this.activeTool,
        color: this.activeColor,
        width: this.activeWidth,
        points: [...this.currentStrokeAllPoints],
        final: true
      };
      this._recordCommittedStroke(strokeObj);

      // Clear scratch canvas and render committed stroke to background canvas
      this._clearCanvas(this.scratchCtx);
      this._renderCompleteStroke(this.bgCtx, strokeObj);
    } else {
      // Shape tool: finalize with exactly 2 points [start, end]
      this._clearCanvas(this.scratchCtx);

      const shapePoints = [this.shapeStartPoint, normPt];
      const strokeObj = {
        strokeId: this.currentStrokeId,
        userId: this.currentUser?.id || 1,
        tool: this.activeTool,
        color: this.activeColor,
        width: this.activeWidth,
        points: shapePoints,
        final: true
      };

      // Emit shape over WebSocket
      this._sendSignalingMessage('draw', {
        strokeId: this.currentStrokeId,
        tool: this.activeTool,
        color: this.activeColor,
        width: this.activeWidth,
        points: shapePoints,
        final: true
      });

      this._recordCommittedStroke(strokeObj);
      this._renderCompleteStroke(this.bgCtx, strokeObj);
    }

    this.currentPointsBatch = [];
    this.currentStrokeAllPoints = [];
    this.shapeStartPoint = null;
    this.currentStrokeId = null;
  }

  _emitDrawBatch(isFinal) {
    if (this.currentPointsBatch.length === 0 && !isFinal) return;

    const payload = {
      strokeId: this.currentStrokeId,
      tool: this.activeTool,
      color: this.activeColor,
      width: this.activeWidth,
      points: [...this.currentPointsBatch],
      final: isFinal
    };

    this._sendSignalingMessage('draw', payload);
    this.currentPointsBatch = [];
  }

  _recordCommittedStroke(strokeObj) {
    this.strokes.push(strokeObj);
    this.strokeMap.set(strokeObj.strokeId, strokeObj);
    if (this.currentUser && strokeObj.userId === this.currentUser.id) {
      this.myStrokeIds.push(strokeObj.strokeId);
    }
  }

  // --- Rendering Pipeline ---

  _scaleWidth(nominalWidth) {
    // Proportional stroke scale relative to 1000px canonical width
    const factor = Math.max(0.5, this.renderWidth / 1000);
    return Math.max(1, nominalWidth * factor);
  }

  _renderSegment(ctx, points, tool, color, width) {
    if (!ctx || points.length < 2) return;
    const scaledW = this._scaleWidth(width);

    ctx.save();
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.lineWidth = scaledW;

    if (tool === 'eraser') {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.strokeStyle = 'rgba(0,0,0,1)';
    } else {
      ctx.globalCompositeOperation = 'source-over';
      ctx.strokeStyle = color;
    }

    ctx.beginPath();
    const [p0x, p0y] = this._fromNorm(points[0]);
    ctx.moveTo(p0x, p0y);

    for (let i = 1; i < points.length; i++) {
      const [px, py] = this._fromNorm(points[i]);
      ctx.lineTo(px, py);
    }
    ctx.stroke();
    ctx.restore();
  }

  _renderShape(ctx, startNorm, endNorm, tool, color, width) {
    if (!ctx || !startNorm || !endNorm) return;
    const [x0, y0] = this._fromNorm(startNorm);
    const [x1, y1] = this._fromNorm(endNorm);
    const scaledW = this._scaleWidth(width);

    ctx.save();
    ctx.lineWidth = scaledW;
    ctx.strokeStyle = color;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    if (tool === 'line') {
      ctx.beginPath();
      ctx.moveTo(x0, y0);
      ctx.lineTo(x1, y1);
      ctx.stroke();
    } else if (tool === 'rect') {
      const rx = Math.min(x0, x1);
      const ry = Math.min(y0, y1);
      const rw = Math.abs(x1 - x0);
      const rh = Math.abs(y1 - y0);
      ctx.beginPath();
      ctx.strokeRect(rx, ry, rw, rh);
    } else if (tool === 'ellipse') {
      const cx = (x0 + x1) / 2;
      const cy = (y0 + y1) / 2;
      const rx = Math.max(1, Math.abs(x1 - x0) / 2);
      const ry = Math.max(1, Math.abs(y1 - y0) / 2);
      ctx.beginPath();
      ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
  }

  _renderCompleteStroke(ctx, stroke) {
    if (!ctx || !stroke || !stroke.points || stroke.points.length === 0) return;

    if (stroke.tool === 'pen' || stroke.tool === 'eraser') {
      if (stroke.points.length === 1) {
        this._renderSegment(ctx, [stroke.points[0], stroke.points[0]], stroke.tool, stroke.color, stroke.width);
      } else {
        this._renderSegment(ctx, stroke.points, stroke.tool, stroke.color, stroke.width);
      }
    } else {
      if (stroke.points.length >= 2) {
        this._renderShape(ctx, stroke.points[0], stroke.points[1], stroke.tool, stroke.color, stroke.width);
      }
    }
  }

  _fromNorm(normPoint) {
    return [normPoint[0] * this.renderWidth, normPoint[1] * this.renderHeight];
  }

  _clearCanvas(ctx) {
    if (!ctx) return;
    ctx.clearRect(0, 0, this.renderWidth, this.renderHeight);
  }

  redrawAll() {
    this._clearCanvas(this.bgCtx);
    this._clearCanvas(this.scratchCtx);
    this.strokes.forEach(s => {
      this._renderCompleteStroke(this.bgCtx, s);
    });
  }

  // --- Signaling Incoming Event Handlers ---

  handleRemoteDraw(payload) {
    if (!payload || !payload.strokeId) return;

    let existing = this.strokeMap.get(payload.strokeId);
    if (existing) {
      // Append incoming points to existing stroke
      if (payload.points && payload.points.length > 0) {
        const lastPt = existing.points[existing.points.length - 1];
        existing.points.push(...payload.points);

        if (this.isOpen) {
          if (existing.tool === 'pen' || existing.tool === 'eraser') {
            this._renderSegment(this.bgCtx, [lastPt, ...payload.points], existing.tool, existing.color, existing.width);
          } else {
            this.redrawAll();
          }
        }
      }
      if (payload.final) {
        existing.final = true;
      }
    } else {
      // New remote stroke
      const strokeObj = {
        strokeId: payload.strokeId,
        userId: payload.userId || 0,
        tool: payload.tool,
        color: payload.color,
        width: payload.width,
        points: payload.points ? [...payload.points] : [],
        final: Boolean(payload.final)
      };
      this.strokes.push(strokeObj);
      this.strokeMap.set(strokeObj.strokeId, strokeObj);

      if (this.isOpen) {
        this._renderCompleteStroke(this.bgCtx, strokeObj);
      }
    }
  }

  handleRemoteUndo(payload) {
    if (!payload || !payload.strokeId) return;
    const strokeId = payload.strokeId;

    this.strokes = this.strokes.filter(s => s.strokeId !== strokeId);
    this.strokeMap.delete(strokeId);
    this.myStrokeIds = this.myStrokeIds.filter(id => id !== strokeId);

    if (this.isOpen) {
      this.redrawAll();
    }
  }

  handleRemoteClear() {
    this.strokes = [];
    this.strokeMap.clear();
    this.myStrokeIds = [];
    if (this.isOpen) {
      this._clearCanvas(this.bgCtx);
      this._clearCanvas(this.scratchCtx);
    }
  }

  handleRemoteBoardState(payload) {
    if (!payload) return;

    const { chunkIndex, totalChunks, strokes } = payload;
    this.pendingChunks.set(chunkIndex, strokes || []);

    if (this.pendingChunks.size === totalChunks) {
      // All sequential chunks arrived: reassemble board
      const assembled = [];
      for (let i = 0; i < totalChunks; i++) {
        const chunk = this.pendingChunks.get(i) || [];
        assembled.push(...chunk);
      }

      this.pendingChunks.clear();
      this.strokes = assembled;
      this.strokeMap.clear();
      this.myStrokeIds = [];

      this.strokes.forEach(s => {
        this.strokeMap.set(s.strokeId, s);
        if (this.currentUser && s.userId === this.currentUser.id) {
          this.myStrokeIds.push(s.strokeId);
        }
      });

      if (this.isOpen) {
        this.redrawAll();
      }
    }
  }

  // --- Client Actions: Undo, Clear, Export ---

  undoOwnStroke() {
    if (this.myStrokeIds.length === 0) return;
    const strokeId = this.myStrokeIds.pop();

    this.strokes = this.strokes.filter(s => s.strokeId !== strokeId);
    this.strokeMap.delete(strokeId);
    this.redrawAll();

    this._sendSignalingMessage('stroke-undo', { strokeId });
  }

  clearBoard() {
    if (!this.isHost) return;

    this.strokes = [];
    this.strokeMap.clear();
    this.myStrokeIds = [];
    this._clearCanvas(this.bgCtx);
    this._clearCanvas(this.scratchCtx);

    this._sendSignalingMessage('clear-board', {});
  }

  exportPNG() {
    const exportCanvas = document.createElement('canvas');
    exportCanvas.width = 1920;
    exportCanvas.height = 1080;
    const expCtx = exportCanvas.getContext('2d');

    // Background fill (crisp dark slate theme)
    expCtx.fillStyle = '#1B1A17';
    expCtx.fillRect(0, 0, 1920, 1080);

    // Save and scale to 1920x1080
    const originalW = this.renderWidth;
    const originalH = this.renderHeight;
    this.renderWidth = 1920;
    this.renderHeight = 1080;

    this.strokes.forEach(s => {
      this._renderCompleteStroke(expCtx, s);
    });

    this.renderWidth = originalW;
    this.renderHeight = originalH;

    exportCanvas.toBlob((blob) => {
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `meetapp-whiteboard-${this.roomCode}.png`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    }, 'image/png');
  }

  // --- Modal Open/Close Controls ---

  open() {
    if (this.isOpen) return;
    this.isOpen = true;

    if (this.overlayEl) {
      this.overlayEl.removeAttribute('aria-hidden');
      this.overlayEl.removeAttribute('inert');
      this.overlayEl.style.display = 'flex';
    }

    if (this.toggleBtn) {
      this.toggleBtn.classList.add('btn-active-chat');
      this.toggleBtn.setAttribute('aria-expanded', 'true');
    }

    requestAnimationFrame(() => {
      this._handleResize();
    });
  }

  close() {
    if (!this.isOpen) return;
    this.isOpen = false;

    if (this.overlayEl) {
      this.overlayEl.setAttribute('aria-hidden', 'true');
      this.overlayEl.setAttribute('inert', '');
      this.overlayEl.style.display = 'none';
    }

    if (this.toggleBtn) {
      this.toggleBtn.classList.remove('btn-active-chat');
      this.toggleBtn.setAttribute('aria-expanded', 'false');
      this.toggleBtn.focus();
    }
  }

  toggle() {
    if (this.isOpen) {
      this.close();
    } else {
      this.open();
    }
  }

  setTool(tool) {
    this.activeTool = tool;
  }

  setColor(color) {
    this.activeColor = color;
  }

  _sendSignalingMessage(type, payload) {
    if (this.signaling) {
      this.signaling.send({
        type: type,
        roomCode: this.roomCode,
        payload: payload
      });
    }
  }

  destroy() {
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }
    window.removeEventListener('resize', this._handleResize);
    document.removeEventListener('keydown', this._handleKeyDown);

    if (this.scratchCanvas) {
      this.scratchCanvas.removeEventListener('pointerdown', this._handlePointerDown);
      this.scratchCanvas.removeEventListener('pointermove', this._handlePointerMove);
      this.scratchCanvas.removeEventListener('pointerup', this._handlePointerUp);
      this.scratchCanvas.removeEventListener('pointercancel', this._handlePointerUp);
      this.scratchCanvas.removeEventListener('pointerleave', this._handlePointerUp);
    }

    this.strokes = [];
    this.strokeMap.clear();
    this.myStrokeIds = [];
  }
}
