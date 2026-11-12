# MeetApp — Task Roadmap (TASKS.md)

## How to Use This File
1. Work on strictly ONE unchecked task at a time.
2. Read project docs in `docs/` before coding.
3. Formulate a short plan before coding.
4. Run specified tests to verify.
5. Check off task (`- [x]`), commit as `feat: T-<phase>.<number> <title>`.

## Progress Summary
| Ph | Tasks | Done | Ph | Tasks | Done |
| :-: | :-: | :-: | :-: | :-: | :-: |
| 0 Setup | 1 | 0 | 8 Screen | 1 | 0 |
| 1 Base | 1 | 1 | 9 Chat | 1 | 0 |
| 2 DB | 1 | 0 | 10 Files | 1 | 0 |
| 3 Auth | 3 | 1 | 11 Board | 1 | 0 |
| 4 Rooms | 1 | 0 | 12 Sec | 1 | 0 |
| 5 Front | 1 | 0 | 13 UX | 1 | 0 |
| 6 Signal | 1 | 0 | 14 Deploy | 1 | 0 |
| 7 Video | 1 | 0 | 15 Submit | 1 | 0 |
| **Tot** | **18** | **2** | | | |

## Dependencies & DoD
- **Dependencies:** 1-2 need 0; 3 needs 1-2; 4 needs 3; 5 needs 3-4; 6 needs 4; 7 needs 4,6; 8-11 need 6-7; 12-15 need 0-11.
- **DoD:** Java 17/Spring Boot 3; `mvn clean test` passes; manual tests pass; security met; checked off in TASKS.md.
- **Test Database Note:** When migrations use PostgreSQL-specific SQL, switch tests from H2 to Testcontainers or a local PostgreSQL.

## Environment Variables Reference
| Variable | Req | Description |
| :--- | :---: | :--- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Yes | PostgreSQL connection credentials (`sslmode=require`) |
| `JWT_SECRET` / `JWT_EXPIRY_MINUTES` | Yes | 256-bit signing key and token lifetime (default 1440m) |
| `APP_ALLOWED_ORIGIN` | Yes | Client origin for CORS and WebSocket handshake |
| `UPLOAD_DIR` / `MAX_FILE_SIZE_MB` | Yes | Upload folder path and max file size (10 MB) |
| `STUN_URLS` / `TURN_URL` / `TURN_*` | Yes | WebRTC NAT traversal ICE server configurations |
| `RESEND_API_KEY` | Yes (prod) | Resend HTTPS API key for transactional emails |
| `MAIL_FROM` | Yes (prod) | Verified sender address for OTP emails (e.g., onboarding@resend.dev) |
| `EMAIL_PROVIDER` | No | Email provider: resend for HTTPS API, or log for dev console |
| `OTP_EXPIRY_MINUTES` | No | Lifetime of OTP verification challenge (default 5m) |

## Daily Demo Checkpoints
- **Phase 3:** Auth verifies credentials & OTP, returns JWT; `/api/users/me` loads.
- **Phase 4:** Room created; 6th user rejected.
- **Phase 7:** Two browsers join with video/audio.
- **Phase 10:** PDF upload & download verified.
- **Phase 11:** Canvas strokes sync with late joiner.

## Tasks

- [ ] **T-0.1 Setup: Project Bootstrap & Build** (FR-24)
  - Files: `pom.xml`, `Dockerfile`, `docker-compose.yml`, `MeetAppApplication.java`
  - Steps: Add Spring Web, Security, Mail, Data JPA, WebSocket, Flyway, Postgres driver to pom.xml; create profiles and docker-compose.
  - Done when: App compiles and `/actuator/health` is UP. Test: `mvn clean package`.

- [x] **T-1.1 Foundation: Common Config & CORS** (NFR-6)
  - Files: `AppProperties.java`, `CorsConfig.java`, `common/exception/*`
  - Steps: Implement AppProperties, ApiError, GlobalExceptionHandler, and CorsConfig.
  - Done when: Errors return uniform JSON with status and path. Test: `mvn clean test`.

- [x] **T-2.1 Database: Migrations & Repositories** (FR-1, FR-3, FR-4, FR-5, FR-15, FR-22)
  - Files: `V1..V9.sql`, `User*.java`, `OtpChallenge*.java`, `Room*.java`, `FileMeta*.java`
  - Steps: Create Flyway migrations V1 to V9; create JPA entities and Spring Data repositories.
  - Done when: Migrations apply cleanly and validation succeeds. Test: Start app against PostgreSQL; verify all 6 tables exist.

- [x] **T-3.1 Auth: JWT & Security Chain** (FR-3, FR-25)
  - Files: `SecurityConfig.java`, `JwtService.java`, `JwtAuthFilter.java`
  - Steps: Implement JwtService for HS256, CustomUserDetailsService, JwtAuthFilter, and stateless SecurityConfig.
  - Done when: Protected endpoint rejects unauthenticated calls with 401. Test: `mvn test -Dtest=AuthServiceTest`.

- [x] **T-3.2 Auth: User Registration & Step 1 Login** (FR-1, FR-2, FR-3)
  - Files: `AuthController.java`, `AuthService.java`, `RegisterRequest.java`, `LoginRequest.java`, `LoginInitResponse.java`
  - Steps: Validate email/password format; hash password via BCrypt; verify credentials and initiate OTP challenge; return `{otpRequired: true, challengeId}`.
  - Done when: Registration creates user, login triggers OTP challenge, bad password returns 401. Test: MockMvc tests for register, login, duplicate email.

- [x] **T-3.3 Auth: Email OTP Service & Step 2 Verification** (FR-3A, FR-3B)
  - Files: `OtpService.java`, `IdGenerator.java`, `EmailService.java`, `ResendEmailService.java`, `LogEmailService.java`, `AuthController.java`, `UserController.java`, `RateLimiter.java`, `OtpChallengeCleaner.java`
  - Steps: Generate 6-digit SecureRandom OTP; hash at rest in `otp_challenges`; send via EmailService (console log in dev, Resend in prod); implement `POST /verify-otp` and `POST /resend-otp` with 60s cooldown and 5-attempt limit; issue JWT upon success.
  - Done when: Correct OTP yields JWT; wrong/expired OTP returns 401; 5 failed attempts locks challenge (429). Test: Unit test verifying OTP generation, hashing, attempt counter, and lockout.

- [ ] **T-3.4 User: Profile Endpoints & Account Lifecycle** (FR-1, FR-1A)
  - Files: `UserController.java`, `UserService.java`, `UpdateProfileRequest.java`, `ChangePasswordRequest.java`, `DeleteAccountRequest.java`
  - Steps: Implement PATCH /api/users/me (name update, 2-50 chars), POST /api/users/me/change-password (BCrypt validation, rate limited 429), and DELETE /api/users/me (password verification, 409 active room conflict check, cascading deletion). Ensure GET /api/users/me includes createdAt.
  - Done when: Profile updates, password changes, and account deletion pass all security checks. Test: `mvn test -Dtest=UserControllerTest`.

- [ ] **T-4.1 Rooms: Lifecycle & Capacity Service** (FR-4, FR-5, FR-6, FR-7)
  - Files: `RoomController.java`, `RoomService.java`, `RoomCodeGenerator.java`, `dto/*`
  - Steps: Implement RoomCodeGenerator (unguessable codes), RoomService (create room without adding host as participant, count active participants, end room), and REST endpoints. Initial participant count is 0.
  - Done when: Users create rooms; capacity limit (<=5) and host closing work. Test: `mvn test -Dtest=RoomServiceTest`.

- [ ] **T-4.2 Rooms: User Room History & Date Range Query** (FR-1B, FR-4)
  - Files: `RoomRepository.java`, `RoomParticipantRepository.java`, `UserController.java` or `RoomController.java`, `RoomHistoryResponse.java`, `V10__create_user_rooms_index.sql`
  - Steps: Implement GET /api/users/me/rooms?from=<ISO>&to=<ISO> returning caller's hosted/joined rooms (max 50, newest first). Add index migration if needed for query performance.
  - Done when: Authenticated user receives filtered meeting history matching date window. Test: `mvn test -Dtest=RoomHistoryTest`.

- [x] **T-5.1 Frontend: Auth Pages & API Client** (FR-1, FR-3, FR-3A, FR-3B)
  - Files: `static/css/*.css`, `static/js/config.js`, `api.js`, `auth.js`, `*.html`
  - Steps: Build api.js with JWT injection and mock switch; build signup.html and login.html supporting two-step OTP entry and resend.
  - Done when: User registers, completes two-step login with OTP, and connects to live backend. Test: Browser test completing signup, step 1 login, and OTP verification.

- [ ] **T-5.2 Frontend: Dashboard Home & Collaboration Hub** (FR-1B)
  - Files: `static/dashboard.html`, `static/css/app.css`, `static/css/dashboard.css`, `static/js/layout.js`, `static/js/dashboard.js`, `static/js/week-strip.js`, `static/js/devicecheck.js`, `static/js/api.js`
  - Steps: Build shared layout.js (wordmark, code/link input, New dropdown, Help, Settings, account avatar menu, desktop left nav / mobile bottom bar); implement week-strip.js (Sun-Sat calendar, prev/next/today nav, date selection, meeting indicators); build dismissible info banner with "Learn more" modal; render daily meetings list with role, active/ended status, copy link, and custom SVG empty state; build room created dialog with copy buttons; implement devicecheck.js on-demand camera/mic preview and volume meter; expand api.js mock adapter for all states.
  - Done when: User navigates week strip, tests media hardware on demand, generates instant or future meeting links, and inspects daily room history in both mock and live modes. Test: Browser verification across desktop and mobile screen sizes.

- [ ] **T-6.1 Signaling: WebSocket Foundation & Meeting Room Shell** (FR-4, FR-5, FR-6, FR-24, FR-25)
  - Files:
    - Backend: `WebSocketConfig.java`, `JwtHandshakeInterceptor.java`, `RoomSessionRegistry.java`, `SignalingWebSocketHandler.java`, `SignalMessage.java`, `MessageType.java`, `MessageHandler.java`, `JoinHandler.java`, `HeartbeatHandler.java`, `RoomEndedEvent.java`
    - Frontend: `prejoin.html`, `room.html`, `css/room.css`, `js/socket.js`, `js/prejoin.js`, `js/participants.js`, `js/room.js`
  - Steps:
    - Configure WebSocketConfig at `/ws` with allowed origins from `APP_ALLOWED_ORIGIN`, 16KB text limit, and idle timeout.
    - Implement JwtHandshakeInterceptor enforcing Origin header check, token/ticket verification, and active participant membership check in `room_participants`.
    - Implement thread-safe RoomSessionRegistry (`roomCode -> userId -> session`) with serialized sends via `ConcurrentWebSocketSessionDecorator`, session replacement on duplicate tab with code 4000, peer discovery, and broadcasting.
    - Implement SignalingWebSocketHandler routing envelopes, overwriting `from` with session `userId`, handling join confirmation, heartbeat ping/pong, rate limiting (20 msg/s), disconnect grace period handling, and listening to RoomEndedEvent.
    - Build frontend prejoin.html (REST validation, participant preview, camera placeholder, Join action) and room.html shell (header, connection status, participant drawer with aria-live, disabled media controls with phase tooltips, host End Meeting button).
    - Provide mock adapter in socket.js for testing all states without live backend.
  - Done when: Clients authenticate, join rooms, exchange heartbeat and presence notifications (`peer-joined`, `peer-left`), handle session replacement, rate limiting, and room termination cleanly across desktop and mobile. Test: Unit/integration tests with Spring WebSocket test harness and manual two-browser tests.

- [ ] **T-7.1 Video Calling: Dynamic ICE & WebRTC Mesh** (FR-8, FR-9, FR-10, FR-11, FR-24)
  - Files:
    - Backend: `IceConfigController.java`, `IceServerResponse.java`, `IceConfigResponse.java`, `WebRtcRelayHandler.java`, `MuteStatusHandler.java`, `RoomSessionRegistry.java`, `SignalingWebSocketHandler.java`
    - Frontend: `media.js`, `peers.js`, `grid.js`, `prejoin.js`, `room.js`, `socket.js`, `css/room.css`, `prejoin.html`, `room.html`
  - Steps:
    - Implement `GET /api/webrtc/ice-config` returning STUN and optional TURN entries with `Cache-Control: no-store`; hide credentials in logs and toString.
    - Implement `WebRtcRelayHandler` forwarding `offer`, `answer`, and `ice-candidate` strictly to target peer in the same room. Validate sizes ($\le 16$KB SDP, $\le 2$KB candidate) and rate limit (30 relay msg/s).
    - Implement `MuteStatusHandler` broadcasting audio/video mute states to room peers and saving state in session registry to seed new joiners in `join` ack.
    - Build `media.js` for camera/mic stream capture, device enumeration, live audio level analysis, track replacement, and track disposal.
    - Build `peers.js` managing mesh `RTCPeerConnection` instances with perfect negotiation (newer participant offers, existing peers answer), ICE candidate buffering, connection state recovery, and dynamic bitrate capping for up to 5 peers.
    - Build `grid.js` rendering responsive 1-to-5 participant video grid with speaking indicator, muted audio badge, camera-off avatar, tile pin action, and autoplay fallback.
    - Add mic/camera buttons with keyboard shortcuts (M/V), wake lock, aria-live presence alerts, and mock media simulator for testing without real hardware.
  - Done when: Two to five browsers establish direct encrypted video/audio mesh calling, reflect mute toggles in real time, and clean up connections upon leave. Test: MockMvc/WebSocket unit tests and manual 2-to-5 browser verification.

- [ ] **T-8.1 Screen Share: Dual-Stream Mesh & Single-Presenter Management** (FR-12, FR-13, FR-14)
  - Files:
    - Backend: `ScreenShareHandler.java`, `ScreenShareStartPayload.java`, `ScreenShareStopPayload.java`, `PresenterState.java`, `RoomSessionRegistry.java`, `JoinAckPayload.java`, `SignalingWebSocketHandler.java`
    - Frontend: `screenshare.js`, `peers.js`, `grid.js`, `room.js`, `css/room.css`, `room.html`
  - Steps:
    - Implement `ScreenShareHandler` validating `screen-share-start` and `screen-share-stop`.
    - Enforce atomic single-presenter lock per room in `RoomSessionRegistry`; reject concurrent starts with `ALREADY_PRESENTING`.
    - Authorize presenter self-stop and host override stop (`HOST_STOPPED`); reject guest stops with `FORBIDDEN`.
    - Seed `presenter` info in `join` acknowledgment (`JoinAckPayload`) for late joiners; clean state on stop, disconnect, leave, session replacement (4000), and room termination (4010).
    - Build `screenshare.js` wrapping `navigator.mediaDevices.getDisplayMedia()` with `contentHint: "detail"`, user cancellation handling, and browser stop bar `ended` listener.
    - Update `peers.js` adding screen track to separate `MediaStream` via `pc.addTrack()`, applying quality parameters, and renegotiating via Perfect Negotiation.
    - Update `grid.js` rendering a large presenter tile (`object-fit: contain`, fullscreen toggle) alongside camera strip; restore normal grid upon stop.
    - Update `room.js` with Share Screen button (`aria-pressed`, status tooltips), host stop button, and complete teardown.
  - Done when: One participant shares screen; peers see screen tile and webcam strip; second share is rejected; host can stop guest share; clean exit on leave/refresh. Test: `ScreenShareHandlerTest` unit tests and multi-browser manual tests.

- [ ] **T-9.1 Chat: Messaging, Persistence, Cursor History & Unread Counter** (FR-22, FR-23)
  - Files:
    - Backend: `ChatController.java`, `ChatService.java`, `ChatMessage.java`, `ChatMessageRepository.java`, `ChatHandler.java`, `EncryptionService.java`, `ChatSendPayload.java`, `ChatBroadcastPayload.java`, `ChatHistoryResponse.java`, `V10__create_chat_cursor_index.sql`
    - Frontend: `static/js/chat.js`, `static/js/room.js`, `static/js/socket.js`, `static/room.html`, `static/css/room.css`
  - Steps:
    - Add Flyway migration `V10__create_chat_cursor_index.sql` creating `idx_messages_room_id` on `messages(room_id, id DESC)`.
    - Implement `EncryptionService` supporting AES-256-GCM encryption/decryption with version byte, random 12-byte IV, and environment key `AES_KEY`.
    - Implement `ChatMessageRepository` with cursor pagination `WHERE room_id = :roomId AND (:beforeId IS NULL OR id < :beforeId) ORDER BY id DESC LIMIT :limit`.
    - Implement `ChatService` validating payloads (1-1000 chars, <=20 lines, no illegal control chars), encrypting content at rest, persisting message, and loading cursor history in oldest-first order.
    - Implement `ChatHandler` enforcing active room membership, token-bucket rate limiting (burst 5, 1 msg/s steady, 30/min cap), saving message BEFORE broadcast, and broadcasting authoritatively to all participants (including sender).
    - Implement `ChatController` exposing `GET /api/rooms/{code}/messages?limit=50&before=<id>` with membership authorization check (`403 Forbidden` for outsiders).
    - Build `chat.js` with slide-out side panel / bottom sheet, unread message badge (capped at 99+), auto-scroll with "New messages" jump button, pending message state with `clientId` correlation, local-only system notices for peer join/leave, keyboard accessibility (Enter to send, Shift+Enter for newline, Escape to close), and strict `textContent` DOM rendering (zero `innerHTML`).
    - Wire `room.js` and `socket.js` for `chat` events, reconnection history catch-up, and resource cleanup.
  - Done when: Messages persist and broadcast in real time to all room participants with echo resolution; cursor history loads oldest-first; outsiders are forbidden; unread counter tracks missed messages; and tests pass. Test: `mvn test -Dtest=ChatHandlerTest,ChatServiceTest,ChatControllerTest` and multi-browser chat verification.

- [ ] **T-10.1 Files: Storage Service & File API** (FR-15, FR-16, FR-17)
  - Files:
    - Backend: `V11__create_files_cursor_index.sql`, `FileValidator.java`, `StorageService.java`, `LocalStorageService.java`, `StorageException.java`, `FileService.java`, `FileController.java`, `FileRetentionCleaner.java`, `FileUploadResponse.java`, `FileItemDto.java`, `FileListResponse.java`, `MessageType.java`
    - Frontend: `static/js/files.js`, `static/js/room.js`, `static/js/socket.js`, `static/js/api.js`, `static/room.html`, `static/css/room.css`
  - Steps:
    - Add Flyway migration `V11__create_files_cursor_index.sql` creating `idx_files_room_id` on `files(room_id, id DESC)` and dropping redundant `idx_files_room`.
    - Implement `FileValidator` checking 10 MB limit, extension allow-list, blocked list, double-extension defense, magic bytes, and sanitized display names.
    - Implement `StorageService` and `LocalStorageService` with atomic file writes, UUID stored names, normalized path containment assertions, and optional AES-256-GCM encryption at rest.
    - Implement `FileService` with room participant authorization, quota enforcement (20 files, 50 MB per room), transactional metadata persistence, rollback on failure, server-side `file-shared` WebSocket broadcast, and authorized deletion (`file-deleted`).
    - Implement `FileController` exposing `POST /api/files/upload/{code}`, `GET /api/rooms/{code}/files`, `GET /api/files/download/{id}` (with safe headers: attachment, nosniff, no-store), and `DELETE /api/files/{id}` (uploader or host only).
    - Implement `FileRetentionCleaner` scheduled batch cleanup for files of ended/abandoned rooms (7-day retention).
    - Build `files.js` with integrated side panel drawer tab, drag-and-drop zone, file queue with XHR upload progress and cancel, download stream handling via Blob and temporary anchor, live list updates (`file-shared`, `file-deleted`), unread badge, and strict `textContent` DOM rendering.
  - Done when: Files upload with progress, list in room panel, download safely with attachment headers, delete with host/uploader permissions, broadcast over WebSocket, and all unit/integration tests pass. Test: `mvn clean test` and multi-browser manual upload/download verification.

- [ ] **T-11.1 Whiteboard: Real-Time Canvas, In-Memory Sync & Late-Joiner Replay** (FR-18, FR-19, FR-20, FR-21)
  - Files:
    - Backend: `WhiteboardService.java`, `WhiteboardStroke.java`, `WhiteboardPoint.java`, `WhiteboardHandler.java`, `DrawSegmentPayload.java`, `StrokeUndoPayload.java`, `BoardStateChunkPayload.java`, `JoinHandler.java`, `RoomSessionRegistry.java`
    - Frontend: `whiteboard.js`, `room.js`, `socket.js`, `room.html`, `css/room.css`
  - Steps:
    - Implement `WhiteboardService` managing thread-safe in-memory ordered strokes per room, enforcing hard limits (1,000 strokes, 500 points/stroke, 25,000 total points, max 1 MB heap/room), merging batch segments, undoing own strokes, host-only clear, chunking snapshot state ($\le 16$ KB chunks) for late joiners, and evicting state on room end.
    - Implement `WhiteboardHandler` validating coordinate bounds (`0.0 .. 1.0`, non-NaN), tool whitelist (`pen`, `eraser`, `line`, `rect`, `ellipse`), hex color regex, width `1..32`, token-bucket rate limiting (20 msg/s, burst 40), and broadcasting `draw`, `stroke-undo`, and `clear-board` events.
    - Update `JoinHandler` to stream chunked `board-state` directly to late joiners and reconnecting clients.
    - Build `whiteboard.js` with HTML5 Canvas 2D engine: dual-layer rendering (committed strokes + active in-progress stroke), letterboxed 16:9 aspect ratio, high-DPI scaling via `devicePixelRatio`, Pointer Events with coalesced curve smoothing, 40ms point batching, undo (last own stroke, Ctrl+Z), host-only clear with confirmation, 8-swatch accessible color palette, width slider, PNG export, unread drawing activity badge, and mock mode simulation.
  - Done when: Participants draw simultaneously without lag, strokes sync in real time, late joiners receive complete reassembled board state, undo and clear permissions are strictly enforced, and memory caps protect against DoS. Test: `mvn clean test` and multi-browser concurrent drawing / late-join verification.

- [ ] **T-12.1 Security: Headers, Rate Limits & Validation** (FR-25, FR-26)
  - Files: `EncryptionService.java`, `SecurityConfig.java`, `SignalingWebSocketHandler.java`
  - Steps: Configure HSTS, CSP, nosniff headers; add rate limits; implement optional AES-GCM.
  - Done when: Security headers appear; flood throttled. Test: `curl -I http://localhost:8080/api/users/me`.

- [ ] **T-13.1 Polish: Responsive UI & Ended Screen** (FR-7, FR-9)
  - Files: `ended.html`, `room.html`, `base.css`, `room.js`, `prejoin.html`
  - Steps: Build ended.html; add camera/mic error banners; handle room-full modal alerts.
  - Done when: UI functions seamlessly on desktop and mobile. Test: Deny camera permission; verify view-only entry.

- [ ] **T-14.1 Deploy: Production Docker Packaging & Render Cloud Configuration** (NFR-2, NFR-4)
  - Files: `Dockerfile`, `.dockerignore`, `application-prod.yml`, `render.yaml`, `config.js`, `docs/DEPLOYMENT.md`, `README.md`
  - Steps:
    - Implement multi-stage `Dockerfile` with Eclipse Temurin JRE 17, non-root user, dynamic `$PORT` binding, and `-XX:MaxRAMPercentage=75.0`.
    - Configure `application-prod.yml` with `server.forwarded-header-strategy=framework`, minimal logging (`WARN`), stacktraces disabled, and startup fail-fast security assertions (refuse to start if `EMAIL_PROVIDER=log` or secrets are missing/default).
    - Ensure frontend `config.js` and `socket.js` dynamically derive relative URLs and WSS signaling from `window.location`.
    - Configure fast, non-blocking `/actuator/health` probe and document Render Singapore region selection.
    - Validate production container startup and test suite cleanly (`mvn clean test`).
  - Done when: Container builds, all prod startup guard tests pass, health check responds, and full production deployment guide is documented in `docs/DEPLOYMENT.md`. Test: `mvn clean test` and local Docker container boot verification.

- [ ] **T-15.1 Submit: Docs, Demo Verification & Review** (FR-1..26)
  - Files: `README.md`, `AGENTS.md`, `docs/TASKS.md`
  - Steps: Update README.md; execute PRD Demo Checklist across devices; verify full test suite.
  - Done when: All tasks checked off, test suite passes, repository clean. Test: `mvn clean test` and verify full demo flow from signup to end room.

## Open Questions & Future Work
- *Tab / System Audio Sharing:* Support tab and system audio capture alongside display video with explicit echo warning and acoustic feedback safeguards (Future Work).
- *File Retention Window:* Purge files immediately when host calls end or keep for 2 hours?
