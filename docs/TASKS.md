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
| `MAIL_HOST` / `MAIL_PORT` | Yes (prod) | SMTP host and port (e.g., 587) |
| `MAIL_USERNAME` / `MAIL_PASSWORD`| Yes (prod) | SMTP authentication credentials |
| `MAIL_FROM` | Yes (prod) | Sender address for OTP emails |
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

- [ ] **T-2.1 Database: Migrations & Repositories** (FR-1, FR-3, FR-4, FR-5, FR-15, FR-22)
  - Files: `V1..V7.sql`, `User*.java`, `OtpChallenge*.java`, `Room*.java`, `FileMeta*.java`
  - Steps: Create Flyway migrations V1 to V7; create JPA entities and Spring Data repositories.
  - Done when: Migrations apply cleanly and validation succeeds. Test: Start app against PostgreSQL; verify all 6 tables exist.

- [x] **T-3.1 Auth: JWT & Security Chain** (FR-3, FR-25)
  - Files: `SecurityConfig.java`, `JwtService.java`, `JwtAuthFilter.java`
  - Steps: Implement JwtService for HS256, CustomUserDetailsService, JwtAuthFilter, and stateless SecurityConfig.
  - Done when: Protected endpoint rejects unauthenticated calls with 401. Test: `mvn test -Dtest=AuthServiceTest`.

- [ ] **T-3.2 Auth: User Registration & Step 1 Login** (FR-1, FR-2, FR-3)
  - Files: `AuthController.java`, `AuthService.java`, `RegisterRequest.java`, `LoginRequest.java`, `LoginInitResponse.java`
  - Steps: Validate email/password format; hash password via BCrypt; verify credentials and initiate OTP challenge; return `{otpRequired: true, challengeId}`.
  - Done when: Registration creates user, login triggers OTP challenge, bad password returns 401. Test: MockMvc tests for register, login, duplicate email.

- [ ] **T-3.3 Auth: Email OTP Service & Step 2 Verification** (FR-3A, FR-3B)
  - Files: `OtpService.java`, `OtpGenerator.java`, `EmailService.java`, `SmtpEmailService.java`, `AuthController.java`
  - Steps: Generate 6-digit SecureRandom OTP; hash at rest in `otp_challenges`; send via EmailService (console log in dev); implement `POST /verify-otp` and `POST /resend-otp` with 60s cooldown and 5-attempt limit; issue JWT upon success.
  - Done when: Correct OTP yields JWT; wrong/expired OTP returns 401; 5 failed attempts locks challenge (429). Test: Unit test verifying OTP generation, hashing, attempt counter, and lockout.

- [ ] **T-4.1 Rooms: Lifecycle & Capacity Service** (FR-4, FR-5, FR-6, FR-7)
  - Files: `RoomController.java`, `RoomService.java`, `RoomCodeGenerator.java`, `dto/*`
  - Steps: Implement RoomCodeGenerator (unguessable codes), RoomService (create, count, end), and REST endpoints.
  - Done when: Users create rooms; capacity limit (<=5) and host closing work. Test: `mvn test -Dtest=RoomServiceTest`.

- [ ] **T-5.1 Frontend: Auth Pages & API Client** (FR-1, FR-3, FR-3A, FR-3B)
  - Files: `static/css/*.css`, `static/js/config.js`, `api.js`, `auth.js`, `dashboard.js`, `*.html`
  - Steps: Build api.js with JWT injection; build signup.html, login.html (supporting two-step OTP entry and resend), and dashboard.html.
  - Done when: User registers, completes two-step login with OTP, and navigates to dashboard. Test: Browser test completing signup, step 1 login, OTP verification, and dashboard load.

- [ ] **T-6.1 Signaling: WebSocket & Session Registry** (FR-24, FR-25)
  - Files: `WebSocketConfig.java`, `JwtHandshakeInterceptor.java`, `RoomSessionRegistry.java`
  - Steps: Configure WebSocketConfig with interceptor, thread-safe registry, and handler enforcing verified sender ID.
  - Done when: Authenticated client connects to /ws; unauthenticated fails. Test: `mvn test -Dtest=RoomSessionRegistryTest`.

- [ ] **T-7.1 Video: Dynamic ICE & WebRTC Mesh** (FR-8, FR-9, FR-10, FR-11)
  - Files: `IceConfigController.java`, `WebRtcRelayHandler.java`, `webrtc.js`, `room.js`
  - Steps: Implement IceConfigController serving STUN/TURN; relay offer/answer/ICE; manage peer connections in webrtc.js.
  - Done when: Two separate browsers connect with two-way media. Test: Join room DEMO1 in two windows; verify audio/video.

- [ ] **T-8.1 Screen Share: Capture & Track Swap** (FR-12, FR-13, FR-14)
  - Files: `ScreenShareHandler.java`, `screenshare.js`, `room.js`, `webrtc.js`
  - Steps: Build ScreenShareHandler; implement screenshare.js calling getDisplayMedia() and replaceTrack().
  - Done when: Participant shares screen; stopping restores webcam. Test: Share screen from Client A; verify Client B renders feed.

- [ ] **T-9.1 Chat: Messaging & Unread Counter** (FR-22, FR-23)
  - Files: `ChatService.java`, `ChatHandler.java`, `chat.js`, `room.html`
  - Steps: Persist chat messages; broadcast with 5 msg/s limit; render bubbles and unread badge.
  - Done when: Messages deliver in real-time and unread badge increments. Test: User A sends message; User B receives message with timestamp.

- [ ] **T-10.1 Files: Storage Service & File API** (FR-15, FR-16, FR-17)
  - Files: `FileController.java`, `FileService.java`, `FileValidator.java`, `files.js`
  - Steps: Implement FileValidator (10MB limit, block .exe), LocalStorageService (UUID disk name), and REST endpoints.
  - Done when: Valid files upload and download; .exe rejected. Test: `mvn test -Dtest=FileValidatorTest` and upload 2MB PDF.

- [ ] **T-11.1 Whiteboard: Canvas Sync & Replay** (FR-18, FR-19, FR-20, FR-21)
  - Files: `WhiteboardService.java`, `StrokeEvent.java`, `whiteboard.js`
  - Steps: Buffer strokes per room; broadcast draw events; replay history to late joiners; implement canvas tools in whiteboard.js.
  - Done when: Strokes sync in real time; late joiner receives canvas state. Test: Draw in Browser A; Browser B renders; Browser C syncs on join.

- [ ] **T-12.1 Security: Headers, Rate Limits & Validation** (FR-25, FR-26)
  - Files: `EncryptionService.java`, `SecurityConfig.java`, `SignalingWebSocketHandler.java`
  - Steps: Configure HSTS, CSP, nosniff headers; add rate limits; implement optional AES-GCM.
  - Done when: Security headers appear; flood throttled. Test: `curl -I http://localhost:8080/api/users/me`.

- [ ] **T-13.1 Polish: Responsive UI & Ended Screen** (FR-7, FR-9)
  - Files: `ended.html`, `room.html`, `base.css`, `room.js`, `prejoin.html`
  - Steps: Build ended.html; add camera/mic error banners; handle room-full modal alerts.
  - Done when: UI functions seamlessly on desktop and mobile. Test: Deny camera permission; verify view-only entry.

- [ ] **T-14.1 Deploy: Docker Packaging & Cloud Config** (NFR-2, NFR-4)
  - Files: `Dockerfile`, `docker-compose.yml`, `README.md`, `application-prod.yml`
  - Steps: Build multi-stage Dockerfile; verify full stack locally; document env injection; configure host forwarded headers (`server.forwarded-header-strategy=framework`) for reverse-proxy client IP resolution.
  - Done when: Application container builds and starts with prod profile. Test: `docker compose up --build` and verify /actuator/health is UP.

- [ ] **T-15.1 Submit: Docs, Demo Verification & Review** (FR-1..26)
  - Files: `README.md`, `AGENTS.md`, `docs/TASKS.md`
  - Steps: Update README.md; execute PRD Demo Checklist across devices; verify full test suite.
  - Done when: All tasks checked off, test suite passes, repository clean. Test: `mvn clean test` and verify full demo flow from signup to end room.

## Open Questions
- *Handshake Tickets:* Add one-time ticket endpoint in v2 to remove JWT from query URL?
- *File Retention Window:* Purge files immediately when host calls end or keep for 2 hours?
