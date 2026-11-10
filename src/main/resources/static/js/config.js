/**
 * MeetApp — Central Client Configuration (config.js)
 * The only place for API base URLs, WebSocket paths, and mock environment flags.
 */

export const CONFIG = {
  // Base endpoint for backend REST services
  API_BASE_URL: '/api',

  // WebSocket signaling endpoint (relative to current host)
  WS_PATH: '/ws',

  // Mock mode flag: when true, api.js intercepts requests and returns simulated responses.
  // Set to false when connecting to the live Spring Boot signaling backend.
  MOCK: false
};
