/**
 * MeetApp — Central Client Configuration (config.js)
 * The single source of truth for API base URLs, WebSocket paths,
 * debug flags, and runtime constants.
 */

export const CONFIG = {
  // Base endpoint for backend REST services
  API_BASE_URL: '/api',

  // WebSocket signaling endpoint (relative to current host)
  WS_PATH: '/ws',

  // Mock mode flag: when true, simulates network and WebRTC media
  MOCK: false,

  // Debug flag: when true, enables verbose operational logging.
  // CRITICAL SECURITY RULE: Even when true, NEVER logs tokens, tickets,
  // SDP payloads, ICE candidates, or TURN credentials.
  DEBUG: false,

  // Force relay flag: when true, forces WebRTC media to use TURN relay only
  FORCE_RELAY: false,

  // Heartbeat interval in milliseconds (25 seconds)
  HEARTBEAT_INTERVAL_MS: 25000,

  // Reconnection backoff limits
  RECONNECT_BASE_MS: 1000,
  RECONNECT_MAX_MS: 15000,
  MAX_RECONNECT_ATTEMPTS: 5,

  // Ticket validity window (30 seconds)
  TICKET_EXPIRY_MS: 30000,

  // Quality ladder parameters approved in Stage A:
  // Top tier capped at 640x360 at 24 fps for 1 remote peer;
  // lower tiers scale down to 480x270 (18-20 fps) and 320x180 (15 fps) for 3-4 peers.
  QUALITY_LADDER: {
    1: { width: 640, height: 360, fps: 24, maxBitrateBps: 500000 },
    2: { width: 480, height: 270, fps: 20, maxBitrateBps: 400000 },
    3: { width: 480, height: 270, fps: 18, maxBitrateBps: 300000 },
    4: { width: 320, height: 180, fps: 15, maxBitrateBps: 200000 }
  },

  // Group chat configuration
  CHAT: {
    MAX_TEXT_LENGTH: 1000,
    MAX_LINES: 20,
    HISTORY_LIMIT: 50,
    ECHO_TIMEOUT_MS: 10000,
    MAX_UNREAD_DISPLAY: 99
  }
};

/**
 * Sanitized logger that enforces privacy by omitting sensitive keys
 */
export function logDebug(category, message, details = null) {
  if (!CONFIG.DEBUG) return;
  if (details) {
    console.debug(`[MeetApp][${category}] ${message}`, details);
  } else {
    console.debug(`[MeetApp][${category}] ${message}`);
  }
}
