# MeetApp — Product Requirements Document (PRD)

## 1. Overview
- **Problem Statement:** Students and small teams lack instant, lightweight spaces for video, chat, file exchange, and sketching without installs or subscription fees.
- **Goal:** Deliver a secure real-time collaboration app with WebRTC mesh calling, in-room messaging, file sharing, and synced canvas whiteboard for up to 5 participants.
- **Vision:** "Provide teams with a friction-free browser workspace to meet, ideate, and collaborate."

## 2. Target Users & Personas
- **Target Users:** Students, study groups, freelancers, and small remote teams.
- **Persona 1: Aarav (Student):** Needs study calls with math sketching. *Pain point:* Free apps enforce 40-min caps and demand downloads.
- **Persona 2: Priya (Designer):** Needs to screen-share mockups and sketch edits live. *Pain point:* Fragmented calling and drawing tools.
- **Persona 3: Marcus (Freelancer):** Needs quick syncs to review code with clients. *Pain point:* Bulky sign-ups and tracking.

## 3. Scope
- **In Scope (MVP):** Email OTP 2FA login; room creation via code/link; WebRTC mesh calling (max 5); pre-join preview; mic/cam toggles; desktop screen share; HTML5 canvas whiteboard with late-joiner sync; in-room chat with unread badge; file sharing ($\le 10$MB); DTLS-SRTP & HTTPS/WSS.
- **Out of Scope:** Native mobile apps; cloud recording; $>5$ participants; SFU/MCU servers; payments; PSTN dial-in.
- **Optional Features:** Whiteboard undo/redo & PNG export; client-side AES encryption; custom avatars.

## 4. Functional Requirements

### A. User Authentication
- **FR-1:** Users must register with name, email, and strong password ($\ge 8$ chars, $\ge 1$ upper, $\ge 1$ digit, $\ge 1$ special).
- **FR-2:** The system must reject duplicate emails and invalid inputs.
- **FR-3 (Login Step 1):** Valid credentials generate a 6-digit email OTP challenge; no JWT is issued.
- **FR-3A (Login Step 2):** Validating OTP issues a 24h JWT. 5 failed attempts locks challenge; OTP expires in 5 min.
- **FR-3B (OTP Resend):** Resending OTP requires 60s cooldown, capped at 3 resends per challenge.
- *Dev Mode Note:* In dev profile, OTP is printed to app logs for testing without SMTP; strictly forbidden in prod.
- **Acceptance Criteria:**
  - *AC-1.1 (Register):* **Given** valid details, **when** user registers, **then** account is saved and login page loads.
  - *AC-1.2 (Step 1 Login):* **Given** valid credentials, **when** user submits login, **then** 6-digit OTP is sent and challenge ID returned without JWT.
  - *AC-1.3 (Step 2 Verify OTP):* **Given** active challenge, **when** valid OTP is submitted, **then** JWT is issued and dashboard opens.
  - *AC-1.4 (Lockout & Expiry):* **Given** 5 wrong attempts or expired OTP (>5m), **when** submitted, **then** challenge is invalidated.
  - *AC-1.5 (Resend Cooldown):* **Given** challenge within 60s, **when** resend requested, **then** request is rejected with 429.

### A1. User Profile & Account Management
- **FR-1A (Profile Management):** Authenticated users can view their profile details (`name`, `email`, `createdAt`), edit their display name (2–50 characters, trimmed; email is read-only), update their password (verifying current password with BCrypt, enforcing $\ge 8$ characters with letter and number, rejecting identical new passwords), and delete their account with password confirmation.
- **FR-1B (Dashboard Home & Readiness):** Authenticated users land on a dashboard featuring a time-of-day greeting ("Good morning / afternoon / evening, {Name}"), two prominent primary actions (Start Meeting and Join with Code with client-side uppercase auto-trim validation), a "Recent meetings" table/list with empty-state guidance, and an on-demand "Before you join" camera/mic readiness tool with zero auto-prompting on load.
- **Acceptance Criteria:**
  - *AC-1.6 (Profile Update):* **Given** authenticated user, **when** updating name to 2–50 characters, **then** name updates in database, success message appears, and layout header avatar/name reflects the change.
  - *AC-1.7 (Change Password):* **Given** authenticated user, **when** submitting correct current password and valid different new password, **then** password updates, fields clear, and success message displays. Wrong current password returns 401; identical password returns 400.
  - *AC-1.8 (Account Deletion):* **Given** authenticated user, **when** providing correct password and confirmation, **then** user record and cascading data are deleted, session is destroyed, and user redirects to landing page. If user hosts an active room, returns 409 Conflict.
  - *AC-1.9 (Dashboard & Device Check):* **Given** dashboard user, **when** clicking "Check devices", **then** browser prompts for media permission on demand, displays live permission and hardware status (allowed, blocked, or not found), and releases tracks immediately.

### B. Room Management
- **FR-4:** Users must create rooms with unique alphanumeric codes and join via code or URL (`/room/{code}`).
- **FR-5:** Rooms must maintain live participant lists and enforce a 5-participant maximum.
- **FR-6:** Users must be able to leave; hosts must have controls to end the meeting for all members.
- **FR-7:** Inactive, ended, or full rooms must reject entry with descriptive alerts.
- **Acceptance Criteria:**
  - *AC-2.1:* **Given** an active room with $<5$ members, **when** user joins, **then** pre-join opens and connects.
  - *AC-2.2:* **Given** a full room (5 members), **when** a 6th user joins, **then** entry is rejected with "Room full".

### C. Video and Audio Calling
- **FR-8:** Pre-join preview tests camera/mic before room entry; FR-9: denied media allows listen-only entry.
- **FR-10:** Video streams arrange in responsive grid with independent mic mute and camera toggles.
- **FR-11:** Mute/camera status broadcasts to all peers via signaling and updates tile icons.
- **Acceptance Criteria:**
  - *AC-3.1:* **Given** active call, **when** user clicks Mute, **then** mic stops and peers see mute icon.
  - *AC-3.2:* **Given** denied access, **when** joining, **then** fallback avatar shows with listen-only badge.

### D. Screen Sharing
- **FR-12:** Desktop users can share display/window, replacing camera track in peer connections.
- **FR-13:** UI indicates who is sharing; mobile is view-only; FR-14: stopping restores webcam immediately.
- **Acceptance Criteria:**
  - *AC-4.1:* **Given** desktop caller, **when** sharing screen, **then** peers receive screen video.
  - *AC-4.2:* **Given** screen share, **when** stopped, **then** peers receive restored webcam feed.

### E. File Sharing
- **FR-15:** Participants can upload files $\le 10$MB (PDF, DOC/X, TXT, PNG, JPG, ZIP).
- **FR-16:** Block executables (`.exe`, `.bat`, `.sh`, `.js`) and sanitize filenames.
- **FR-17:** Uploads list in room drawer with name, size, sender, download link, and chat notice.
- **Acceptance Criteria:**
  - *AC-5.1:* **Given** 4 MB PDF, **when** uploaded, **then** peers see file in drawer and can download.
  - *AC-5.2:* **Given** `.exe` file, **when** uploaded, **then** server rejects with HTTP 400.

### F. Collaborative Whiteboard
- **FR-18:** Canvas whiteboard provides pen, eraser, color picker, brush size, and Clear.
- **FR-19:** Strokes sync across peers via WebSocket ($<100$ms); FR-20: server buffers history for late joiners.
- **FR-21:** Whiteboard supports PNG export and stroke undo/redo.
- **Acceptance Criteria:**
  - *AC-6.1:* **Given** whiteboard, **when** User A draws, **then** User B renders identical stroke in $<100$ms.
  - *AC-6.2:* **Given** drawings, **when** User C joins late, **then** full canvas renders immediately.

### G. Group Chat
- **FR-22:** In-room chat delivers broadcast messages with sender name and timestamp in real time.
- **FR-23:** Closed chat drawer displays unread badge counter for incoming messages.
- **Acceptance Criteria:**
  - *AC-7.1:* **Given** active room, **when** message sent, **then** peers receive it in $<200$ms.
  - *AC-7.2:* **Given** closed chat, **when** messages arrive, **then** unread badge increments.

### H. Security and Encryption
- **FR-24:** P2P media uses DTLS-SRTP; APIs and signaling use HTTPS/WSS.
- **FR-25:** Passwords hashed with BCrypt; JWT validated on REST and WebSocket handshake.
- **FR-26:** CORS restricted to frontend; uploads validated against whitelist.
- **Acceptance Criteria:**
  - *AC-8.1:* **Given** unauthenticated call to `/api/rooms/create`, **when** sent, **then** returns HTTP 401.
  - *AC-8.2:* **Given** missing JWT, **when** WebSocket connects, **then** handshake is rejected.

## 5. Non-Functional Requirements
- **NFR-1 (Perf):** P2P setup $<5$s; chat/board latency $<100$ms.
- **NFR-2 (Browsers):** Latest Chrome, Edge, Firefox, Safari.
- **NFR-3 (Responsive):** Desktop ($1920\times1080$ to $1280\times720$), tablet, mobile ($360$px+).
- **NFR-4 (Reliability):** Auto-reconnect WebSocket within 10s of network drop.
- **NFR-5 (A11y):** WCAG 2.1 AA contrast, visible focus, aria-labels on controls.
- **NFR-6 (Security):** Zero plain secrets in logs, sanitized inputs, BCrypt work factor $\ge 10$.
- **NFR-7 (Logs):** Structured SLF4J audit logs for lifecycle, auth, and security.

## 6. User Flows
1. **Auth:** Register -> Login Step 1 (email/pass) -> OTP emailed -> Login Step 2 (enter OTP) -> JWT issued -> Dashboard.
2. **Meeting:** Dashboard -> Create/Join Room -> Pre-join check -> Connect to call.
3. **Screen Share:** Click Share Screen -> Select surface -> Video replaced -> Stop restores camera.
4. **File Share:** Open Files -> Drag file ($\le 10$MB) -> Upload validates -> Peers notified & download.
5. **Whiteboard:** Open Whiteboard -> Pick tool/color -> Draw -> WebSocket relays coordinates to peers.
6. **Leave/End:** Participant clicks Leave -> Exits to Dashboard. Host clicks End -> Closes room for all.

## 7. Screens and Pages
| Page | Key Elements & Actions |
| :--- | :--- |
| **Landing** | Hero header, feature cards, "Sign In" and "Get Started" buttons. |
| **Signup / Login** | Inputs (name, email, password), validation alerts, submit buttons. |
| **Dashboard** | Greeting, "Create Meeting" button, "Join by Code" input, logout button. |
| **Pre-Join** | Camera preview, audio meter, mic/cam toggles, "Join Room" button. |
| **Meeting Room** | Video grid, bottom bar (Mic, Cam, Screen, Board, Files, Chat, Leave/End), side drawer. |
| **Meeting Ended** | Status banner, call duration, "Back to Dashboard" button. |

## 8. Error and Edge Cases
- **Network Drop:** Auto-reconnect WebSocket for 10s; graceful exit if unreachable.
- **Permission Denied:** Alert user of blocked hardware; allow listen-only entry.
- **Room Full:** Reject 6th participant with "Room full (max 5)" dialog.
- **Expired Token:** Clear credentials and redirect to login with timeout notice.
- **Unsupported Browser:** Detect missing `RTCPeerConnection` and render upgrade alert.
- **Invalid Upload:** Reject files $>10$MB or blocked types (`.exe`, `.bat`, etc.).
- **Host Leaves:** Room persists if host leaves; terminates if host ends meeting.
- **Duplicate Tab:** Block duplicate room entry by same user.

## 9. Assumptions, Constraints & Limitations
- **Mesh Limits:** Full mesh $N \times (N-1)$ limits concurrency to 5 participants.
- **NAT/Firewalls:** Symmetric NATs block P2P and require TURN relay fallback.
- **Mobile Screen Share:** Mobile browsers cannot share display surfaces (view-only).
- **Cold Starts:** Free container hosts may incur up to 60s cold start latency.

## 10. Success Criteria & Demo Checklist
- [ ] 2 browser windows register, verify email OTP, and join room `DEMO1`.
- [ ] Bidirectional video and audio streams connect within 5 seconds.
- [ ] Mic mute and camera toggle update peer icons immediately.
- [ ] Desktop screen sharing replaces video feed on remote peer screens.
- [ ] Whiteboard strokes render simultaneously across two clients.
- [ ] Late joiner receives pre-existing whiteboard drawings upon entry.
- [ ] 2 MB PDF upload succeeds, alerts room, and downloads on peer.
- [ ] Uploading `.exe` is rejected with an HTTP 400 error.
- [ ] Host clicking "End Meeting" terminates sessions for all participants.

## 11. Glossary
- **WebRTC:** API enabling peer-to-peer audio, video, and data exchange.
- **Signaling:** WebSocket exchange of ICE candidates and SDP offers/answers.
- **STUN / TURN:** Servers discovering public IPs (STUN) or relaying media across firewalls (TURN).
- **Mesh:** Topology where each client directly connects to every other participant.
- **JWT:** Signed cryptographic token carrying user claims.
- **DTLS-SRTP:** Protocols providing encryption for WebRTC media streams.

## 12. Open Questions
- Should host privileges transfer to another member if the creator disconnects unexpectedly?
- Should uploaded meeting files delete immediately when a room ends or persist for a grace period?
