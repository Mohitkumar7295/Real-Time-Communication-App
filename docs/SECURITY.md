# MeetApp — Security Architecture & Guidelines (SECURITY.md)

## 1. Security Goals & Scope
MeetApp safeguards five core domains:
- **Accounts:** Protecting credentials and authentication sessions from unauthorized access.
- **Rooms:** Restricting meeting access to intended participants and enforcing a 5-user capacity limit (FR-7).
- **Files:** Preventing unauthorized downloads, path traversal, and executable uploads (FR-16).
- **In-Room Chat & Canvas:** Preventing Cross-Site Scripting (XSS), message spoofing, and flood DoS.
- **Media Streams:** Guaranteeing cryptographic privacy and integrity for P2P media via DTLS-SRTP.

Adversary model covers: anonymous external attackers, logged-in users probing other rooms, and uninvited intruders.

---

## 2. Threat Model
| Threat | Example Attack | Impact | Mitigation | Implementation |
| :--- | :--- | :--- | :--- | :--- |
| **Credential Stuffing** | Dictionary attack on `/api/auth/login` | Account takeover | Rate limit (5 req/min), generic errors | `security/RateLimitFilter` |
| **Forged / Tampered JWT** | Modifying user claims in token | Privilege escalation | HMAC-SHA256 signature check (256-bit key) | `security/JwtService` |
| **Room Code Guessing** | Iterating codes | Room snooping | Unguessable random space ($36^6$ combos) | `room/RoomCodeGenerator` |
| **Unauthorized File Access** | Requesting file ID of another room | Data exfiltration | Enforce room membership check on download | `file/FileService` |
| **Malicious File Upload** | Uploading `.exe` or `shell.php` | Remote execution | Whitelist, blocked executables, UUID names | `file/FileValidator` |
| **Cross-Site Scripting (XSS)** | Script payload in chat or filename | Session hijack | DOM escaping (`textContent`), strict CSP | `js/chat.js`, `SecurityConfig`|
| **Signaling Identity Spoofing**| Sending WS offer with fake `from` | Impersonation | Server overwrites `from` with verified session ID | `signaling/SignalingWebSocketHandler` |
| **Message Flooding / DoS** | Continuous draw or chat spam | Client freeze | Socket rate limits (5 msg/s, 60 draw/s) | `signaling/handler/*` |
| **SQL Injection** | SQL fragments in parameters | Data breach | Parameterized queries via Spring Data JPA | `user/UserRepository` |
| **InsForge API Exposure** | Scraping DB via InsForge auto-API | Hash leak | Row-Level Security (RLS) on all tables | `db/migration/V1..V5` |

---

## 3. Encryption Map
| Data Category | In Transit | At Rest | Mechanism & Details |
| :--- | :--- | :--- | :--- |
| **P2P Video / Audio / Screen** | DTLS-SRTP | N/A (Ephemeral) | Browser WebRTC encryption. Protects media in transit. Does not encrypt signaling metadata. |
| **REST & WebSocket Traffic** | HTTPS / WSS | N/A | TLS 1.3 termination at hosting platform. |
| **User Passwords** | TLS | BCrypt (Cost 10) | Salted one-way hash. Plaintext passwords never stored. |
| **Database Connections** | TLS (`sslmode=require`) | Managed Disk | Enforced SSL channel between backend and InsForge. |
| **Chat & Files at Rest** | TLS | AES-GCM-256 (Opt) | Optional encryption via `crypto/EncryptionService` using `AES_KEY` & random 96-bit IV. |

*Note:* In-room chat and files are transported over TLS but decrypted at the server; client E2EE is reserved for future work.

---

## 4. Authentication Design (FR-1, FR-2, FR-3)
- **Password Policy:** Minimum 8 characters, $\ge 1$ uppercase, $\ge 1$ digit, $\ge 1$ special character.
- **Hashing:** BCrypt with cost factor 10.
- **Generic Responses:** Failed logins return identical generic `401 Unauthorized: Invalid credentials`.
- **JWT Architecture:** Signed with HMAC-SHA256 (`HS256`) using `JWT_SECRET` ($\ge 32$ random bytes). Claims: `sub` (userId), `email`, `iat`, `exp` (24h).
- **Hygiene:** Sensitive data is excluded from JWT payload. Logout deletes token from client `localStorage`. Refresh tokens omitted in v1.

---

## 5. Authorization Matrix
| Resource / Action | Anonymous | Authenticated User | Room Participant | Room Host |
| :--- | :---: | :---: | :---: | :---: |
| Register / Login | Yes | Yes | Yes | Yes |
| Create Meeting Room | No | Yes | Yes | Yes |
| Join Room WebSocket | No | Yes (if $<5$) | Yes | Yes |
| Send / Receive Chat | No | No | Yes | Yes |
| Upload / Download Files | No | No | Yes | Yes |
| Relay WebRTC Signaling | No | No | Yes | Yes |
| End Meeting Room | No | No | No | Yes |

*Rule:* Every authorization check is executed server-side. Frontend button visibility is for UX only.

---

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

---

## 7. WebSocket Signaling Security (FR-24, FR-25)
- **Handshake Authentication:** `JwtHandshakeInterceptor` parses `token` from `/ws?token=<JWT>&room=<code>`.
- **Query String Risk:** Tokens in query strings may appear in logs. *Mitigations:* Logs filter query parameters; tokens expire in 24h; single-use tickets planned for v2.
- **Spoofing Prevention:** Server overwrites `from` with verified session user ID, dropping client-supplied `from`.
- **Session Cleanup:** Closed sockets are immediately purged from `RoomSessionRegistry`.

---

## 8. WebRTC & Media Privacy
- **DTLS-SRTP:** Mandatory encryption for all P2P media streams.
- **Dynamic ICE:** STUN/TURN credentials served via authenticated endpoint `GET /api/webrtc/ice-config`—never hardcoded.
- **IP Exposure:** In P2P mesh, client IP addresses are visible to room peers to establish UDP connections.
- **Consent:** Browser prompts user consent for camera, microphone, and display capture.

---

## 9. File Upload Security (FR-15, FR-16)
- **Constraints:** Maximum 10 MB (`size_bytes <= 10485760`).
- **Whitelist:** `.pdf`, `.doc`, `.docx`, `.txt`, `.png`, `.jpg`, `.jpeg`, `.zip`.
- **Blacklist:** Executables (`.exe`, `.bat`, `.sh`, `.js`, `.jar`, `.svg`, `.html`) are unconditionally rejected.
- **Path Traversal Prevention:** Sanitized via `Paths.get(originalName).getFileName().toString()`.
- **Disk Isolation:** Stored in `/uploads` outside web root using random UUIDs (`uuid.ext`).
- **Download Safety:** Served with `Content-Disposition: attachment; filename="sanitized.ext"` and `X-Content-Type-Options: nosniff`.

---

## 10. Input Validation & Output Encoding
- **Backend:** Bean Validation (`@Valid`, `@NotNull`, `@Size`, `@Email`) validates all incoming DTOs.
- **Database:** JPA uses parameterized statements, preventing SQL injection.
- **Frontend Encoding:** User text is rendered using `element.textContent = data`, never `innerHTML`.

---

## 11. Secrets Management
- All secrets are injected via environment variables: `DB_PASSWORD`, `JWT_SECRET`, `TURN_CREDENTIAL`, `AES_KEY`.
- `.env` is listed in `.gitignore`. `.env.example` documents variable names only.
- If a secret is leaked, rotate the credential immediately and scrub Git history.

---

## 12. Database Security & InsForge RLS
- Non-superuser database account with SSL (`sslmode=require`).
- Row-Level Security (RLS) enabled across all tables to prevent leaks via InsForge auto-APIs:
```sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE files ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
```

---

## 13. Logging & Monitoring Hygiene
- **Allowed:** Logins, room lifecycles, file uploads, upload rejections, and security exceptions.
- **Forbidden:** Passwords, raw JWT tokens, query strings with tokens, TURN credentials, and chat content.
- **Log Injection:** Newlines stripped from user input before writing to SLF4J logs.

---

## 14. Dependency & Build Security
- Maven dependencies pinned to stable releases; scanned with `mvn dependency-check:check`.
- New dependencies require architectural approval.

---

## 15. Deployment Security
- Enforce HTTPS and WSS on hosting platforms.
- Actuator restricted to `/actuator/health` only; stack traces suppressed from error responses.

---

## 16. Security Test Plan
| Test Case | Execution Steps | Expected Outcome |
| :--- | :--- | :--- |
| **Wrong Password** | POST `/api/auth/login` with bad password | `401 Unauthorized` |
| **Expired Token** | Request endpoint with expired JWT | `401 Unauthorized` |
| **Tampered Token** | Alter payload in JWT and send request | `401 Unauthorized` |
| **Unauthorized File Download** | Non-participant calls `/api/files/download/{id}` | `403 Forbidden` |
| **Executable Upload** | POST file `malware.exe` to upload endpoint | `415 Unsupported Media Type` |
| **Oversized File** | Upload 12 MB file | `413 Payload Too Large` |
| **XSS in Chat** | Send `<script>alert(1)</script>` via chat | Harmless text literal |
| **Spoofed From** | Emit WS message with forged `from` | Overwritten with real sender ID |

---

## 17. Pre-Release Security Checklist
- [ ] No plaintext secrets in code, `.env`, or Git history.
- [ ] BCrypt work factor $\ge 10$ confirmed.
- [ ] JWT secret is random and $\ge 256$ bits.
- [ ] WebSocket handshake validates JWT and room membership.
- [ ] File uploads reject executable files and enforce 10 MB limit.
- [ ] Row-Level Security (RLS) active on all PostgreSQL tables.
- [ ] CORS restricted to authorized frontend origin.
- [ ] Security headers active in HTTP responses.

---

## 18. Known Limitations & Future Improvements
- **Chat E2EE:** Chat is TLS-encrypted but decrypted on server; client-side E2EE planned for future.
- **P2P IP Disclosure:** WebRTC mesh exposes IP addresses to room participants.
- **2FA:** Two-factor authentication is deferred to future releases.
- **Handshake Tickets:** Single-use tickets to replace URL query JWTs planned for v2.

---

## 19. Open Questions
- *Ticket Endpoint:* Should `/api/auth/ws-ticket` be prioritized to eliminate tokens from query strings?
- *File Scan:* Should an external ClamAV scanning daemon be integrated for uploaded files?
