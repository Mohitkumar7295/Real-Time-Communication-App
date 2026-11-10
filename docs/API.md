# MeetApp — API Specification (API.md)

## 1. Conventions
- **Base Paths:** REST: `/api`, WebSocket: `/ws`.
- **Payloads:** JSON (multipart for uploads). Naming: `camelCase`. IDs: `Long` (`BIGINT`).
- **Dates:** ISO-8601 UTC (`YYYY-MM-DDTHH:mm:ssZ`).
- **Auth Header:** `Authorization: Bearer <JWT>`.

## 2. Standard Error Format
```json
{"timestamp":"2026-11-09T05:30:00Z","status":400,"error":"Bad Request","message":"Invalid input","path":"/api/path"}
```
| Status | Name | Condition |
| :--- | :--- | :--- |
| `400` | Bad Request | Validation failure (malformed email, password <8 chars) |
| `401` | Unauthorized | Missing/invalid JWT or bad credentials / bad OTP |
| `403` | Forbidden | Not room host or member |
| `404` | Not Found | Room code, user, or file ID does not exist |
| `409` | Conflict | Duplicate email or room full (>5) |
| `413` | Payload Too Large | File upload exceeds 10 MB limit |
| `415` | Unsupported Media | Blocked file type (`.exe`, `.bat`, etc.) |
| `429` | Too Many Requests | Rate limit exceeded / challenge locked |
| `500` | Server Error | Unhandled server error |

## 3. REST Endpoints

### 3.1 `POST /api/auth/register` (FR-1, FR-2)
- **Auth:** Anonymous.
- **Request:** `{"name": "Aarav", "email": "a@ex.com", "password": "Password1!"}` (name $\ge 2$, valid email, password $\ge 8$ with upper/digit/special).
- **Success:** `201 Created` $\to$ `{"id": 1, "name": "Aarav", "email": "a@ex.com"}`.
- **Errors:** `400` (validation), `409` (duplicate email).

### 3.2 `POST /api/auth/login` (FR-3)
- **Auth:** Anonymous.
- **Request:** `{"email": "a@ex.com", "password": "Password1!"}`.
- **Success:** `200 OK` $\to$ `{"otpRequired": true, "challengeId": "ch_123abc", "maskedEmail": "a***@ex.com", "expiresInSeconds": 300}`.
- **Errors:** `401` (generic "Invalid email or password" to prevent user enumeration).
- **Notes:** Generates 6-digit OTP and emails user. In dev profile, OTP is logged to console.

### 3.2A `POST /api/auth/verify-otp` (FR-3A)
- **Auth:** Anonymous.
- **Request:** `{"challengeId": "ch_123abc", "otp": "123456"}` (6 numeric digits).
- **Success:** `200 OK` $\to$ `{"token": "fake.jwt.token", "user": {"id": 1, "name": "Aarav", "email": "a@ex.com"}}`.
- **Errors:** `400` (invalid format), `401` (invalid/expired OTP), `429` (locked after 5 failed attempts).

### 3.2B `POST /api/auth/resend-otp` (FR-3B)
- **Auth:** Anonymous.
- **Request:** `{"challengeId": "ch_123abc"}`.
- **Success:** `200 OK` $\to$ `{"message": "OTP resent successfully", "expiresInSeconds": 300}`.
- **Errors:** `400` (invalid challenge), `429` (cooldown active: 60s minimum wait, or max 3 resends).

### 3.3 `GET /api/users/me` (FR-1)
- **Auth:** Authenticated User (`Bearer <JWT>`).
- **Success:** `200 OK` $\to$ `{"id": 1, "name": "Aarav", "email": "a@ex.com", "createdAt": "2026-11-09T05:30:00Z"}`.
- **Errors:** `401 Unauthorized` (missing/invalid token).

### 3.3A `PATCH /api/users/me` (FR-1A)
- **Auth:** Authenticated User (`Bearer <JWT>`).
- **Request:** `{"name": "Aarav Kumar"}` (name $\ge 2$ and $\le 50$ characters, trimmed; email cannot be changed here).
- **Success:** `200 OK` $\to$ `{"id": 1, "name": "Aarav Kumar", "email": "a@ex.com", "createdAt": "2026-11-09T05:30:00Z"}`.
- **Errors:** `400 Bad Request` (name length <2 or >50, blank), `401 Unauthorized` (missing/expired token).

### 3.3B `POST /api/users/me/change-password` (FR-1A)
- **Auth:** Authenticated User (`Bearer <JWT>`).
- **Request:** `{"currentPassword": "OldPassword1!", "newPassword": "NewPassword2@"}` (same strength rules as registration: $\ge 8$ characters, at least 1 letter and 1 digit).
- **Rules:** Validates current password using BCrypt; rejects new password if identical to current password; rate limited (5 attempts per minute per user/IP).
- **Success:** `204 No Content`.
- **Errors:** `400 Bad Request` (validation failure / new password matches current password), `401 Unauthorized` (incorrect current password or missing token), `429 Too Many Requests` (rate limited).

### 3.3C `DELETE /api/users/me` (FR-1A)
- **Auth:** Authenticated User (`Bearer <JWT>`).
- **Request:** `{"password": "Password1!"}`.
- **Rules:** Verifies account password using BCrypt before deletion; rate limited (5 attempts per 15 minutes). If user is currently hosting an active room, returns `409 Conflict`. Upon confirmation, permanently deletes the user account and cascades room participation, files, messages, and OTP challenges.
- **Success:** `204 No Content`.
- **Errors:** `400 Bad Request` (missing password), `401 Unauthorized` (incorrect password or missing token), `409 Conflict` ("Cannot delete account while hosting an active meeting. Please end the meeting first."), `429 Too Many Requests`.

### 3.4 `POST /api/rooms` (FR-4)
- **Auth:** Authenticated User.
- **Success:** `201 Created` $\to$ `{"roomCode": "ABC-DEF", "hostId": 1, "isActive": true, "createdAt": "2026-11-09T05:30:00Z"}`.

### 3.5 `GET /api/rooms/{code}` (FR-5, FR-7)
- **Auth:** Authenticated User.
- **Success:** `200 OK` $\to$ `{"roomCode": "ABC-DEF", "hostId": 1, "isActive": true, "participantCount": 2, "maxParticipants": 5}`.
- **Errors:** `404` (not found), `409` (room full/inactive).

### 3.6 `POST /api/rooms/{code}/end` (FR-6)
- **Auth:** Room Host.
- **Success:** `200 OK` $\to$ `{"message": "Room ABC-DEF ended"}`. Errors: `403` (not host), `404`.

### 3.7 `GET /api/rooms/{code}/participants` (FR-5)
- **Auth:** Room Participant.
- **Success:** `200 OK` $\to$ `[{"userId": 1, "name": "Aarav", "joinedAt": "2026-11-09T05:30:00Z"}]`.

### 3.8 `GET /api/rooms/{code}/messages` (FR-22)
- **Auth:** Room Participant.
- **Success:** `200 OK` $\to$ `[{"id": 10, "senderId": 1, "senderName": "Aarav", "content": "Hello", "sentAt": "2026-11-09T05:32:00Z"}]`.

### 3.9 `GET /api/rooms/{code}/files` (FR-17)
- **Auth:** Room Participant.
- **Success:** `200 OK` $\to$ `[{"id": 5, "originalName": "doc.pdf", "sizeBytes": 204800, "uploaderName": "Aarav", "uploadedAt": "..."}]`.

### 3.10 `POST /api/files/upload/{code}` (FR-15, FR-16)
- **Auth:** Room Participant.
- **Request:** `multipart/form-data` with `file` ($\le 10$MB). Whitelist: PDF, DOC/X, TXT, PNG, JPG, ZIP. Block: `.exe`, `.bat`, `.sh`, `.js`.
- **Success:** `201 Created` $\to$ `{"id": 5, "originalName": "doc.pdf", "sizeBytes": 204800, "uploaderId": 1}`.
- **Errors:** `413` (>10MB), `415` (blocked type).

### 3.11 `GET /api/files/download/{id}` (FR-17)
- **Auth:** Room Participant.
- **Success:** `200 OK` binary stream with `Content-Disposition: attachment; filename="doc.pdf"`. Errors: `403`, `404`.

### 3.12 `GET /api/webrtc/ice-config` (FR-24)
- **Auth:** Authenticated User.
- **Success:** `200 OK` $\to$ `{"iceServers": [{"urls": ["stun:stun.l.google.com:19302"]}, {"urls": ["turn:turn.meetapp.com:3478"], "username": "u", "credential": "p"}]}`.

### 3.13 `GET /actuator/health` (NFR-4)
- **Auth:** Public.
- **Success:** `200 OK` $\to$ `{"status": "UP"}`.

## 4. Authentication Details
- **Token:** JJWT HMAC-SHA256. Claims: `sub` (userId), `email`, `iat`, `exp` (24h).
- **Expiry:** Expired tokens return `401 Unauthorized`; client redirects to `/login.html`.
- **Logout:** Client deletes token from `localStorage`. No server session.

## 5. WebSocket Lifecycle
- **Endpoint:** `GET /ws?token=<JWT>&room=<code>`.
- **Handshake:** Validates JWT, room code, and capacity ($<5$).
- **Close Codes:** `1000` (Normal exit), `1008` (Policy Violation: full, duplicate tab, expired token).
- **Reconnection:** Client retries up to 3 times with exponential backoff.

## 6. WebSocket Message Envelope
```json
{"type": "chat", "roomCode": "ABC-DEF", "from": "1", "to": null, "payload": {}}
```
*Rule:* Server overwrites `from` with authenticated session user ID; ignores client-supplied `from`.

## 7. WebSocket Message Catalog

| Type | Direction | Target | Payload Example | Limits & Rules |
| :--- | :--- | :--- | :--- | :--- |
| `join` | C $\to$ S | Server | `{}` | Emitted after open |
| `peer-joined` | S $\to$ C | Room | `{"userId": 2, "name": "Priya"}` | Notifies room peers |
| `peer-left` | S $\to$ C | Room | `{"userId": 2}` | Notifies disconnect |
| `offer` | C $\to$ S $\to$ C | Peer (`to`) | `{"sdp": "v=0..."}` | WebRTC SDP offer |
| `answer` | C $\to$ S $\to$ C | Peer (`to`) | `{"sdp": "v=0..."}` | WebRTC SDP answer |
| `ice-candidate`| C $\to$ S $\to$ C | Peer (`to`) | `{"candidate": "..."}` | WebRTC ICE candidate |
| `chat` | Bidirectional | Room | `{"text": "Hello!", "timestamp": "..."}` | Max 1000 chars |
| `draw` | Bidirectional | Room | `{"tool":"pen","color":"#000","size":2,"x":12,"y":14}` | Max 500 points |
| `clear-board` | Bidirectional | Room | `{}` | Resets canvas |
| `board-state` | S $\to$ C | Peer | `{"strokes": [...]}` | Late-joiner sync |
| `screen-share-start` | C $\to$ S $\to$ C | Room | `{"userId": 1}` | Screen share start |
| `screen-share-stop` | C $\to$ S $\to$ C | Room | `{"userId": 1}` | Screen share stop |
| `file-shared` | S $\to$ C | Room | `{"fileId": 5, "name": "doc.pdf", "size": 2048}` | Broadcast |
| `mute-status` | Bidirectional | Room | `{"audioMuted": true, "videoMuted": false}` | Device toggles |
| `room-ended` | S $\to$ C | Room | `{"message": "Host ended meeting"}` | Disconnects peers |
| `error` | S $\to$ C | Peer | `{"code": "ROOM_FULL", "message": "Max 5"}` | Error notification |

*Limit:* Max envelope size: 64 KB.

## 8. Protocol Sequence Examples

### 8.1 Two-Step Login & Join Room
```mermaid
sequenceDiagram
    participant C as Client
    participant API as REST /api
    participant WS as WebSocket /ws
    C->>API: POST /api/auth/login (email, password)
    API-->>C: 200 OK (otpRequired, challengeId)
    Note over API: Server sends 6-digit OTP via email
    C->>API: POST /api/auth/verify-otp (challengeId, otp)
    API-->>C: 200 OK (JWT token, user)
    C->>WS: Connect /ws?token=JWT&room=ABC-DEF
    WS-->>C: 101 Switching Protocols
    C->>WS: {"type": "join"}
    WS-->>C: {"type": "board-state", "payload": {...}}
```

### 8.2 WebRTC Exchange
```mermaid
sequenceDiagram
    participant C1 as Client 1
    participant WS as WebSocket
    participant C2 as Client 2
    C1->>WS: {"type":"offer","to":2,"payload":{"sdp":"..."}}
    WS->>C2: {"type":"offer","from":1,"payload":{"sdp":"..."}}
    C2->>WS: {"type":"answer","to":1,"payload":{"sdp":"..."}}
    WS->>C1: {"type":"answer","from":2,"payload":{"sdp":"..."}}
    C1->>WS: {"type":"ice-candidate","to":2,"payload":{...}}
    WS->>C2: {"type":"ice-candidate","from":1,"payload":{...}}
```

## 9. Permissions Matrix
| Action / Endpoint | Anonymous | User | Participant | Host |
| :--- | :---: | :---: | :---: | :---: |
| `POST /api/auth/*` | Yes | Yes | Yes | Yes |
| `POST /api/rooms` | No | Yes | Yes | Yes |
| `GET /api/rooms/{code}` | No | Yes | Yes | Yes |
| `POST /api/rooms/{code}/end` | No | No | No | Yes |
| `GET /api/rooms/{code}/*` | No | No | Yes | Yes |
| `POST /api/files/upload/{code}` | No | No | Yes | Yes |
| `WS /ws` (Join room) | No | Yes | Yes | Yes |
| `WS screen-share-start` | No | No | Yes | Yes |

## 10. Rate Limiting & Abuse Protection
- **Login / Verify OTP:** 5 req/min per IP ($\to$ `429`).
- **File Upload:** 2 uploads/min per user ($\to$ `429`).
- **Chat:** 5 msg/sec per user (dropped if exceeded).
- **Draw:** 60 events/sec per user.

## 11. Versioning & Mapping
- Version: **v1** under `/api`. Breaking changes bump to `/v2`.
- **Contracts:** `auth.js` (`/register`, `/login`, `/verify-otp`, `/resend-otp`), `dashboard.js` (`/rooms`, `/me`), `socket.js` (`/ws`), `webrtc.js` (`ice-config`, `offer`, `answer`, `ice-candidate`), `room.js` (`/end`, `mute-status`, `room-ended`), `screenshare.js` (`screen-share-*`), `chat.js` (`/messages`, `chat`), `files.js` (`/files/*`, `file-shared`), `whiteboard.js` (`draw`, `clear-board`, `board-state`).

## 13. OpenAPI & Postman Verification
- SpringDoc OpenAPI at `/swagger-ui.html` (`/v3/api-docs`).
- **Checklist:**
  1. `POST /api/auth/register` $\to$ 201 Created.
  2. `POST /api/auth/login` $\to$ 200 OK (`challengeId`, `otpRequired: true`).
  3. `POST /api/auth/verify-otp` with valid OTP $\to$ 200 OK with Bearer JWT.
  4. `POST /api/rooms` with JWT $\to$ 201 Created (`roomCode`).
  5. `POST /api/files/upload/{code}` with 1MB PDF $\to$ 201 Created; `.exe` $\to$ 415.
  6. `GET /api/files/download/{id}` $\to$ 200 OK binary stream.
  7. `POST /api/rooms/{code}/end` $\to$ 200 OK; next join $\to$ 409.

## 14. Open Questions
- *Token Refresh:* Introduce `POST /api/auth/refresh` in v2?
- *File Expiry:* Should file download URLs expire after room termination?
