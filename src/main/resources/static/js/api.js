/**
 * MeetApp — HTTP Client & API Service (api.js)
 * Clean Fetch wrapper with unified error handling, JWT attachment, and mock adapter.
 */

import { CONFIG } from './config.js';

/**
 * Custom API Error class wrapping standardized backend error envelopes.
 */
export class ApiError extends Error {
  constructor(status, error, message, fieldErrors = []) {
    super(message || error || 'An unexpected error occurred');
    this.name = 'ApiError';
    this.status = status;
    this.error = error;
    this.fieldErrors = fieldErrors;
  }
}

/**
 * Masks an email for safe display (e.g. "mohit@example.com" -> "m***t@example.com").
 */
function maskEmail(email) {
  if (!email || !email.includes('@')) return 'your email';
  const [local, domain] = email.split('@');
  if (local.length <= 2) {
    return `${local[0]}***@${domain}`;
  }
  return `${local[0]}***${local[local.length - 1]}@${domain}`;
}

/**
 * Mock API simulation router.
 */
async function mockFetch(endpoint, method, body, token) {
  // Simulate network latency (250ms)
  await new Promise((resolve) => setTimeout(resolve, 250));

  // 1. POST /api/auth/register
  if (endpoint === '/auth/register' && method === 'POST') {
    if (body.email && body.email.toLowerCase() === 'existing@example.com') {
      throw new ApiError(409, 'Conflict', 'An account with this email address already exists.');
    }
    return {
      id: 1,
      name: body.name,
      email: body.email
    };
  }

  // 2. POST /api/auth/login
  if (endpoint === '/auth/login' && method === 'POST') {
    // Expected mock password for success: "Test1234"
    if (body.password === 'Test1234') {
      return {
        otpRequired: true,
        challengeId: `ch_mock_${Date.now()}`,
        maskedEmail: maskEmail(body.email),
        expiresInSeconds: 300
      };
    }
    // Generic error to prevent email enumeration
    throw new ApiError(401, 'Unauthorized', 'Email or password is incorrect.');
  }

  // 3. POST /api/auth/verify-otp
  if (endpoint === '/auth/verify-otp' && method === 'POST') {
    const code = String(body.otp || '').trim();

    if (code === '123456') {
      return {
        token: `mock_jwt_header.${btoa(JSON.stringify({ sub: 1, email: 'user@example.com', exp: Math.floor(Date.now() / 1000) + 86400 }))}.signature`,
        user: {
          id: 1,
          name: 'Demo User',
          email: 'user@example.com'
        }
      };
    }

    if (code === '000000') {
      throw new ApiError(401, 'Unauthorized', 'Verification code has expired. Please request a new code.');
    }

    if (code === '999999') {
      throw new ApiError(429, 'Too Many Requests', 'Too many failed attempts. This challenge has been locked for your security.');
    }

    throw new ApiError(401, 'Unauthorized', 'Incorrect verification code. Please check your email and try again.');
  }

  // 4. POST /api/auth/resend-otp
  if (endpoint === '/auth/resend-otp' && method === 'POST') {
    return {
      message: 'Verification code resent successfully.',
      expiresInSeconds: 300
    };
  }

  // 5. GET /api/users/me
  if (endpoint === '/users/me' && method === 'GET') {
    if (!token) {
      throw new ApiError(401, 'Unauthorized', 'Authentication token missing or expired.');
    }
    return {
      id: 1,
      name: 'Demo User',
      email: 'user@example.com'
    };
  }

  // 6. POST /api/ws-ticket
  if (endpoint === '/ws-ticket' && method === 'POST') {
    return {
      ticket: `mock_ticket_${Date.now()}`,
      expiresInSeconds: 30
    };
  }

  // 7. GET /api/webrtc/ice-config
  if (endpoint === '/webrtc/ice-config' && method === 'GET') {
    return {
      iceServers: [
        { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }
      ]
    };
  }

  // 8. POST /api/rooms
  if (endpoint === '/rooms' && method === 'POST') {
    return {
      roomCode: 'DEMO1001',
      host: { id: 1, name: 'Demo User' },
      activeParticipantCount: 0,
      active: true,
      createdAt: new Date().toISOString()
    };
  }

  // 9. GET /api/rooms/{code}
  if (endpoint.startsWith('/rooms/') && method === 'GET') {
    const code = endpoint.replace('/rooms/', '');
    return {
      roomCode: code.toUpperCase(),
      host: { id: 1, name: 'Demo User' },
      activeParticipantCount: 2,
      active: true,
      createdAt: new Date().toISOString()
    };
  }

  // 10. POST /api/rooms/{code}/join
  if (endpoint.includes('/join') && method === 'POST') {
    return { message: 'Joined successfully' };
  }

  // 11. POST /api/rooms/{code}/leave
  if (endpoint.includes('/leave') && method === 'POST') {
    return { message: 'Left successfully' };
  }

  // 12. POST /api/rooms/{code}/end
  if (endpoint.includes('/end') && method === 'POST') {
    return { message: 'Room ended' };
  }

  // 13. GET /api/rooms/{code}/messages
  if (endpoint.includes('/messages') && method === 'GET') {
    if (endpoint.includes('before=')) {
      return {
        messages: [
          {
            id: 99,
            senderId: 2,
            senderName: 'Priya Sharma',
            text: 'Shared the agenda on https://meetapp.com/agenda',
            sentAt: new Date(Date.now() - 600000).toISOString()
          }
        ]
      };
    }
    return {
      messages: [
        {
          id: 101,
          senderId: 2,
          senderName: 'Priya Sharma',
          text: 'Welcome everyone to the sprint review!',
          sentAt: new Date(Date.now() - 300000).toISOString()
        },
        {
          id: 102,
          senderId: 3,
          senderName: 'Aarav Patel',
          text: 'Glad to be here. Audio is working well.',
          sentAt: new Date(Date.now() - 180000).toISOString()
        }
      ]
    };
  }

  throw new ApiError(404, 'Not Found', `Mock endpoint ${method} ${endpoint} not found.`);
}

/**
 * Universal API Request Method.
 */
export async function apiRequest(endpoint, { method = 'GET', body = null, headers = {} } = {}) {
  // Retrieve token from session storage safely
  const token = sessionStorage.getItem('meetapp_token');

  // In Mock Mode, route directly to the local mock simulator
  if (CONFIG.MOCK) {
    return mockFetch(endpoint, method.toUpperCase(), body, token);
  }

  // Live Mode: Dispatch real HTTP request
  const requestHeaders = {
    'Accept': 'application/json',
    ...headers
  };

  if (body && !(body instanceof FormData)) {
    requestHeaders['Content-Type'] = 'application/json';
  }

  if (token) {
    requestHeaders['Authorization'] = `Bearer ${token}`;
  }

  const url = `${CONFIG.API_BASE_URL}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;

  const response = await fetch(url, {
    method,
    headers: requestHeaders,
    body: body ? (body instanceof FormData ? body : JSON.stringify(body)) : null
  });

  // Parse JSON response body if available
  let data = null;
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    try {
      data = await response.json();
    } catch {
      data = null;
    }
  }

  if (!response.ok) {
    const status = response.status;
    const errorTitle = (data && data.error) || response.statusText;
    const errorMessage = (data && data.message) || 'Request failed';
    const fieldErrors = (data && data.fieldErrors) || [];

    throw new ApiError(status, errorTitle, errorMessage, fieldErrors);
  }

  return data;
}
