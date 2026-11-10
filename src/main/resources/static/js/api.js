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
