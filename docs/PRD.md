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
- **In Scope (MVP):** JWT auth; room creation via code/link; WebRTC mesh calling (max 5); pre-join preview; mic/cam toggles; desktop screen share; HTML5 canvas whiteboard with late-joiner sync; in-room chat with unread badge; file sharing ($\le 10$MB); DTLS-SRTP & HTTPS/WSS.
- **Out of Scope:** Native mobile apps; cloud recording; $>5$ participants; SFU/MCU servers; payments; PSTN dial-in.
- **Optional Features:** Whiteboard undo/redo & PNG export; client-side AES encryption; custom avatars.

## 4. Functional Requirements

### A. User Authentication
- **FR-1:** Users must register with name, email, and strong password ($\ge 8$ chars, $\ge 1$ upper, $\ge 1$ digit, $\ge 1$ special).
- **FR-2:** The system must reject duplicate emails and invalid inputs.
- **FR-3:** Authenticated users must receive a signed JWT (24h expiry); protected routes must reject unauthenticated requests.
- **Acceptance Criteria:**
  - *AC-1.1:* **Given** valid details, **when** user registers, **then** account is saved and login page loads.
  - *AC-1.2:* **Given** invalid credentials, **when** user logs in, **then** "Invalid credentials" error displays.

### B. Room Management
- **FR-4:** Users must create rooms with unique alphanumeric codes and join via code or URL (`/room/{code}`).
- **FR-5:** Rooms must maintain live participant lists and enforce a 5-participant maximum.
- **FR-6:** Users must be able to leave; hosts must have controls to end the meeting for all members.
- **FR-7:** Inactive, ended, or full rooms must reject entry with descriptive alerts.
- **Acceptance Criteria:**
  - *AC-2.1:* **Given** an active room with $<5$ members, **when** user joins, **then** pre-join opens and connects.
  - *AC-2.2:* **Given** a full room (5 members), **when** a 6th user joins, **then** entry is rejected with "Room full".

### C. Video and Audio Calling
- **FR-8:** Pre-join preview must test camera/mic permissions before room entry.
- **FR-9:** If media access is denied, users must be allowed entry in view/listen-only mode.
- **FR-10:** Video streams must arrange in a responsive grid with independent mic mute and camera toggles.
- **FR-11:** Mute/camera status must broadcast to all peers via signaling and update tile icons.
- **Acceptance Criteria:**
  - *AC-3.1:* **Given** active call, **when** user clicks Mute, **then** local mic stops and peers see mute icon.
  - *AC-3.2:* **Given** denied permissions, **when** joining, **then** fallback avatar shows with view-only badge.

### D. Screen Sharing
- **FR-12:** Desktop users must be able to share display/window, replacing their camera track in peer connections.
- **FR-13:** UI must indicate who is sharing; mobile clients must be view-only.
- **FR-14:** Stopping screen share must restore the local webcam stream immediately.
- **Acceptance Criteria:**
  - *AC-4.1:* **Given** desktop caller, **when** sharing screen, **then** peers receive desktop video instead of webcam.
  - *AC-4.2:* **Given** active screen share, **when** stopped, **then** peers receive restored webcam feed.

### E. File Sharing
- **FR-15:** Participants must be able to upload files up to 10 MB in allowed formats (PDF, DOC/X, TXT, PNG, JPG, ZIP).
- **FR-16:** Executables (`.exe`, `.bat`, `.sh`, `.js`) must be blocked and filenames sanitized against path traversal.
- **FR-17:** Uploads must list in the room drawer with name, size, sender, download link, and a chat notice.
- **Acceptance Criteria:**
  - *AC-5.1:* **Given** a 4 MB PDF, **when** uploaded, **then** it lists in room drawer and peers can download it.
  - *AC-5.2:* **Given** a `.exe` file, **when** uploaded, **then** server rejects it with HTTP 400.

### F. Collaborative Whiteboard
- **FR-18:** Canvas whiteboard must provide pen, eraser, color picker, brush size, and Clear controls.
- **FR-19:** Strokes must synchronize across participants via WebSocket in real time ($<100$ms).
- **FR-20:** Server must buffer stroke history and deliver current canvas state to late joiners on connect.
- **FR-21:** The board should support PNG export and stroke undo/redo.
- **Acceptance Criteria:**
  - *AC-6.1:* **Given** open whiteboard, **when** User A draws, **then** User B renders identical strokes in $<100$ms.
  - *AC-6.2:* **Given** existing drawings, **when** User C joins late, **then** full canvas state renders immediately.

### G. Group Chat
- **FR-22:** In-room chat must deliver broadcast messages with sender name and timestamp in real time.
- **FR-23:** Closed chat drawer must display an unread badge counter for incoming messages.
- **Acceptance Criteria:**
  - *AC-7.1:* **Given** active room, **when** message is sent, **then** all peers receive it in $<200$ms.
  - *AC-7.2:* **Given** closed chat, **when** messages arrive, **then** unread badge increments until opened.

### H. Security and Encryption
- **FR-24:** P2P media streams must use DTLS-SRTP encryption; APIs and signaling must use HTTPS/WSS.
- **FR-25:** Passwords must be hashed with BCrypt; JWT must be checked on REST APIs and WebSocket handshake.
- **FR-26:** CORS must restrict origins to frontend; uploads must validate extension and MIME type.
- **Acceptance Criteria:**
  - *AC-8.1:* **Given** unauthenticated request to `/api/rooms/create`, **when** sent, **then** returns HTTP 401.
  - *AC-8.2:* **Given** missing JWT token, **when** WebSocket connects, **then** handshake is rejected.

## 5. Non-Functional Requirements
- **NFR-1 (Performance):** P2P setup $<5$s; chat/whiteboard latency $<100$ms.
- **NFR-2 (Browsers):** Latest Chrome, Edge, Firefox, Safari (desktop & mobile).
- **NFR-3 (Responsive):** Operates on desktop ($1920\times1080$ to $1280\times720$), tablet, and mobile ($360$px+).
- **NFR-4 (Reliability):** Auto-reconnect WebSocket within 10s of network drop; clean media teardown.
- **NFR-5 (Accessibility):** WCAG 2.1 AA contrast, visible focus outlines, aria-labels on controls.
- **NFR-6 (Security):** Zero plain secrets in code/logs, sanitized inputs, BCrypt work factor $\ge 10$.
- **NFR-7 (Logging):** Structured SLF4J audit logs for room lifecycle, auth, and security exceptions.

## 6. User Flows
1. **Auth:** Register -> Login -> Store JWT -> Open Dashboard.
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
- **Network Drop:** Auto-reconnect WebSocket for 10s; gracefully exit if unreachable.
- **Permission Denied:** Alert user of blocked hardware; provide view/listen-only entry.
- **Room Full:** Reject 6th participant with "Room is full (max 5)" dialog.
- **Expired Token:** Clear credentials and redirect to login with timeout notice.
- **Unsupported Browser:** Detect missing `RTCPeerConnection` and display upgrade alert.
- **Invalid Upload:** Reject files $>10$MB or blocked types (`.exe`, `.bat`, etc.) on client and server.
- **Host Leaves:** Room persists if host exits without ending; closes for all if host clicks "End Meeting".
- **Duplicate Tab:** Block duplicate room entry by the same user account.

## 9. Assumptions, Constraints & Limitations
- **Mesh Limits:** Full mesh $N \times (N-1)$ limits practical concurrency to 5 participants.
- **NAT / Firewalls:** Symmetric NATs block direct P2P and require TURN relay fallback.
- **Mobile Screen Share:** Mobile browsers cannot share display surfaces due to OS sandbox (view-only).
- **Cold Starts:** Free container hosts may incur up to 60s cold start latency on first request.

## 10. Success Criteria & Demo Checklist
- [ ] 2 browser windows register, log in, and join room `DEMO1`.
- [ ] Bidirectional video and audio streams connect within 5 seconds.
- [ ] Mic mute and camera toggle update peer icons immediately.
- [ ] Desktop screen sharing replaces video feed on remote peer screens.
- [ ] Whiteboard strokes render simultaneously across two clients.
- [ ] Late joiner receives pre-existing whiteboard drawings upon entry.
- [ ] 2 MB PDF upload succeeds, alerts room, and downloads on peer.
- [ ] Uploading `.exe` is rejected with an HTTP 400 error.
- [ ] Host clicking "End Meeting" terminates sessions for all participants.

## 11. Glossary
- **WebRTC:** Browser API enabling direct peer-to-peer audio, video, and data exchange.
- **Signaling:** WebSocket exchange of ICE candidates and SDP offers/answers to establish P2P.
- **STUN / TURN:** Servers discovering public IPs (STUN) or relaying media across firewalls (TURN).
- **Mesh:** Topology where each participant directly connects to every other participant.
- **JWT:** Cryptographically signed token verifying identity between client and server.
- **DTLS-SRTP:** Protocols providing encryption and authentication for WebRTC media streams.

## 12. Open Questions
- Should host privileges transfer to another member if the creator disconnects unexpectedly?
- Should uploaded meeting files delete immediately when a room ends or persist for a grace period?
