/**
 * MeetApp — File Sharing Controller (files.js)
 * Manages file uploads with progress/cancel, downloads via authenticated Blobs,
 * real-time broadcast updates, and accessible text-only DOM rendering.
 */

import { apiRequest } from './api.js';
import { getToken } from './auth.js';
import { CONFIG } from './config.js';

const ALLOWED_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp',
  'pdf', 'txt', 'csv', 'docx', 'xlsx', 'pptx'
]);

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

export class FileManager {
  constructor(roomCode, socketClient, isHost, currentUserId) {
    this.roomCode = roomCode;
    this.socketClient = socketClient;
    this.isHost = Boolean(isHost);
    this.currentUserId = currentUserId;

    this.filesMap = new Map(); // id -> fileObj
    this.activeXhr = null;
    this.unreadCount = 0;
    this.isTabActive = false;
    this.isDrawerOpen = false;

    // Elements
    this.listEl = document.getElementById('filesList');
    this.dropZoneEl = document.getElementById('fileDropZone');
    this.fileInputEl = document.getElementById('fileInput');
    this.progressWrapEl = document.getElementById('uploadProgressWrap');
    this.progressBarEl = document.getElementById('uploadProgressFill');
    this.progressPercentEl = document.getElementById('uploadProgressPercent');
    this.fileNameEl = document.getElementById('uploadFileName');
    this.btnCancelEl = document.getElementById('btnCancelUpload');
    this.emptyStateEl = document.getElementById('emptyFilesState');
    this.tabBadgeEl = document.getElementById('tabFilesBadge');
    this.toolbarBadgeEl = document.getElementById('filesToolbarBadge');

    this._bindEvents();
    this._bindSocket();
  }

  _bindEvents() {
    if (this.dropZoneEl && this.fileInputEl) {
      this.dropZoneEl.addEventListener('click', () => this.fileInputEl.click());

      this.fileInputEl.addEventListener('change', (e) => {
        const file = e.target.files && e.target.files[0];
        if (file) this.uploadFile(file);
        this.fileInputEl.value = '';
      });

      // Drag and drop
      ['dragenter', 'dragover'].forEach(eventName => {
        this.dropZoneEl.addEventListener(eventName, (e) => {
          e.preventDefault();
          e.stopPropagation();
          this.dropZoneEl.classList.add('drag-active');
        });
      });

      ['dragleave', 'drop'].forEach(eventName => {
        this.dropZoneEl.addEventListener(eventName, (e) => {
          e.preventDefault();
          e.stopPropagation();
          this.dropZoneEl.classList.remove('drag-active');
        });
      });

      this.dropZoneEl.addEventListener('drop', (e) => {
        const dt = e.dataTransfer;
        const file = dt && dt.files && dt.files[0];
        if (file) this.uploadFile(file);
      });
    }

    if (this.btnCancelEl) {
      this.btnCancelEl.addEventListener('click', () => {
        if (this.activeXhr) {
          this.activeXhr.abort();
          this.activeXhr = null;
          this._hideProgress();
        }
      });
    }
  }

  _bindSocket() {
    if (!this.socketClient) return;

    this.socketClient.callbacks.onFileShared = (payload) => {
      this._handleLiveFileShared(payload);
    };

    this.socketClient.callbacks.onFileDeleted = (payload) => {
      this._handleLiveFileDeleted(payload);
    };
  }

  setTabState(isTabActive, isDrawerOpen) {
    this.isTabActive = isTabActive;
    this.isDrawerOpen = isDrawerOpen;
    if (this.isTabActive && this.isDrawerOpen) {
      this.unreadCount = 0;
      this._updateBadges();
    }
  }

  _updateBadges() {
    const display = this.unreadCount > 99 ? '99+' : String(this.unreadCount);
    const visible = this.unreadCount > 0;

    if (this.tabBadgeEl) {
      this.tabBadgeEl.textContent = display;
      this.tabBadgeEl.style.display = visible ? 'inline-block' : 'none';
    }
    if (this.toolbarBadgeEl) {
      this.toolbarBadgeEl.textContent = display;
      this.toolbarBadgeEl.style.display = visible ? 'inline-block' : 'none';
    }
  }

  async loadInitialFiles() {
    if (CONFIG.MOCK) {
      this.renderFiles([
        {
          id: 101,
          originalName: 'presentation.pdf',
          sizeBytes: 1548576,
          uploaderId: 1,
          uploaderName: 'Host User',
          uploadedAt: new Date().toISOString()
        }
      ]);
      return;
    }

    try {
      const res = await apiRequest(`/rooms/${encodeURIComponent(this.roomCode)}/files?limit=50`);
      if (res && res.files) {
        this.renderFiles(res.files);
      }
    } catch (err) {
      console.warn('Failed to load room files', err);
    }
  }

  uploadFile(file) {
    if (!file) return;

    // 1. Client-side size check
    if (file.size > MAX_FILE_SIZE_BYTES) {
      alert(`File "${file.name}" exceeds the maximum limit of 10 MB.`);
      return;
    }

    // 2. Client-side extension check
    const ext = (file.name.split('.').pop() || '').toLowerCase();
    if (!ALLOWED_EXTENSIONS.has(ext)) {
      alert(`File type ".${ext}" is not supported. Allowed formats: PNG, JPG, GIF, WebP, PDF, TXT, CSV, DOCX, XLSX, PPTX.`);
      return;
    }

    const token = getToken();
    if (!token && !CONFIG.MOCK) {
      alert('You must be logged in to upload files.');
      return;
    }

    this._showProgress(file.name);

    if (CONFIG.MOCK) {
      setTimeout(() => {
        this._hideProgress();
        this._handleLiveFileShared({
          fileId: Date.now(),
          originalName: file.name,
          sizeBytes: file.size,
          uploaderId: this.currentUserId,
          uploaderName: 'You',
          uploadedAt: new Date().toISOString()
        });
      }, 800);
      return;
    }

    const formData = new FormData();
    formData.append('file', file);

    const xhr = new XMLHttpRequest();
    this.activeXhr = xhr;

    xhr.open('POST', `/api/files/upload/${encodeURIComponent(this.roomCode)}`);
    xhr.setRequestHeader('Authorization', `Bearer ${token}`);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && this.progressBarEl && this.progressPercentEl) {
        const percent = Math.min(100, Math.round((e.loaded / e.total) * 100));
        this.progressBarEl.style.width = `${percent}%`;
        this.progressPercentEl.textContent = `${percent}%`;
      }
    };

    xhr.onload = () => {
      this.activeXhr = null;
      this._hideProgress();

      if (xhr.status === 201) {
        // Successful upload: server broadcasts file-shared
      } else {
        let errorMsg = 'Upload failed.';
        try {
          const body = JSON.parse(xhr.responseText);
          if (body.message) errorMsg = body.message;
        } catch {}
        alert(errorMsg);
      }
    };

    xhr.onerror = () => {
      this.activeXhr = null;
      this._hideProgress();
      alert('Network error during file upload. Please try again.');
    };

    xhr.onabort = () => {
      this.activeXhr = null;
      this._hideProgress();
    };

    xhr.send(formData);
  }

  async downloadFile(fileId, originalName) {
    if (CONFIG.MOCK) {
      alert(`Mock download for "${originalName}"`);
      return;
    }

    const token = getToken();
    try {
      const res = await fetch(`/api/files/download/${fileId}`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });

      if (!res.ok) {
        throw new Error(`Download failed with status ${res.status}`);
      }

      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);

      const a = document.createElement('a');
      a.href = objectUrl;
      a.download = originalName || 'download';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);

      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (err) {
      console.error('Download error', err);
      alert('Could not download file: ' + err.message);
    }
  }

  async deleteFile(fileId) {
    if (!confirm('Are you sure you want to delete this shared file?')) return;

    if (CONFIG.MOCK) {
      this._handleLiveFileDeleted({ fileId });
      return;
    }

    try {
      await apiRequest(`/files/${fileId}`, { method: 'DELETE' });
      this._handleLiveFileDeleted({ fileId });
    } catch (err) {
      console.error('Failed to delete file', err);
      alert('Could not delete file: ' + (err.message || 'Error occurred'));
    }
  }

  _showProgress(name) {
    if (this.progressWrapEl) this.progressWrapEl.style.display = 'flex';
    if (this.fileNameEl) this.fileNameEl.textContent = name;
    if (this.progressBarEl) this.progressBarEl.style.width = '0%';
    if (this.progressPercentEl) this.progressPercentEl.textContent = '0%';
  }

  _hideProgress() {
    if (this.progressWrapEl) this.progressWrapEl.style.display = 'none';
    if (this.progressBarEl) this.progressBarEl.style.width = '0%';
  }

  _handleLiveFileShared(payload) {
    const fileItem = {
      id: payload.fileId,
      originalName: payload.originalName,
      sizeBytes: payload.sizeBytes,
      uploaderId: payload.uploaderId,
      uploaderName: payload.uploaderName,
      uploadedAt: payload.uploadedAt
    };

    if (this.filesMap.has(fileItem.id)) return;
    this.filesMap.set(fileItem.id, fileItem);

    this._prependFileCard(fileItem);

    if (!this.isTabActive || !this.isDrawerOpen) {
      this.unreadCount++;
      this._updateBadges();
    }
  }

  _handleLiveFileDeleted(payload) {
    const fileId = payload.fileId;
    this.filesMap.delete(fileId);

    const card = document.getElementById(`fileCard-${fileId}`);
    if (card) {
      card.remove();
    }

    if (this.filesMap.size === 0 && this.emptyStateEl) {
      this.emptyStateEl.style.display = 'flex';
    }
  }

  renderFiles(files) {
    if (!this.listEl) return;
    this.listEl.textContent = ''; // Strict clear
    this.filesMap.clear();

    if (!files || files.length === 0) {
      if (this.emptyStateEl) this.emptyStateEl.style.display = 'flex';
      return;
    }

    if (this.emptyStateEl) this.emptyStateEl.style.display = 'none';

    files.forEach(f => {
      this.filesMap.set(f.id, f);
      this._appendFileCard(f);
    });
  }

  _buildCardNode(f) {
    const li = document.createElement('li');
    li.className = 'file-card';
    li.id = `fileCard-${f.id}`;

    const left = document.createElement('div');
    left.className = 'file-card-info';

    // File icon
    const iconWrap = document.createElement('div');
    iconWrap.className = 'file-card-icon';
    iconWrap.textContent = (f.originalName.split('.').pop() || 'FILE').toUpperCase().slice(0, 4);
    left.appendChild(iconWrap);

    const textCol = document.createElement('div');
    textCol.className = 'file-text-col';

    const nameSpan = document.createElement('span');
    nameSpan.className = 'file-name';
    nameSpan.textContent = f.originalName; // STRICT textContent
    textCol.appendChild(nameSpan);

    const metaSpan = document.createElement('span');
    metaSpan.className = 'file-meta';
    const sizeStr = this._formatSize(f.sizeBytes);
    const uploader = f.uploaderName || 'Participant';
    metaSpan.textContent = `${sizeStr} · Shared by ${uploader}`;
    textCol.appendChild(metaSpan);

    left.appendChild(textCol);
    li.appendChild(left);

    // Actions
    const actions = document.createElement('div');
    actions.className = 'file-card-actions';

    const dlBtn = document.createElement('button');
    dlBtn.type = 'button';
    dlBtn.className = 'btn-file-action';
    dlBtn.title = 'Download file';
    dlBtn.setAttribute('aria-label', `Download ${f.originalName}`);
    dlBtn.textContent = 'Download';
    dlBtn.addEventListener('click', () => this.downloadFile(f.id, f.originalName));
    actions.appendChild(dlBtn);

    // Show delete only if uploader or room host
    const canDelete = this.isHost || (this.currentUserId && String(this.currentUserId) === String(f.uploaderId));
    if (canDelete) {
      const delBtn = document.createElement('button');
      delBtn.type = 'button';
      delBtn.className = 'btn-file-action btn-file-delete';
      delBtn.title = 'Delete file';
      delBtn.setAttribute('aria-label', `Delete ${f.originalName}`);
      delBtn.textContent = 'Delete';
      delBtn.addEventListener('click', () => this.deleteFile(f.id));
      actions.appendChild(delBtn);
    }

    li.appendChild(actions);
    return li;
  }

  _prependFileCard(f) {
    if (!this.listEl) return;
    if (this.emptyStateEl) this.emptyStateEl.style.display = 'none';

    const node = this._buildCardNode(f);
    this.listEl.insertBefore(node, this.listEl.firstChild);
  }

  _appendFileCard(f) {
    if (!this.listEl) return;
    const node = this._buildCardNode(f);
    this.listEl.appendChild(node);
  }

  _formatSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  destroy() {
    if (this.activeXhr) {
      try {
        this.activeXhr.abort();
      } catch {}
      this.activeXhr = null;
    }
  }
}
