# MeetApp — Task Roadmap (TASKS.md)

## How to Use This File
1. Work on ONE task at a time, moving sequentially.
2. Read project docs in `docs/` before coding.
3. Formulate a short plan before coding.
4. Run specified tests to verify.
5. Check off task (`- [x]`), commit as `feat: T-<phase>.<number> <title>`.

## Progress Summary
| Ph | Tasks | Done | Ph | Tasks | Done |
| :-: | :-: | :-: | :-: | :-: | :-: |
| 0 Setup | 1 | 0 | 8 Screen | 1 | 0 |
| 1 Base | 1 | 0 | 9 Chat | 1 | 0 |
| 2 DB | 1 | 0 | 10 Files | 1 | 0 |
| 3 Auth | 2 | 0 | 11 Board | 1 | 0 |
| 4 Rooms | 1 | 0 | 12 Sec | 1 | 0 |
| 5 Front | 1 | 0 | 13 UX | 1 | 0 |
| 6 Signal | 1 | 0 | 14 Deploy | 1 | 0 |
| 7 Video | 1 | 0 | 15 Submit | 1 | 0 |
| **Tot** | **17** | **0** | | | |

## Dependencies & Definition of Done
- **Deps:** 1-2 need 0; 3 needs 1-2; 4 needs 3; 5 needs 3-4; 6 needs 4; 7 needs 4,6; 8-11 need 6-7; 12-15 need 0-11.
- **DoD:** Java 17/Spring Boot 3; `mvn clean test` passes; manual tests pass; security met.

## Daily Demo Checkpoints
- **Phase 3:** Auth returns JWT; `/api/users/me` loads.
- **Phase 4:** Room created; 6th user rejected.
- **Phase 7:** Two browsers join with video/audio.
- **Phase 10:** PDF upload & download verified.
- **Phase 11:** Canvas strokes sync with late joiner.

## Tasks

- [ ] **T-0.1 Setup: Project Bootstrap & Build**
  - Goal: Initialize project and container configs.
  - Implements: FR-24
  - Files: `pom.xml`, `Dockerfile`, `docker-compose.yml`, `MeetAppApplication.java`
  - Steps:
    - Setup dependencies in pom.xml.
    - Create config profiles and docker-compose.
    - Create MeetAppApplication class.
  - Done when: App compiles and `/actuator/health` is UP.
  - Test: `mvn clean package`.

- [ ] **T-1.1 Foundation: Common Config & CORS**
  - Goal: Establish global exception handling and CORS.
  - Implements: NFR-6
  - Files: `AppProperties.java`, `CorsConfig.java`, `common/exception/*`
  - Steps:
    - Implement AppProperties for config values.
    - Create ApiError and GlobalExceptionHandler.
    - Configure CorsConfig for APP_ALLOWED_ORIGIN.
  - Done when: Errors return uniform JSON with status and path.
  - Test: `mvn clean test`.

- [ ] **T-2.1 Database: Migrations & Repositories**
  - Goal: Define schema migrations and JPA repositories.
  - Implements: FR-1, FR-4, FR-5, FR-15, FR-22
  - Files: `V1..V6.sql`, `User*.java`, `Room*.java`, `FileMeta*.java`
  - Steps:
    - Create Flyway migrations V1 to V6.
    - Create JPA entities with BIGINT PKs and LAZY links.
    - Create Spring Data JPA repositories.
  - Done when: Migrations apply and validation succeeds.
  - Test: Start app against PostgreSQL; verify 5 tables exist.

- [ ] **T-3.1 Auth: JWT & Security Chain**
  - Goal: Implement token service, auth filter, and security chain.
  - Implements: FR-3, FR-25
  - Files: `SecurityConfig.java`, `JwtService.java`, `JwtAuthFilter.java`
  - Steps:
    - Implement JwtService for HS256 and 24h expiry.
    - Build CustomUserDetailsService loading user by email.
    - Implement JwtAuthFilter and stateless SecurityConfig.
  - Done when: Protected endpoint rejects unauthenticated calls with 401.
  - Test: `mvn test -Dtest=AuthServiceTest`.

- [ ] **T-3.2 Auth: Register & Login Endpoints**
  - Goal: Build registration, login, and profile lookup APIs.
  - Implements: FR-1, FR-2
  - Files: `AuthController.java`, `AuthService.java`, `RegisterRequest.java`, `UserController.java`
  - Steps:
    - Implement RegisterRequest with validation.
    - Implement AuthService validating email and hashing password.
    - Build AuthController endpoints and GET /api/users/me.
  - Done when: Registration creates user, login returns JWT, bad auth returns 401.
  - Test: MockMvc tests for register, login, duplicate.

- [ ] **T-4.1 Rooms: Lifecycle & Capacity Service**
  - Goal: Implement room creation, lookup, capacity, and closing.
  - Implements: FR-4, FR-5, FR-6, FR-7
  - Files: `RoomController.java`, `RoomService.java`, `RoomCodeGenerator.java`, `dto/*`
  - Steps:
    - Implement RoomCodeGenerator generating unguessable codes.
    - Build RoomService for creation, participant count, and closing.
    - Implement POST /api/rooms, GET /api/rooms/{code}, POST /end.
  - Done when: Users create rooms; capacity limits and host closing work.
  - Test: `mvn test -Dtest=RoomServiceTest`.

- [ ] **T-5.1 Frontend: Auth Pages & API Client**
  - Goal: Build client HTTP wrapper, auth views, and dashboard.
  - Implements: FR-1, FR-3
  - Files: `static/css/*.css`, `static/js/config.js`, `api.js`, `auth.js`, `dashboard.js`, `*.html`
  - Steps:
    - Implement config.js and api.js with JWT injection.
    - Create signup.html and login.html with auth.js.
    - Create dashboard.html with Create and Join actions.
  - Done when: User can register, log in, store JWT, and open dashboard.
  - Test: Browser test completing signup, login, and room creation.

- [ ] **T-6.1 Signaling: WebSocket & Session Registry**
  - Goal: Establish signaling WebSocket with JWT handshake and registry.
  - Implements: FR-24, FR-25
  - Files: `WebSocketConfig.java`, `JwtHandshakeInterceptor.java`, `RoomSessionRegistry.java`
  - Steps:
    - Configure WebSocketConfig with JwtHandshakeInterceptor.
    - Build thread-safe RoomSessionRegistry.
    - Implement SignalingWebSocketHandler enforcing from ID.
  - Done when: Authenticated client connects to /ws; unauthenticated fails.
  - Test: `mvn test -Dtest=RoomSessionRegistryTest`.

- [ ] **T-7.1 Video: Dynamic ICE & WebRTC Mesh**
  - Goal: Serve ICE config and orchestrate mesh WebRTC calls.
  - Implements: FR-8, FR-9, FR-10, FR-11
  - Files: `IceConfigController.java`, `WebRtcRelayHandler.java`, `webrtc.js`, `room.js`
  - Steps:
    - Build IceConfigController serving STUN/TURN credentials.
    - Implement WebRtcRelayHandler relaying offer, answer, and ICE.
    - Implement webrtc.js managing mesh peer connections.
  - Done when: Two separate browsers connect with two-way media.
  - Test: Join room DEMO1 in two windows; verify audio/video.

- [ ] **T-8.1 Screen Share: Capture & Track Swap**
  - Goal: Implement screen capture with track replacement.
  - Implements: FR-12, FR-13, FR-14
  - Files: `ScreenShareHandler.java`, `screenshare.js`, `room.js`, `webrtc.js`
  - Steps:
    - Build ScreenShareHandler broadcasting start/stop events.
    - Implement screenshare.js calling getDisplayMedia() and replaceTrack().
    - Add UI indicators; revert to camera on stop; mobile view-only.
  - Done when: Participant shares screen; stopping restores webcam.
  - Test: Share screen from Client A; verify Client B renders feed.

- [ ] **T-9.1 Chat: Messaging & Unread Counter**
  - Goal: Implement group chat broadcasting, persistence, and unread badges.
  - Implements: FR-22, FR-23
  - Files: `ChatService.java`, `ChatHandler.java`, `chat.js`, `room.html`
  - Steps:
    - Implement ChatService persisting messages to database.
    - Build ChatHandler broadcasting messages with 5 msg/s limit.
    - Build chat.js rendering text and updating unread badge.
  - Done when: Messages deliver in real-time and unread badge increments.
  - Test: User A sends message; User B receives message with timestamp.

- [ ] **T-10.1 Files: Storage Service & File API**
  - Goal: Build file validation, local disk storage, drawer, and download.
  - Implements: FR-15, FR-16, FR-17
  - Files: `FileController.java`, `FileService.java`, `FileValidator.java`, `files.js`
  - Steps:
    - Implement FileValidator enforcing 10MB limit and blocking .exe.
    - Implement LocalStorageService saving files to /uploads with UUID.
    - Build FileController upload, list, and download endpoints.
  - Done when: Valid files upload and download; .exe rejected.
  - Test: `mvn test -Dtest=FileValidatorTest` and upload 2MB PDF.

- [ ] **T-11.1 Whiteboard: Canvas Sync & Replay**
  - Goal: Implement canvas drawing, stroke sync, and late-joiner replay.
  - Implements: FR-18, FR-19, FR-20, FR-21
  - Files: `WhiteboardService.java`, `StrokeEvent.java`, `whiteboard.js`
  - Steps:
    - Build WhiteboardService buffering strokes per active room.
    - Implement WhiteboardHandler broadcasting draw and sending state.
    - Implement whiteboard.js with pen, eraser, color, and Clear.
  - Done when: Strokes sync in real time; late joiner receives canvas state.
  - Test: Draw in Browser A; Browser B renders; Browser C syncs on join.

- [ ] **T-12.1 Security: Headers, Rate Limits & Validation**
  - Goal: Configure HTTP security headers and socket rate limits.
  - Implements: FR-25, FR-26
  - Files: `EncryptionService.java`, `SecurityConfig.java`, `SignalingWebSocketHandler.java`
  - Steps:
    - Configure HSTS, CSP, and nosniff headers.
    - Add rate limiting for draw (60/s) and chat (5/s).
    - Implement optional EncryptionService AES-GCM.
  - Done when: Security headers appear; flood throttled.
  - Test: `curl -I http://localhost:8080/api/users/me`.

- [ ] **T-13.1 Polish: Responsive UI & Ended Screen**
  - Goal: Implement responsive layouts, hardware error handling, and ended screen.
  - Implements: FR-7, FR-9
  - Files: `ended.html`, `room.html`, `base.css`, `room.js`, `prejoin.html`
  - Steps:
    - Implement ended.html with summary and return button.
    - Add error banners for denied camera/mic.
    - Add modal alert for full room rejection.
  - Done when: UI functions on desktop and mobile.
  - Test: Deny camera permission; verify view-only entry.

- [ ] **T-14.1 Deploy: Docker Packaging & Cloud Config**
  - Goal: Package multi-stage Docker container and configure Render/Railway deploy.
  - Implements: NFR-2, NFR-4
  - Files: `Dockerfile`, `docker-compose.yml`, `README.md`, `application-prod.yml`
  - Steps:
    - Build multi-stage Dockerfile with Temurin 17 JRE base.
    - Verify Docker Compose runs full stack locally against PostgreSQL.
    - Document env injection for Render and Railway.
  - Done when: Application container builds and starts with prod profile.
  - Test: `docker compose up --build` and verify /actuator/health is UP.

- [ ] **T-15.1 Submit: Docs, Demo Verification & Review**
  - Goal: Finalize user documentation, execute verification checklist, and cleanup.
  - Implements: FR-1..26
  - Files: `README.md`, `AGENTS.md`, `docs/TASKS.md`
  - Steps:
    - Update README.md with setup and screenshots.
    - Execute PRD Demo Checklist across two physical devices.
    - Verify all unit and integration tests execute successfully.
  - Done when: All tasks checked off, test suite passes, repository clean.
  - Test: `mvn clean test` and verify full demo flow from signup to end room.

## Blocked or Deferred Tasks
*(None currently. All tasks are active and actionable.)*

## Open Questions
- *Handshake Tickets:* Add one-time ticket endpoint in v2 to remove JWT from query URL?
- *File Retention Window:* Purge files immediately when host calls end or keep for 2 hours?
