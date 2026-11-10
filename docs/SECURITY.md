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
| **InsForge API Exposure** | Scraping DB via InsForge auto-API | Hash leak | Row-Level Security (RLS) on all tables | `db/migration/V1..V7` |

## 3. Encryption Map
| Data Category | In Transit | At Rest | Mechanism & Details |
| :--- | :--- | :--- | :--- |
| **P2P Video / Audio / Screen** | DTLS-SRTP | N/A (Ephemeral) | Browser WebRTC encryption. Protects media in transit. |
| **REST & WebSocket Traffic** | HTTPS / WSS | N/A | TLS 1.3 termination at hosting platform. |
| **Passwords & OTPs** | TLS | BCrypt / SHA-256 | Salted hashes at rest. Plaintext secrets never stored. |
| **Database Connections** | TLS (`sslmode=require`) | Managed Disk | Enforced SSL channel between backend and InsForge. |
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
   - **Logging & Dev Mode:** OTPs are never logged in production. In the `dev` profile only, the generated OTP is logged via SLF4J to enable local testing without SMTP.
3. **JWT Issuance:** Only after successful OTP verification is a signed JWT returned. Signed with HMAC-SHA256 (`HS256`, 256-bit secret), valid for 24 hours.

## 5. Authorization Matrix
| Resource / Action | Anonymous | Authenticated User | Room Participant | Room Host |
| :--- | :---: | :---: | :---: | :---: |
| Register / Login / Verify OTP | Yes | Yes | Yes | Yes |
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
- **Handshake Authentication:** `JwtHandshakeInterceptor` parses `token` from `/ws?token=<JWT>&room=<code>`.
- **Spoofing Prevention:** Server overwrites `from` with verified session user ID, dropping client-supplied `from`.
- **Session Cleanup:** Closed sockets are immediately purged from `RoomSessionRegistry`.

## 8. WebRTC & Media Privacy
- **DTLS-SRTP:** Mandatory encryption for all P2P media streams.
- **Dynamic ICE:** STUN/TURN credentials served via authenticated endpoint `GET /api/webrtc/ice-config`—never hardcoded.
- **IP Exposure:** In P2P mesh, client IP addresses are visible to room peers to establish UDP connections.
- **Consent:** Browser prompts user consent for camera, microphone, and display capture.

## 9. File Upload Security (FR-15, FR-16)
- **Constraints:** Maximum 10 MB (`size_bytes <= 10485760`).
- **Whitelist:** `.pdf`, `.doc`, `.docx`, `.txt`, `.png`, `.jpg`, `.jpeg`, `.zip`. Block executables (`.exe`, `.bat`, `.sh`, `.js`).
- **Isolation:** Stored in `/uploads` outside web root using random UUIDs (`uuid.ext`). Served with `Content-Disposition: attachment`.

## 10. Secrets & Database RLS
- All secrets loaded from environment variables (`DB_PASSWORD`, `JWT_SECRET`, `MAIL_PASSWORD`, `AES_KEY`).
- Row-Level Security (RLS) enabled across all tables to block InsForge auto-API leakage:
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
