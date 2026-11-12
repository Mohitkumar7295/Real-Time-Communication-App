# MeetApp — Security Architecture & Guidelines (SECURITY.md)

## 1. Security Goals & Scope
MeetApp safeguards five core domains:
- **Accounts:** Protecting credentials and authentication sessions via two-step login (BCrypt + Email OTP).
- **Rooms:** Restricting meeting access to intended participants and enforcing a 5-user capacity limit (FR-7).
- **Files:** Preventing unauthorized downloads, path traversal, and executable uploads (FR-16).
- **In-Room Chat & Canvas:** Preventing Cross-Site Scripting (XSS), message spoofing, and flood DoS.
- **Media Streams:** Guaranteeing cryptographic privacy and integrity for P2P media via DTLS-SRTP.

Adversary model covers: anonymous external attackers, logged-in users probing other rooms, and uninvited intruders.

## 2. Threat Model
| Threat | Example Attack | Impact | Mitigation | Implementation |
| :--- | :--- | :--- | :--- | :--- |
| **Credential Stuffing** | Dictionary attack on `/api/auth/login` | Account takeover | Rate limit (5 req/min), generic errors | `security/RateLimitFilter` |
| **OTP Brute-Force** | Guessing 6-digit OTP code | 2FA bypass | Invalidate after 5 failed attempts, 5-min expiry | `otp/OtpService` |
| **OTP Replay / Intercept**| Reusing expired or valid OTP | Account hijack | Single-use flag, hashed at rest, constant-time compare | `otp/OtpService` |
| **Forged / Tampered JWT** | Modifying user claims in token | Privilege escalation | HMAC-SHA256 signature check (256-bit key) | `security/JwtService` |
| **Room Code Guessing** | Iterating codes | Room snooping | Unguessable random space ($36^6$ combos) | `room/RoomCodeGenerator` |
| **Unauthorized File Access** | Requesting file ID of another room | Data exfiltration | Enforce room membership check on download | `file/FileService` |
| **Malicious File Upload** | Uploading `.exe` or `shell.php` | Remote execution | Whitelist, blocked executables, UUID names | `file/FileValidator` |
| **Cross-Site Scripting (XSS)** | Script payload in chat or filename | Session hijack | DOM escaping (`textContent`), strict CSP | `js/chat.js`, `SecurityConfig`|
| **Signaling Identity Spoofing**| Sending WS offer with fake `from` | Impersonation | Server overwrites `from` with verified session ID | `signaling/SignalingWebSocketHandler` |
| **Message Flooding / DoS** | Continuous draw or chat spam | Client freeze | Socket rate limits (5 msg/s, 60 draw/s) | `signaling/handler/*` |
| **SQL Injection** | SQL fragments in parameters | Data breach | Parameterized queries via Spring Data JPA | `user/UserRepository` |
| **Direct DB Exposure** | Probing database port without authorization | Data leak | Network isolation, TLS sslmode=require, non-root user | `db/migration/V1..V7` |

## 3. Encryption Map
| Data Category | In Transit | At Rest | Mechanism & Details |
| :--- | :--- | :--- | :--- |
| **P2P Video / Audio / Screen** | DTLS-SRTP | N/A (Ephemeral) | Browser WebRTC encryption. Protects media in transit. |
| **REST & WebSocket Traffic** | HTTPS / WSS | N/A | TLS 1.3 termination at hosting platform. |
| **Passwords & OTPs** | TLS | BCrypt / SHA-256 | Salted hashes at rest. Plaintext secrets never stored. |
| **Database Connections** | TLS (`sslmode=require`) | Managed Disk | Enforced SSL channel between backend and PostgreSQL database. |
| **Chat & Files at Rest** | TLS | AES-GCM-256 (Opt) | Optional encryption via `crypto/EncryptionService`. |

*Note:* In-room chat and files are transported over TLS but decrypted at the server; client E2EE is reserved for future work.

## 4. Authentication & Two-Step Login (FR-1, FR-2, FR-3, FR-3A, FR-3B)
MeetApp enforces a mandatory two-step authentication flow before issuing a JWT:
1. **Step 1 (Credentials):** User submits email and password. Passwords verified with BCrypt (cost factor 10). Failed attempts return generic `401 Unauthorized: Invalid email or password` to eliminate account enumeration.
2. **Step 2 (Email OTP):** Upon successful credential check, an OTP challenge is created and dispatched via email:
   - **Generation:** Cryptographically secure 6-digit numeric string (`100000`–`999999`) using `java.security.SecureRandom`.
   - **Hash at Rest:** The OTP is hashed (SHA-256/BCrypt) before saving to `otp_challenges.otp_hash`. Plaintext OTP is never stored in DB.
   - **Expiration:** Valid for 5 minutes (`OTP_EXPIRY_MINUTES`). Expired challenges return `401 Unauthorized`.
   - **Failed Attempt Lockout:** A challenge allows at most 5 failed attempts; on the 5th bad attempt, the challenge is invalidated (`attempts >= 5` returns `429 Too Many Requests`).
   - **Resend Cooldown:** Minimum 60-second cooldown between resends (`POST /api/auth/resend-otp`), maximum 3 resends per session.
   - **Single-Use Consumption:** Successful verification marks `consumed = TRUE`; reuse is strictly rejected.
   - **Constant-Time Compare:** Hash validation uses `MessageDigest.isEqual()` to prevent timing side-channel attacks.
   - **Logging & Dev Mode:** OTPs are never logged in production. In the `dev` profile only, the generated OTP is logged via SLF4J to enable local testing without external email delivery.
3. **JWT Issuance:** Only after successful OTP verification is a signed JWT returned. Signed with HMAC-SHA256 (`HS256`, 256-bit secret), valid for 24 hours.

### 4.1 JWT Invalidation After Password Change & Account Deletion
Because MeetApp uses stateless HMAC-SHA256 JWTs, tokens issued prior to a credential change remain cryptographically valid until their 24-hour natural expiration unless explicitly verified against a revocation anchor.

**Architectural Options for Token Invalidation:**
- **Option 1 (Accept Stateless 24h Expiry):** Accept standard stateless token expiration. Tokens expire naturally within 24 hours. Zero database queries during `JwtAuthFilter` execution, preserving high-performance stateless verification.
- **Option 2 (Database Timestamp Revocation — Recommended):**
  - Add a `password_changed_at TIMESTAMPTZ` column to the `users` table via Flyway migration `V10__add_password_changed_at.sql`.
  - When a password is changed (`POST /api/users/me/change-password`), update `password_changed_at = now()`.
  - In `JwtAuthFilter`, verify that the token's `iat` (issued-at) claim is strictly after `password_changed_at`. Tokens issued prior to the password change are instantly rejected with `401 Unauthorized`.
  - *Recommendation:* Option 2 provides enterprise-grade session termination across all devices when credentials change. Requires user approval prior to generating migration `V10`.

## 5. Authorization Matrix
| Resource / Action | Anonymous | Authenticated User | Room Participant | Room Host |
| :--- | :---: | :---: | :---: | :---: |
| Register / Login / Verify OTP | Yes | Yes | Yes | Yes |
| View / Edit Profile (`/api/users/me`)| No | Yes | Yes | Yes |
| Change Password / Delete Account | No | Yes | Yes | Yes |
| Create Meeting Room | No | Yes | Yes | Yes |
| Join Room WebSocket | No | Yes (if $<5$) | Yes | Yes |
| Send / Receive Chat | No | No | Yes | Yes |
| Upload / Download Files | No | No | Yes | Yes |
| Relay WebRTC Signaling | No | No | Yes | Yes |
| End Meeting Room | No | No | No | Yes |

*Rule:* Every authorization check is executed server-side. Frontend button visibility is for UX only.

## 6. REST Security & HTTP Headers
- **Stateless:** `SessionCreationPolicy.STATELESS`. Spring Security session cookies are disabled.
- **CSRF:** Disabled because authentication is stateless and uses `Authorization: Bearer <JWT>` header rather than browser cookies.
- **CORS:** Restricted to `APP_ALLOWED_ORIGIN`. Wildcards (`*`) are disallowed.
- **Security Headers:**
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Content-Security-Policy: default-src 'self'; connect-src 'self' wss:; img-src 'self' data: blob:; media-src 'self' blob:;`

## 7. WebSocket Signaling Security (FR-24, FR-25)

### 7.1 Handshake Authentication & Query String Leakage Protection
Standard browser `WebSocket` APIs cannot supply custom HTTP authorization headers (e.g. `Authorization: Bearer <JWT>`) during the initial HTTP GET upgrade request. This necessitates transmitting credentials via the query string.

**Architectural Proposals Evaluated:**
- **Option (a) — Direct Query-String Token with Logging Exclusions:**
  Client passes `/ws?token=<JWT>&room=<code>`. Acceptable if JWTs are short-lived, transport uses TLS 1.3 (WSS), and reverse proxies (Nginx/Caddy/Cloudflare), load balancers, and application server access logs are strictly configured to strip or omit query strings.
- **Option (b) — Ephemeral One-Time Ticket Endpoint (Recommended):**
  Client sends an authenticated REST call `POST /api/ws-ticket` using standard `Authorization: Bearer <JWT>`. The server verifies the token and generates a cryptographically secure, random single-use ticket (e.g. 128-bit UUID or SecureRandom hex string) valid for 30 seconds, associated with `userId` and cached in an in-memory thread-safe map. The client connects with `/ws?ticket=<TICKET>&room=<code>`. Upon handshake, the ticket is consumed and invalidated immediately (single-use). Even if intermediate access logs capture the query parameter, the ticket is inert and cannot be replayed.
- *Recommendation:* Option (b) provides the highest security posture, fully preventing token persistence in server access logs, proxy caches, or browser history.

### 7.2 Origin Header Verification
- `WebSocketConfig` and `JwtHandshakeInterceptor` strictly validate the incoming `Origin` HTTP header against `APP_ALLOWED_ORIGIN`.
- Cross-origin requests originating from unrecognized domains or unauthorized origins are rejected immediately during handshake with HTTP 403 Forbidden. Wildcards (`*`) are strictly prohibited in production.

### 7.3 Room Membership & Active Participant Verification
- The user must already be an **ACTIVE participant** of the specified room prior to opening the WebSocket connection.
- During handshake, the interceptor checks the database via `RoomParticipantRepository`:
  - `Room` must exist and have `is_active = TRUE`.
  - `RoomParticipant` record must exist for `(roomId, userId)` where `left_at IS NULL`.
- If the user has not formally joined the room via `POST /api/rooms/{code}/join`, or if they previously left, the handshake is rejected with HTTP 403 Forbidden.

### 7.4 Identity Spoofing Prevention
- The server extracts the verified `userId` and `name` from the authenticated session attributes established during the handshake.
- For all inbound envelopes, the server **overwrites `from` with the session `userId`**; any client-provided `from` field is discarded without exception.
- Targeted messages (`to`) are routed strictly through authenticated session mappings in `RoomSessionRegistry`.

### 7.5 Single Active Session per User & Tab Replacement
- A user is permitted strictly one active WebSocket connection per room at any given time.
- If a user opens the room in a second browser tab or window, `RoomSessionRegistry.add()` detects the existing session for that `userId`, registers the new session, and cleanly terminates the older session with close code `4000` ("Replaced by newer session").
- This eliminates duplicate audio/signaling loops and race conditions.

### 7.6 Inbound Message Validation & Rate Limiting
- **Size Limit:** Max 16 KB per text frame (enforced at both the container buffer level and handler). Oversized frames are dropped and terminate the socket with code `1009` / `4013`.
- **Schema Validation:** Inbound JSON messages are strictly parsed against Java DTO models (`SignalMessage`). Unrecognized event types or malformed payloads receive an `error` envelope.
- **Abuse Rate Limiting:** A token bucket limiter caps incoming throughput at 20 messages/second per session. Exceeding the threshold returns a throttled error and disconnects repeat offenders (`4029`).

### 7.7 Structured Logging & Audit Hygiene
- **Never Log Secrets:** Application logs (SLF4J) must **never** record JWT tokens, one-time tickets, or full URLs containing query strings.
- **Safe Audit Trails:** Handshake and lifecycle logs record only sanitized metadata: `sessionId`, `userId`, `roomCode`, and event type (e.g. `log.info("WebSocket joined: session={}, user={}, room={}", sessionId, userId, roomCode)`).

## 8. WebRTC & Media Relay Security (FR-8..11, FR-24)

### 8.1 Signaling Relay Security Rules
The Spring Boot backend functions strictly as a signaling relay for WebRTC negotiation envelopes (`offer`, `answer`, `ice-candidate`, `mute-status`):
- **Bidirectional Membership Verification:** Prior to relaying an `offer`, `answer`, or `ice-candidate`, the server verifies:
  1. The sender session is an active participant in `roomCode`.
  2. The target session (`to`) exists in `RoomSessionRegistry` and is registered to the exact same `roomCode`.
  3. Relaying to peers in other rooms or unverified sessions is strictly rejected with `TARGET_NOT_FOUND` / `TARGET_NOT_IN_ROOM`.
- **Server-Controlled Identity:** The server overwrites the `from` field with the authenticated session `userId`. Client-supplied sender identifiers are discarded to prevent peer impersonation.
- **Self-Targeting Prevention:** Envelopes where `from == to` (self-addressed) are detected and dropped immediately.
- **Payload Size Caps:**
  - SDP Offers / Answers: capped at 16 KB (16,384 bytes).
  - ICE Candidates: capped at 2 KB (2,048 bytes).
  - Oversized payloads trigger an `error` envelope and close repeat offender sockets (`1009`).
- **Session Relay Rate Limits:** In addition to overall WebSocket limits, media relay envelopes are throttled to a maximum of 30 messages/sec per session to prevent CPU starvation and DoS attacks via candidate flooding.
- **Zero Raw Media Access:** The signaling server never handles, proxies, inspects, or stores RTP/SRTP audio/video tracks. Media flows 100% peer-to-peer.

### 8.2 DTLS-SRTP & Media Stream Encryption
- All browser-to-browser media (audio, video) is encrypted end-to-end via **DTLS-SRTP** (Datagram Transport Layer Security / Secure Real-time Transport Protocol). Keys are negotiated directly between peer browsers during the WebRTC handshake and are never known to the signaling server.

### 8.3 ICE Configuration & TURN Credential Custody
- STUN and TURN server credentials are served **only** through the authenticated REST endpoint `GET /api/webrtc/ice-config` protected by JWT.
- Responses strictly enforce `Cache-Control: no-store, no-cache, must-revalidate` to ensure credentials are never cached by browsers, intermediate proxies, or service workers.
- **Static Credential Exposure Note:** Because static TURN credentials (`TURN_USERNAME`, `TURN_CREDENTIAL`) are delivered to the browser for `RTCPeerConnection` instantiation, they are inherently visible to any authenticated user inspecting network calls or client state. To mitigate abuse, the endpoint is rate limited (10 requests per minute per user), requires valid authentication, and production environments should utilize TURN providers with ephemeral time-limited HMAC tokens where supported.
- TURN passwords are never logged in console or SLF4J logs; `AppProperties.toString()` redacts `turnCredential` as `[PROTECTED]`.
- Rate limited to 10 requests per minute per user to prevent credential scraping.

### 8.4 IP Address Disclosure in P2P Mesh
- **Transparent Privacy Notice:** In browser-native WebRTC mesh connections, peers exchange ICE candidates (`host`, `srflx`) directly through signaling to establish direct UDP socket pairs. Consequently, connected participants in the same meeting room can discover each other's IP addresses via browser developer tools or `chrome://webrtc-internals`.
- When NAT traversal fails and connections fall back to TURN, media relays through the TURN server, masking direct peer IP addresses behind the TURN relay IP.
- The application makes no false claims of IP anonymity for direct P2P mesh calls.

### 8.5 Screen Sharing Security & Privacy Rules (FR-12, FR-13, FR-14)
- **Active Membership Verification:** Only active, authenticated participants registered to the specified `roomCode` in `RoomSessionRegistry` may initiate or stop screen sharing.
- **Server-Controlled Identity:** The server overwrites the `from` field with the authenticated session `userId`, discarding any client-provided identifiers to prevent presenter spoofing.
- **Zero Server Media Processing:** Screen capture video frames and tab audio are transmitted 100% peer-to-peer using WebRTC DTLS-SRTP encryption. The server relays only JSON signaling notifications and opaque SDP renegotiation envelopes (`offer`, `answer`, `ice-candidate`). No screen pixels ever touch the server.
- **User Privacy Guidance (Surface Selection):** Desktop browsers present a native picker through `navigator.mediaDevices.getDisplayMedia()` offering *Entire Screen*, *Window*, or *Browser Tab*. Users are advised to share specific tabs or application windows to avoid accidental exposure of background notifications, desktop icons, or confidential personal data.
- **Atomic Single-Presenter Enforcement:** The backend enforces an atomic concurrency lock on the room's presenter slot. If two participants click share simultaneously, only one succeeds; the second receives an explicit `ALREADY_PRESENTING` error without crashing or destabilizing active WebRTC connections.
- **Host Override Authorization:** Only the active presenter and the authenticated meeting host can issue a `screen-share-stop`. Unauthorized stop attempts by guest participants are rejected with error code `FORBIDDEN`.
- **Client Inbound Stream Validation:** Clients strictly verify screen streams: a received media stream is treated as a screen presentation **only if** it arrives on the presenter's authenticated `RTCPeerConnection` **and** its stream identifier matches the `streamId` broadcast in `screen-share-start`. Claims from non-presenters or mismatched stream IDs are discarded.
- **Signaling Flood & Renegotiation Rate Limiting:** Screen share control envelopes (`screen-share-start`, `screen-share-stop`) are rate-limited to at most 10 events per minute per session to prevent presentation toggling abuse and browser renegotiation storms. User cancellations in the native browser picker never hit the server.

### 8.6 Group Chat Security & Privacy Rules (FR-22, FR-23)
- **Active Membership Enforcement:**
  - WebSocket: Only authenticated users with an active participant record in the room can emit or receive `chat` envelopes. Handlers verify session registration in `RoomSessionRegistry`.
  - REST API: `GET /api/rooms/{code}/messages` verifies that the caller is an active or recorded past participant of that specific room in `room_participants`. Non-participants are rejected with HTTP 403 Forbidden.
  - **Design Note on Past Participants:** As a deliberate product choice for MeetApp, any user who has participated in a room retains the ability to inspect the conversation history for that meeting, including messages sent after they left (similar to persistent collaboration channels). Users who never participated in the room are completely blocked.
- **Server-Controlled Identity & Timestamps:**
  - The server explicitly sets `id`, `from`, `name`, and `sentAt`. Any client-provided values for these fields are discarded, preventing sender impersonation and timestamp tampering.
- **Strict Payload Sanitization & Length Limits:**
  - Message text is trimmed and strictly validated: 1 to 1000 characters, at most 20 lines.
  - Non-newline control characters (`[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]`) are rejected immediately with `INVALID_PAYLOAD`.
- **Cross-Site Scripting (XSS) Prevention:**
  - Frontend chat rendering strictly uses `document.createElement()` and `node.textContent = text`. Under no circumstance is `innerHTML` or `insertAdjacentHTML` used with message or user data.
  - Automatic HTML evaluation is prohibited. If URLs are rendered as links, they are constructed strictly as manual DOM `<a>` elements for verified `http:` and `https:` schemes only, decorated with `target="_blank"` and `rel="noopener noreferrer"`.
- **SQL Injection Defense:**
  - All database interactions use Spring Data JPA parameterized queries and prepared statements. Dynamic SQL string concatenation is strictly prohibited.
- **Audit Logging Hygiene (Zero Text Logging):**
  - Message content is **never** logged in application logs (SLF4J), console output, or error stack traces.
  - Log entries record only sanitized operational metadata: `log.info("Chat message saved: id={}, user={}, room={}", id, userId, roomCode)`.
- **Data Encryption at Rest & Additional Authenticated Data (AAD):**
  - **In-Transit:** Chat messages are encrypted in transit via TLS 1.3 / WSS.
  - **At Rest (AES-256-GCM):** Message bodies are encrypted prior to insertion into the `messages` table using AES-256-GCM (12-byte random IV per message, 256-bit key from `AES_KEY`).
  - **Cross-Room Tampering Prevention via GCM AAD:** The database `room_id` is supplied as GCM Additional Authenticated Data (AAD) during encryption and decryption. If an attacker or compromised database administrator copies ciphertext from one room to another, GCM tag verification fails immediately and the tampered message cannot be decrypted.
  - **Honest Cryptographic Notice (Not End-to-End Encrypted):** Chat is **NOT** end-to-end encrypted (E2EE). The Spring Boot server holds the encryption key, inspects text for validation, persists records, and relays messages to room participants. Encryption at rest protects backups and database file dumps, but the backend server operates as a trusted party with plaintext access.

## 9. File Upload & Storage Security (FR-15, FR-16, FR-17)

MeetApp enforces defense-in-depth controls across every stage of the file lifecycle (upload, validation, storage, streaming download, and deletion):

### 9.1 Size Limit & Memory Exhaustion Prevention
- **Early Size Enforcement:** Maximum file size is strictly capped at `10 MB` (`app.max-file-size-mb: 10`, `10,485,760 bytes`).
- Size limits are enforced **BEFORE** buffering entire payloads into JVM heap:
  1. **Servlet Container Limits:** Spring Boot multipart configuration rejects oversized HTTP requests at the network gateway layer (`spring.servlet.multipart.max-file-size: 10MB`, `spring.servlet.multipart.max-request-size: 12MB`). Exceeding this limit immediately raises `MaxUploadSizeExceededException`, returning `413 Payload Too Large`.
  2. **Streaming Byte Counter:** In the application service layer, incoming streams are wrapped in a bounded counting stream. If actual stream bytes exceed `10,485,760 bytes` at any point, the stream is aborted and deleted immediately without completing storage.

### 9.2 Extension Whitelisting & Blocklist
File validation never trusts the user-provided filename extension blindly:
- **Strict Allow-List:**
  - **Images:** `.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`
  - **Documents:** `.pdf`, `.txt`, `.csv`, `.docx`, `.xlsx`, `.pptx`
- **Comprehensive Blocklist:**
  Any file ending in or containing executable, scripting, markup, archive, or dangerous binary extensions is rejected with `415 Unsupported Media Type`:
  `.exe`, `.bat`, `.cmd`, `.sh`, `.ps1`, `.js`, `.mjs`, `.html`, `.htm`, `.svg` (rejects SVG due to embedded `<script>` / XML entity hazards), `.jar`, `.msi`, `.dll`, `.apk`, `.com`, `.scr`, `.vbs`, `.php`, `.jsp`, `.asp`, `.zip`, `.tar`, `.gz`, `.7z`.
- **Double Extension Attack Prevention:** Attackers often name files `photo.png.exe` or `report.pdf.js` hoping naive systems match only the first extension. MeetApp parses all dot-separated extensions across the filename: if **any** segment matches the blocklist, the upload is rejected.

### 9.3 Magic-Byte Signature Verification
Clients can forge the HTTP `Content-Type` header effortlessly (e.g. labeling `malware.exe` as `image/png`). MeetApp **never trusts** the client-supplied `Content-Type`:
- The server reads the initial 16 to 32 bytes of the file stream to verify magic number signatures:
  - **PNG:** `89 50 4E 47 0D 0A 1A 0A`
  - **JPEG:** `FF D8 FF`
  - **GIF:** `47 49 46 38` (`GIF87a` or `GIF89a`)
  - **WebP:** `52 49 46 46` ... `57 45 42 50` (`RIFF` ... `WEBP`)
  - **PDF:** `25 50 44 46` (`%PDF-`)
  - **DOCX / XLSX / PPTX:** Verified against standard ZIP container magic signature `50 4B 03 04` (`PK\x03\x04`), as modern Office Open XML files are zipped XML archives. Bare `.zip` filenames are blocked.
  - **Plain Text / CSV:** Stream is verified to contain zero binary null bytes (`\0`) and adhere to valid UTF-8/ASCII character encoding.
- Files whose magic bytes disagree with their extension are rejected with `415 Unsupported Media Type`.

### 9.4 Randomized Stored Filenames & Storage Isolation
- **Random Stored Name:** The user-provided filename is **never** used on the filesystem. Every file is stored using a random UUID: `UUID.randomUUID().toString() + "." + sanitizedExtension`.
- **Storage Location:** Stored files reside in `/uploads` (or configured `UPLOAD_DIR`), located strictly outside any public web root or static resource handler. Files cannot be accessed directly via static HTTP URL paths.
- **Path Traversal Defense:** The storage layer resolves target paths against a normalized root directory and strictly asserts:
  ```java
  Path resolved = rootDir.resolve(storedName).normalize();
  if (!resolved.startsWith(rootDir.normalize())) {
      throw new SecurityException("Path traversal attempt detected");
  }
  ```
- **Atomic Writes:** Files are written to a temporary file (`.tmp`) first and renamed/moved atomically to their final stored path only after full write and validation succeed.

### 9.5 Sanitized Display Name
While the filesystem uses random UUIDs, the database stores the user's display filename for room participants:
- Strips any path separators (`/`, `\`, `../`, `..\`).
- Strips non-printable ASCII and control characters (`\u0000` to `\u001F`, `\u007F` to `\u009F`).
- Normalizes Unicode to Canonical Composition (NFC).
- Caps original filename length to 100 characters while preserving the extension.
- On the frontend, filenames are rendered strictly via `textContent` (zero `innerHTML`), preventing XSS payloads in filenames (e.g., `<img src=x onerror=alert(1)>.pdf`).

### 9.6 Download Security Headers & Safe Streaming
When streaming downloads via `GET /api/files/download/{id}`:
- **`Content-Disposition: attachment; filename="<sanitized>"; filename*=UTF-8''<encoded>`**: Forces browsers to treat the file as a download, preventing the browser from rendering the file inline inside the application context.
- **`X-Content-Type-Options: nosniff`**: Prevents MIME-sniffing by browsers (e.g., treating an uploaded text file as HTML).
- **`Cache-Control: private, no-cache, no-store, must-revalidate`**: Prevents caching of meeting files on intermediate corporate proxies or browser caches.
- **Safe Fallback `Content-Type`**: Serves `application/octet-stream` for complex/ambiguous types, and safe whitelisted MIME types for standard documents/images.

### 9.7 Authorization on Every Download & Deletion
- **Download Authorization:** Every download request requires a valid JWT and performs a database lookup verifying that the authenticated user is an active or recorded participant in the meeting room that owns the file (`room_participants` check).
- **Outsider Probe Protection:** If a non-participant requests a file ID from another room, the server returns a generic `404 Not Found` (or `403 Forbidden`) without disclosing whether the file exists.
- **Deletion Authorization:** Only the original uploader (`uploader_id == user.id`) OR the meeting room host (`room.host_id == user.id`) can invoke `DELETE /api/files/{id}`. All other users receive `403 Forbidden`.

### 9.8 Room Quotas & Rate Limits
- **Per-Room Storage Quota:**
  - Maximum **20 files** per room.
  - Maximum **50 MB** cumulative storage per room.
  - Uploads attempting to exceed either threshold are rejected with `413 Payload Too Large`.
- **Per-User Rate Limits:**
  - File Uploads: 5 uploads per minute per user (`429 Too Many Requests`).
  - File Downloads: 30 downloads per minute per user (`429 Too Many Requests`).

### 9.9 Logging Hygiene & Privacy
- Application loggers (SLF4J) **never** log file binary contents, chunk buffers, or raw bytes.
- Log entries record only sanitized operational metadata: `fileId`, `roomCode`, `sizeBytes`, `sanitizedExtension`, and `uploaderId`.

### 9.10 Antivirus / Malware Scanning Disclosure
- **Honest Security Boundary Notice:** MeetApp implements strict format validation, extension whitelisting, magic-byte checks, and download isolation, but does **NOT** run an active background antivirus daemon (such as ClamAV or Windows Defender). Malicious payloads embedded within permitted formats (e.g., macro-enabled documents or malicious PDFs) are mitigated through `Content-Disposition: attachment` and `nosniff`, but users should observe standard caution when downloading files shared by untrusted peers.

## 10. Collaborative Whiteboard Security Architecture (FR-18..21)

The real-time whiteboard allows participants to collaborate on a shared 16:9 canvas using vector strokes. Because vector drawing messages can be emitted at high frequency and are stored in server memory to support late joiners, strict memory bounding, mathematical validation, and permission checks are enforced.

### 10.1 Coordinate & Numeric Validation
- **Normalized Range:** All coordinates must be normalized floating-point numbers in the closed interval `[0.0, 1.0]`.
- **Rejection of Non-Finite Numbers:** The server strictly checks every coordinate value using `Double.isFinite()` and rejects payloads containing `NaN`, `+Infinity`, `-Infinity`, negative numbers, or values exceeding `1.0` with `INVALID_PAYLOAD`.
- **Stroke Width Range:** Stroke width must be a valid number between `1.0` and `32.0` board units.
- **Color Validation:** Colors must strictly match the 6-character hex format `^#[0-9a-fA-F]{6}$`. Any attempt to inject CSS function calls (e.g. `url()`, `expression()`, `rgba()`) or scripts is rejected with `INVALID_PAYLOAD`.
- **Tool Whitelist:** The `tool` property is validated against an explicit enum whitelist: `pen`, `eraser`, `line`, `rect`, `ellipse`. Unrecognized tools trigger `INVALID_PAYLOAD`.
- **Point Count Caps per Batch:** Freehand batches (`pen`, `eraser`) are restricted to a maximum of 50 points per WebSocket message. Geometric shapes (`line`, `rect`, `ellipse`) are strictly enforced to have exactly 2 points (`[start, end]`) and `final: true`.

### 10.2 Stroke Ownership & Anti-Hijacking Rules
- **Stroke ID Binding:** When a client initiates a new stroke (`strokeId`), the server records the authenticated `userId` as the permanent owner of that stroke.
- **Segment Append Protection:** If subsequent draw segments arrive referencing an existing `strokeId`, the server verifies that the sender's `userId` matches the stroke owner. Any attempt by another participant to append points to a peer's stroke is rejected with `STROKE_CONFLICT`.
- **Undo Ownership Enforcement:** Only the creator of a stroke may undo it (`stroke-undo`). If a participant attempts to undo a stroke created by another user, the server rejects the request with `STROKE_NOT_OWNED`.
- **Host Privileges vs. Selective Censorship:** The meeting room host is permitted to clear the entire board (`clear-board`), but **cannot** selectively undo or delete individual strokes belonging to other participants. This design choice prevents selective tampering or falsification of collaborative work.

### 10.3 In-Memory Quotas & DoS Starvation Defense
Because board state is buffered in JVM heap memory for late-joiner replay, hard caps prevent memory exhaustion attacks:
- **Max Strokes per Board:** Maximum **1,000** active strokes per room.
- **Max Points per Stroke:** Maximum **500** points per stroke.
- **Max Total Points per Board:** Maximum **25,000** cumulative points per room board.
- **Capacity Rejection (`BOARD_FULL`):** When either the stroke limit or point limit is reached, further draw messages are rejected with error code `BOARD_FULL` (`"Whiteboard capacity reached for this room"`). The existing board state is preserved and remains intact.
- **Worst-Case Memory Footprint:**
  - Each point is stored as two 32-bit floats (8 bytes raw).
  - In Java object representation with ArrayList and record overhead, 1 point $\approx$ 32 bytes.
  - 25,000 points $\approx$ 800 KB of point memory.
  - 1,000 stroke metadata objects $\approx$ 200 KB.
  - Total maximum memory consumed per active room is strictly bounded at **$\le$ 1.0 MB**.
  - For a server hosting 100 concurrent active rooms, cumulative whiteboard memory usage is strictly capped at **$\le$ 100 MB**, preventing Out-Of-Memory (OOM) crashes.

### 10.4 Rate Limiting & Socket Abuse Protection
- **Token Bucket Limiter:** Draw messages are throttled per WebSocket session using an in-memory token bucket configured for **20 messages/sec** with a burst capacity of **40 messages**.
- **Violation Policy:** Exceeding the burst threshold triggers an error envelope with code `RATE_LIMITED`. Continuous or programmatic flooding triggers session termination with WebSocket close code `1008 Policy Violation`.

### 10.5 Identity Spoofing Mitigation
- Clients may never specify or spoof the `from` field. The server unconditionally overwrites the envelope's `from` attribute with the verified `userId` attached to the authenticated `WebSocketSession`.

### 10.6 Ephemeral Lifecycle & Server Restart Disclosure
- **Eviction Triggers:** The in-memory whiteboard buffer for a room is immediately evicted and garbage collected:
  1. When the meeting host terminates the room (`RoomEndedEvent`).
  2. When all participants leave and the 10-second disconnect grace period expires without reconnection.
  3. When an idle room cleanup job sweeps abandoned rooms.
- **Honest Ephemeral Disclosure:** Whiteboard drawing data is stored **only in JVM memory** and is never persisted to PostgreSQL or disk. If the application server process restarts or crashes, all active whiteboard drawings are permanently lost.

## 11. Secrets & Database RLS
- All secrets loaded from environment variables (`DB_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD`, `AES_KEY`).
- Row-Level Security (RLS) enabled across all tables as an in-depth access control layer:
```sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY; ALTER TABLE otp_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE rooms ENABLE ROW LEVEL SECURITY; ALTER TABLE room_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE files ENABLE ROW LEVEL SECURITY; ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
```

## 11. Security Test Plan
| Test Case | Execution Steps | Expected Outcome |
| :--- | :--- | :--- |
| **Wrong Password** | POST `/api/auth/login` with bad password | `401 Unauthorized` (generic error) |
| **Wrong OTP** | POST `/api/auth/verify-otp` with incorrect code | `401 Unauthorized` (attempts incremented) |
| **OTP Lockout** | 5 consecutive wrong OTP submissions | `429 Too Many Requests` (challenge invalidated) |
| **Expired OTP** | POST `/api/auth/verify-otp` after 5 minutes | `401 Unauthorized` |
| **Resend Cooldown** | POST `/api/auth/resend-otp` before 60 seconds | `429 Too Many Requests` |
| **Expired Token** | Request endpoint with expired JWT | `401 Unauthorized` |
| **Tampered Token** | Alter payload in JWT and send request | `401 Unauthorized` |
| **Unauthorized File Download** | Non-participant calls `/api/files/download/{id}` | `403 Forbidden` |
| **Executable Upload** | POST file `malware.exe` to upload endpoint | `415 Unsupported Media Type` |
| **Oversized File** | Upload 12 MB file | `413 Payload Too Large` |
| **XSS in Chat** | Send `<script>alert(1)</script>` via chat | Harmless text literal |
| **Spoofed From** | Emit WS message with forged `from` | Overwritten with real sender ID |

## 12. Pre-Release Security Checklist
- [ ] No plaintext secrets or passwords in code, `.env`, or Git history.
- [ ] BCrypt work factor $\ge 10$ and SecureRandom 6-digit OTP active.
- [ ] OTPs hashed before DB storage; constant-time comparison enforced.
- [ ] No OTP logging in production profile.
- [ ] JWT secret is random and $\ge 256$ bits.
- [ ] WebSocket handshake validates JWT and room membership.
- [ ] File uploads reject executable files and enforce 10 MB limit.
- [ ] Row-Level Security (RLS) active on all PostgreSQL tables.
- [ ] Security headers and rate limiting active in HTTP responses.

## 13. Open Questions
- *Ticket Endpoint:* Should `/api/auth/ws-ticket` be prioritized to eliminate tokens from query strings in v2?
- *File Scan:* Should an external ClamAV scanning daemon be integrated for uploaded files?
