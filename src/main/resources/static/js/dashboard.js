/**
 * MeetApp — Dashboard & Collaboration Hub Controller (dashboard.js)
 * Manages instant meeting creation, room code entry, week strip history,
 * device preview modal, and accessible dialogs.
 */

import { requireAuth } from './layout.js';
import { apiRequest, ApiError } from './api.js';
import { getUser, setSession } from './auth.js';
import { WeekStrip } from './week-strip.js';
import { DeviceChecker } from './devicecheck.js';
import { CONFIG } from './config.js';

let weekStripInstance = null;
let deviceCheckerInstance = null;
let allMeetings = [];

function extractRoomCode(raw) {
  if (!raw) return '';
  const trimmed = raw.trim();
  // Support pasting full room URL: https://domain/room.html?room=DEMO1001
  if (trimmed.includes('?room=')) {
    const parts = trimmed.split('?room=');
    return (parts[1] || '').split('&')[0].trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
  }
  return trimmed.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

async function handleCreateInstantMeeting(btn) {
  try {
    if (btn) btn.disabled = true;

    if (CONFIG.MOCK) {
      window.location.href = 'room.html?room=MOCK1001';
      return;
    }

    const created = await apiRequest('/rooms', { method: 'POST' });
    const code = created.roomCode;

    // Decoupled host entry: call join to establish participant record
    await apiRequest(`/rooms/${encodeURIComponent(code)}/join`, { method: 'POST' });
    window.location.href = `room.html?room=${encodeURIComponent(code)}`;
  } catch (err) {
    console.error('Failed to create instant meeting', err);
    alert('Could not start meeting: ' + (err.message || 'Server error'));
    if (btn) btn.disabled = false;
  }
}

async function handleJoinMeeting(codeOrUrl) {
  const code = extractRoomCode(codeOrUrl);
  if (!code || code.length < 4) {
    alert('Please enter a valid 8-character meeting code or URL.');
    return;
  }

  try {
    if (CONFIG.MOCK) {
      window.location.href = `room.html?room=${encodeURIComponent(code)}`;
      return;
    }

    await apiRequest(`/rooms/${encodeURIComponent(code)}/join`, { method: 'POST' });
    window.location.href = `room.html?room=${encodeURIComponent(code)}`;
  } catch (err) {
    console.warn('Failed to join room', err);
    if (err.status === 404) {
      alert(`Meeting room "${code}" does not exist. Check the code and try again.`);
    } else if (err.status === 409) {
      alert(`Meeting room "${code}" is either full or has already ended.`);
    } else {
      alert('Could not join room: ' + (err.message || 'Error occurred'));
    }
  }
}

function renderMeetingsForDate(selectedDate) {
  const listEl = document.getElementById('meetingsList');
  const emptyEl = document.getElementById('emptyMeetingsState');
  const dateTitleEl = document.getElementById('selectedDateHeading');

  if (!listEl) return;
  listEl.textContent = ''; // Strict reset

  if (dateTitleEl) {
    dateTitleEl.textContent = selectedDate.toLocaleDateString(undefined, {
      weekday: 'long',
      month: 'short',
      day: 'numeric'
    });
  }

  const selectedStr = selectedDate.toDateString();
  const filtered = allMeetings.filter(m => {
    if (!m.createdAt) return false;
    return new Date(m.createdAt).toDateString() === selectedStr;
  });

  if (filtered.length === 0) {
    if (emptyEl) emptyEl.style.display = 'flex';
    return;
  }

  if (emptyEl) emptyEl.style.display = 'none';

  filtered.forEach(m => {
    const li = document.createElement('li');
    li.className = 'meeting-card';

    const left = document.createElement('div');
    left.className = 'meeting-card-left';

    const timeBox = document.createElement('div');
    timeBox.className = 'meeting-time-box';
    const dateObj = new Date(m.createdAt);
    timeBox.textContent = dateObj.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    left.appendChild(timeBox);

    const details = document.createElement('div');
    details.className = 'meeting-details';

    const titleSpan = document.createElement('span');
    titleSpan.className = 'meeting-code-title';
    titleSpan.textContent = m.roomCode;
    details.appendChild(titleSpan);

    const metaSpan = document.createElement('span');
    metaSpan.className = 'meeting-meta-line';
    const roleText = m.role === 'HOST' ? 'Hosted by you' : 'Joined by you';
    metaSpan.textContent = `${roleText} · ${m.participantCount} participant${m.participantCount === 1 ? '' : 's'}`;
    details.appendChild(metaSpan);

    left.appendChild(details);
    li.appendChild(left);

    const right = document.createElement('div');
    right.style.display = 'flex';
    right.style.alignItems = 'center';
    right.style.gap = 'var(--space-3)';

    if (m.isActive) {
      const activeBadge = document.createElement('span');
      activeBadge.className = 'meeting-status-active';
      activeBadge.textContent = '● Active now';
      right.appendChild(activeBadge);

      const rejoinBtn = document.createElement('button');
      rejoinBtn.type = 'button';
      rejoinBtn.className = 'btn btn-secondary';
      rejoinBtn.style.padding = '6px 12px';
      rejoinBtn.style.minHeight = '36px';
      rejoinBtn.textContent = 'Re-join';
      rejoinBtn.addEventListener('click', () => handleJoinMeeting(m.roomCode));
      right.appendChild(rejoinBtn);
    } else {
      const endedBadge = document.createElement('span');
      endedBadge.className = 'meeting-status-ended';
      endedBadge.textContent = 'Ended';
      right.appendChild(endedBadge);
    }

    li.appendChild(right);
    listEl.appendChild(li);
  });
}

async function loadMeetingHistory() {
  if (CONFIG.MOCK) {
    allMeetings = [
      {
        roomCode: 'DEMO1001',
        hostId: 1,
        role: 'HOST',
        isActive: true,
        createdAt: new Date().toISOString(),
        endedAt: null,
        participantCount: 2
      }
    ];
    if (weekStripInstance) {
      weekStripInstance.setMeetings(allMeetings);
      renderMeetingsForDate(weekStripInstance.getSelectedDate());
    }
    return;
  }

  try {
    const { from, to } = weekStripInstance.getWeekRange();
    const query = `?from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`;
    const meetings = await apiRequest(`/users/me/rooms${query}`);
    allMeetings = meetings || [];
    if (weekStripInstance) {
      weekStripInstance.setMeetings(allMeetings);
      renderMeetingsForDate(weekStripInstance.getSelectedDate());
    }
  } catch (err) {
    console.warn('Could not fetch user room history', err);
  }
}

async function initDashboard() {
  if (!requireAuth()) return;

  // 1. Populate user info in header
  const nameEl = document.getElementById('user-welcome-name');
  const emailEl = document.getElementById('user-email');

  let user = getUser();
  if (!user) {
    try {
      user = await apiRequest('/users/me');
      if (user) setSession(null, user);
    } catch {}
  }
  if (user) {
    if (nameEl && user.name) nameEl.textContent = user.name;
    if (emailEl && user.email) emailEl.textContent = user.email;
  }

  // 2. Wire New Meeting button
  const newMeetingBtn = document.getElementById('btnNewMeeting');
  if (newMeetingBtn) {
    newMeetingBtn.addEventListener('click', () => handleCreateInstantMeeting(newMeetingBtn));
  }

  // 3. Wire Join Meeting Form
  const joinInput = document.getElementById('joinInput');
  const joinBtn = document.getElementById('btnJoinMeeting');
  if (joinBtn && joinInput) {
    joinBtn.addEventListener('click', () => handleJoinMeeting(joinInput.value));
    joinInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleJoinMeeting(joinInput.value);
      }
    });
  }

  // 4. Initialize Week Strip
  const weekStripContainer = document.getElementById('weekStripContainer');
  if (weekStripContainer) {
    weekStripInstance = new WeekStrip(weekStripContainer, (selectedDate) => {
      renderMeetingsForDate(selectedDate);
    });
  }

  const btnPrevWeek = document.getElementById('btnPrevWeek');
  const btnNextWeek = document.getElementById('btnNextWeek');
  if (btnPrevWeek && btnNextWeek && weekStripInstance) {
    btnPrevWeek.addEventListener('click', () => {
      weekStripInstance.prevWeek();
      loadMeetingHistory();
    });
    btnNextWeek.addEventListener('click', () => {
      weekStripInstance.nextWeek();
      loadMeetingHistory();
    });
  }

  // 5. Initialize Device Checker Dialog
  const deviceDialog = document.getElementById('deviceDialog');
  const previewVideo = document.getElementById('previewVideo');
  const audioFill = document.getElementById('audioFill');
  const deviceStatus = document.getElementById('deviceStatus');
  const btnCheckDevices = document.getElementById('btnCheckDevices');
  const btnCloseDeviceDialog = document.getElementById('btnCloseDeviceDialog');

  if (deviceDialog && previewVideo && audioFill && deviceStatus) {
    deviceCheckerInstance = new DeviceChecker(deviceDialog, previewVideo, audioFill, deviceStatus);
    if (btnCheckDevices) {
      btnCheckDevices.addEventListener('click', () => deviceCheckerInstance.openAndStart());
    }
    if (btnCloseDeviceDialog) {
      btnCloseDeviceDialog.addEventListener('click', () => deviceDialog.close());
    }
  }

  // 6. Initialize Info & Help Dialogs
  const learnMoreDialog = document.getElementById('learnMoreDialog');
  const btnLearnMore = document.getElementById('btnLearnMore');
  const btnCloseLearnMore = document.getElementById('btnCloseLearnMore');
  if (learnMoreDialog && btnLearnMore) {
    btnLearnMore.addEventListener('click', () => learnMoreDialog.showModal());
    if (btnCloseLearnMore) btnCloseLearnMore.addEventListener('click', () => learnMoreDialog.close());
  }

  const helpDialog = document.getElementById('helpDialog');
  const btnHelp = document.getElementById('btnHelp');
  const btnCloseHelp = document.getElementById('btnCloseHelp');
  if (helpDialog && btnHelp) {
    btnHelp.addEventListener('click', () => helpDialog.showModal());
    if (btnCloseHelp) btnCloseHelp.addEventListener('click', () => helpDialog.close());
  }

  // 7. Load Initial Meetings Data
  await loadMeetingHistory();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initDashboard);
} else {
  initDashboard();
}
