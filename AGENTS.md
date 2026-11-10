# AGENTS.md — MeetApp AI Engineering Guide

## 1. Project Overview
MeetApp is a real-time video conferencing and collaboration web application providing multi-user video calling, screen sharing, file sharing, a collaborative whiteboard, and group chat. Developed as an internship project (CodeAlpha Task 4: Real-Time Communication App), it demonstrates secure enterprise-grade full-stack engineering, media streaming, and real-time signaling. The architecture pairs a Spring Boot signaling backend with browser-native WebRTC mesh communication.

## 2. Tech Stack
- **Backend:** Java 17, Spring Boot 3.x, Maven
- **Spring Modules:** Spring Web, Spring WebSocket, Spring Security, Spring Data JPA, Bean Validation
- **Authentication & Security:** JWT (`jjwt`) with BCrypt password hashing
- **Database:** PostgreSQL (or MySQL, configured via Spring profiles)
- **Signaling:** Spring WebSocket (plain WebSocket handler or STOMP)
- **Media Streaming:** Browser WebRTC API (`RTCPeerConnection`, `getUserMedia`, `getDisplayMedia`), Mesh topology, STUN/TURN configured via `application.properties`
- **Frontend:** Plain HTML5, CSS3, and modular JavaScript served from `src/main/resources/static`
- **Whiteboard:** HTML5 Canvas synchronized over WebSocket
- **File Storage:** Local disk directory with file metadata tracked in the database

## 3. Project Map & Folder Structure

Detailed map with every file and responsibility: see docs/ARCHITECTURE.md section 4
Use exactly this structure. Do not create packages or folders outside it without asking.

```
meetapp/
├── pom.xml, Dockerfile, docker-compose.yml, .env.example, .gitignore, README.md, AGENTS.md
├── docs/ (PRD.md, ARCHITECTURE.md, DATABASE.md, API.md, SECURITY.md, TASKS.md)
└── src/
    ├── main/
    │   ├── java/com/codealpha/meetapp/
    │   │   ├── MeetAppApplication.java
    │   │   ├── common/ (config/, exception/, util/)
    │   │   ├── security/
    │   │   ├── auth/ (dto/)
    │   │   ├── otp/ (dto/)
    │   │   ├── email/
    │   │   ├── user/ (dto/)
    │   │   ├── room/ (dto/)
    │   │   ├── signaling/ (message/, handler/)
    │   │   ├── webrtc/ (dto/)
    │   │   ├── whiteboard/
    │   │   ├── chat/ (dto/)
    │   │   ├── file/ (storage/, dto/)
    │   │   └── crypto/
    │   └── resources/
    │       ├── application.yml, application-dev.yml, application-prod.yml
    │       ├── db/migration/ (V1__users.sql .. V7__create_otp_challenges.sql)
    │       └── static/
    │           ├── index.html, login.html, signup.html, dashboard.html, prejoin.html, room.html, ended.html
    │           ├── css/ (base.css, auth.css, room.css)
    │           └── js/ (config.js, api.js, auth.js, dashboard.js, socket.js, webrtc.js, room.js, screenshare.js, chat.js, files.js, whiteboard.js)
    └── test/java/com/codealpha/meetapp/ (mirrors main feature packages)
```

### Database Hosting
InsForge hosts PostgreSQL only; no InsForge Auth, edge functions or auto APIs; Flyway owns the schema (`ddl-auto=validate`); env vars only (`DB_URL`, `DB_USER`, `DB_PASSWORD`); `sslmode=require`. No table may be publicly exposed through InsForge auto APIs without row-level security. Fallback: self-hosted PostgreSQL or another Postgres host.

## 4. Read-First Rule
- Always inspect and read `docs/PRD.md`, `docs/ARCHITECTURE.md`, `docs/API.md`, `docs/DATABASE.md`, `docs/SECURITY.md`, and `docs/TASKS.md` before starting any task.
- If any required documentation file is missing, never assume or fabricate requirements; explicitly state which file is missing and ask the user for guidance before proceeding.

## 5. Workflow Rules
- Always work on strictly ONE unchecked task from `docs/TASKS.md` at a time.
- Always produce a concise implementation plan before writing code; wait for explicit user approval on large architectural or multi-file changes.
- After finishing a task, mark the task as checked (`- [x]`) in `docs/TASKS.md` and provide a concise summary of all files created or modified.
- Never refactor or touch unrelated code outside the immediate scope of the current task.
- Never add a new library or Maven dependency without asking and obtaining explicit approval.
- If any requirement is ambiguous, ask a clarifying question instead of making assumptions.

## 6. Coding Standards (Java)
- Always enforce a strict layered architecture: `Controller -> Service -> Repository`.
- Always use DTOs for incoming requests and outgoing responses; never expose JPA entities directly to the client.
- Always use constructor injection (`@RequiredArgsConstructor` or explicit constructors); never use field injection (`@Autowired` on fields).
- Always validate all input payloads using Bean Validation annotations (`@Valid`, `@NotNull`, `@NotBlank`, `@Size`, etc.).
- Always implement a centralized `@RestControllerAdvice` global exception handler returning a consistent JSON error format (`timestamp`, `status`, `error`, `message`, `path`).
- Always write meaningful names and small, single-purpose methods; add comments only where logic is complex and not self-explanatory.
- Always use SLF4J logging (`log.info()`, `log.warn()`, `log.error()`); never use `System.out.println` or `e.printStackTrace()`.

## 7. Coding Standards (Frontend)
- Always organize client logic into modular JavaScript files (`config.js`, `auth.js`, `room.js`, `webrtc.js`, `whiteboard.js`, `chat.js`, `files.js`).
- Never hardcode backend URLs, ports, or credentials; always read configurations from a central `config.js`.
- Always build responsive layouts that function seamlessly on mobile, tablet, and desktop browsers.
- Always handle errors and permission rejections for camera, microphone, and screen sharing gracefully with clear, user-friendly messages.

## 8. Security Rules (Non-Negotiable)
- Never hardcode secrets, passwords, or JWT keys; always load them from environment variables with placeholders in `application.properties`.
- Always hash passwords using BCrypt; never log passwords, raw credentials, or JWT tokens.
- Always verify JWT on every protected REST endpoint AND during the WebSocket handshake/connection initialization.
- Always restrict CORS strictly to the configured frontend origin.
- Always validate uploaded file type (MIME type + extension whitelist) and file size; sanitize filenames to prevent path traversal; completely block executable files.
- Always enforce HTTPS and WSS protocols for production deployments.
- Always enforce authorization checks ensuring users may only access rooms and files they are permitted to access.

## 9. WebRTC and WebSocket Rules
- The server only relays signaling messages; it must never touch, process, or proxy raw media streams.
- Supported signaling events: `join`, `leave`, `offer`, `answer`, `ice-candidate`, `chat`, `draw`, `clear-board`, `screen-share-start`, `screen-share-stop`, `file-shared`, `mute-status`.
- Always follow the message schema and payload contracts defined in `docs/API.md` exactly.
- Always clean up peer connections, media tracks, and server session states when a user leaves or disconnects.
- Always buffer whiteboard drawing history on the server or room host and transmit the current canvas state to late joiners upon entering the room.

## 10. Testing and Verification
- Always provide manual test steps after implementing each feature (e.g., "Open two browser windows, join the same room with code 'DEMO1', confirm two-way audio/video and chat delivery").
- Always write unit tests for service layer business logic and security components where practical.
- Always execute and pass `mvn clean test` before marking any task as complete in `docs/TASKS.md`.

## 11. Git Rules
- Always make atomic, well-defined commits using standard prefixes: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`.
- Never commit secrets, tokens, `.env` files, or the runtime local `uploads/` folder.
- Always maintain an updated `.gitignore` excluding `/target/`, `/uploads/`, `.idea/`, `.vscode/`, and OS-specific files.

## 12. Do's and Don'ts

### Do's:
- Always read all relevant docs in `/docs` before writing any code.
- Always validate inputs on both frontend and backend.
- Always clean up WebRTC tracks and WebSocket sessions on disconnect.
- Always encapsulate business logic inside service classes.
- Always adhere to standard HTTP status codes and uniform JSON error structures.

### Don'ts:
- Never write application code without an assigned task in `docs/TASKS.md`.
- Never expose JPA entity models directly in REST responses or WebSocket messages.
- Never use field injection (`@Autowired` on fields).
- Never log sensitive credentials, passwords, or authentication tokens.
- Never add dependencies or libraries without prior approval.
- Never modify or refactor code outside the scope of the current task.

## 13. Definition of Done
A task is complete ONLY when:
1. Code compiles without errors under Java 17 and Spring Boot 3.x.
2. All unit and integration tests pass successfully (`mvn clean test`).
3. The feature is verified using clear, repeatable manual testing steps.
4. Security constraints are met (no hardcoded secrets, input validated, permissions enforced).
5. The task is checked off in `docs/TASKS.md` and modified files are summarized.
