# MeetApp — Database Design Document (DATABASE.md)

## 1. Overview
MeetApp persists core relational data in PostgreSQL while keeping transient states in memory and raw binaries on disk:
- **PostgreSQL:** Users, OTP challenges, rooms, participants, file meta, chat.
- **In-Memory:** Session registry (`RoomSessionRegistry`) & canvas stroke buffer (`WhiteboardService`).
- **Local Disk:** File binaries in `/uploads` behind `StorageService`.

## 2. Entity-Relationship Diagram
```mermaid
erDiagram
    users ||--o{ otp_challenges : "has"
    users ||--o{ rooms : "hosts"
    users ||--o{ room_participants : "joins"
    users ||--o{ files : "uploads"
    users ||--o{ messages : "sends"
    rooms ||--o{ room_participants : "contains"
    rooms ||--o{ files : "has"
    rooms ||--o{ messages : "receives"

    users { BIGINT id PK, VARCHAR email UK, VARCHAR name, VARCHAR password_hash, TIMESTAMPTZ created_at }
    otp_challenges { VARCHAR id PK, BIGINT user_id FK, VARCHAR otp_hash, TIMESTAMPTZ expires_at, INT attempts, BOOLEAN consumed, TIMESTAMPTZ created_at, TIMESTAMPTZ last_sent_at }
    rooms { BIGINT id PK, VARCHAR room_code UK, BIGINT host_id FK, BOOLEAN is_active, TIMESTAMPTZ created_at, TIMESTAMPTZ ended_at }
    room_participants { BIGINT id PK, BIGINT room_id FK, BIGINT user_id FK, TIMESTAMPTZ joined_at, TIMESTAMPTZ left_at }
    files { BIGINT id PK, BIGINT room_id FK, BIGINT uploader_id FK, VARCHAR original_name, VARCHAR stored_name UK, VARCHAR content_type, BIGINT size_bytes, TIMESTAMPTZ uploaded_at }
    messages { BIGINT id PK, BIGINT room_id FK, BIGINT sender_id FK, TEXT content, TIMESTAMPTZ sent_at }
```

## 3. Table Definitions
All primary keys use 64-bit `BIGINT GENERATED ALWAYS AS IDENTITY`, except `otp_challenges` which uses a secure UUID string token `id`.

### 3.1 `users` Table (FR-1, FR-2)
| Column | Type | Nullable | Default | Constraints | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY` | `PK` | User ID |
| `name` | `VARCHAR(100)` | No | - | `CHECK (length(name) >= 2)` | Display name |
| `email` | `VARCHAR(255)` | No | - | `UNIQUE, CHECK (email = lower(email))` | Lowercase email |
| `password_hash`| `VARCHAR(255)` | No | - | - | BCrypt hash |
| `created_at` | `TIMESTAMPTZ` | No | `NOW()` | - | Creation time |

### 3.2 `otp_challenges` Table (FR-3, FR-3A, FR-3B)
| Column | Type | Nullable | Default | Constraints | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `VARCHAR(64)` | No | - | `PK` | Random challenge UUID |
| `user_id` | `BIGINT` | No | - | `FK -> users(id)` | Target user |
| `otp_hash` | `VARCHAR(255)` | No | - | - | Hash of 6-digit OTP |
| `expires_at` | `TIMESTAMPTZ` | No | - | - | 5-min expiry timestamp |
| `attempts` | `INT` | No | `0` | `CHECK (attempts <= 5)` | Failed attempt counter |
| `consumed` | `BOOLEAN` | No | `FALSE` | - | Single-use flag |
| `created_at` | `TIMESTAMPTZ` | No | `NOW()` | - | Creation time |
| `last_sent_at`| `TIMESTAMPTZ` | No | `NOW()` | - | Cooldown tracker (60s) |

### 3.3 `rooms` Table (FR-4, FR-5, FR-6)
| Column | Type | Nullable | Default | Constraints | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY` | `PK` | Room ID |
| `room_code` | `VARCHAR(32)` | No | - | `UNIQUE, CHECK (length(room_code) >= 6)` | Room code |
| `host_id` | `BIGINT` | No | - | `FK -> users(id)` | Room host |
| `is_active` | `BOOLEAN` | No | `TRUE` | - | Open flag |
| `created_at` | `TIMESTAMPTZ` | No | `NOW()` | - | Start time |
| `ended_at` | `TIMESTAMPTZ` | Yes | `NULL` | - | Close time |

### 3.4 `room_participants` Table (FR-5, FR-7)
| Column | Type | Nullable | Default | Constraints | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY` | `PK` | Record ID |
| `room_id` | `BIGINT` | No | - | `FK -> rooms(id)` | Room reference |
| `user_id` | `BIGINT` | No | - | `FK -> users(id)` | User reference |
| `joined_at` | `TIMESTAMPTZ` | No | `NOW()` | - | Join time |
| `left_at` | `TIMESTAMPTZ` | Yes | `NULL` | - | Exit time (`NULL`=active) |

### 3.5 `files` Table (FR-15, FR-16, FR-17)
| Column | Type | Nullable | Default | Constraints | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY` | `PK` | File ID |
| `room_id` | `BIGINT` | No | - | `FK -> rooms(id)` | Target room |
| `uploader_id` | `BIGINT` | No | - | `FK -> users(id)` | Uploader ID |
| `original_name`| `VARCHAR(255)` | No | - | - | Sanitized name |
| `stored_name` | `VARCHAR(255)` | No | - | `UNIQUE` | UUID disk name |
| `content_type` | `VARCHAR(100)` | No | - | - | MIME type |
| `size_bytes` | `BIGINT` | No | - | `CHECK (size_bytes > 0 AND size_bytes <= 10485760)` | Max 10 MB |
| `uploaded_at` | `TIMESTAMPTZ` | No | `NOW()` | - | Upload time |

### 3.6 `messages` Table (FR-22)
| Column | Type | Nullable | Default | Constraints | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY` | `PK` | Message ID |
| `room_id` | `BIGINT` | No | - | `FK -> rooms(id)` | Room ID |
| `sender_id` | `BIGINT` | No | - | `FK -> users(id)` | Sender ID |
| `content` | `TEXT` | No | - | `CHECK (length(trim(content)) > 0)` | Chat text |
| `sent_at` | `TIMESTAMPTZ` | No | `NOW()` | - | Sent time |

## 4. Constraints & Integrity Rules
- **Foreign Keys:** `rooms.host_id -> users(id)` (`RESTRICT`); all others cascade on parent user/room delete.
- **Integrity:** `email` unique and lowercase; `size_bytes` $\le 10$MB; OTP strictly hashed at rest.

## 5. Indexes
| Index Name | Table | Columns | Purpose |
| :--- | :--- | :--- | :--- |
| `idx_users_email` | `users` | `email` | User lookup during login |
| `idx_otp_user_expires` | `otp_challenges` | `user_id, expires_at` | Challenge lookup & expiry prune |
| `idx_rooms_code` | `rooms` | `room_code` | Room lookup by code |
| `idx_participants_active`| `room_participants` | `room_id, user_id` WHERE `left_at IS NULL` | Active 5-user capacity check |
| `idx_files_room` | `files` | `room_id` | List files for room |
| `idx_messages_room_sent` | `messages` | `room_id, sent_at DESC` | Ordered chat history |

## 6. Flyway Migration Plan
Sequential migrations (`V1`..`V7`) executed on startup:
- `V1__create_users.sql`, `V2__create_rooms.sql`, `V3__create_room_participants.sql`, `V4__create_files.sql`, `V5__create_messages.sql`, `V6__create_indexes.sql`, `V7__create_otp_challenges.sql`.

```sql
-- V1: users
CREATE TABLE users (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL CHECK (length(name)>=2), email VARCHAR(255) NOT NULL UNIQUE CHECK (email=lower(email)), password_hash VARCHAR(255) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
-- V2: rooms
CREATE TABLE rooms (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, room_code VARCHAR(32) NOT NULL UNIQUE CHECK (length(room_code)>=6), host_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ended_at TIMESTAMPTZ);
-- V3: room_participants
CREATE TABLE room_participants (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), left_at TIMESTAMPTZ);
-- V4: files
CREATE TABLE files (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, uploader_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, original_name VARCHAR(255) NOT NULL, stored_name VARCHAR(255) NOT NULL UNIQUE, content_type VARCHAR(100) NOT NULL, size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760), uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
-- V5: messages
CREATE TABLE messages (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE, sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, content TEXT NOT NULL CHECK (length(trim(content)) > 0), sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
-- V6: indexes
CREATE INDEX idx_users_email ON users(email); CREATE INDEX idx_rooms_code ON rooms(room_code); CREATE INDEX idx_participants_active ON room_participants(room_id, user_id) WHERE left_at IS NULL; CREATE INDEX idx_files_room ON files(room_id); CREATE INDEX idx_messages_room_sent ON messages(room_id, sent_at DESC);
-- V7: otp_challenges
CREATE TABLE otp_challenges (id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, otp_hash VARCHAR(255) NOT NULL, expires_at TIMESTAMPTZ NOT NULL, attempts INT NOT NULL DEFAULT 0 CHECK (attempts <= 5), consumed BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), last_sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE INDEX idx_otp_user_expires ON otp_challenges(user_id, expires_at);
```

## 7. JPA Mapping Notes
- Entities: `User`, `OtpChallenge`, `Room`, `RoomParticipant`, `FileMeta`, `ChatMessage`.
- `@ManyToOne` associations use `FetchType.LAZY`. DTOs used exclusively. Hibernate `ddl-auto=validate`.

## 8. Key Application Queries
```sql
SELECT id, name, email, password_hash FROM users WHERE email = :email;
SELECT id, user_id, otp_hash, expires_at, attempts, consumed FROM otp_challenges WHERE id = :id AND consumed = FALSE AND expires_at > NOW();
INSERT INTO rooms (room_code, host_id, is_active, created_at) VALUES (:roomCode, :hostId, TRUE, NOW()) RETURNING id;
SELECT COUNT(*) FROM room_participants WHERE room_id = :roomId AND left_at IS NULL;
UPDATE rooms SET is_active = FALSE, ended_at = NOW() WHERE room_code = :roomCode;
SELECT m.id, m.content, m.sent_at, u.name AS sender_name FROM messages m JOIN users u ON m.sender_id = u.id WHERE m.room_id = :roomId ORDER BY m.sent_at ASC LIMIT 50;
```

## 9. Data Lifecycle & Row-Level Security
- **Active Call:** Real-time writes for participants, chat, and files.
- **Disk File Deletion:** Deleting a file row triggers `LocalStorageService.deleteFile(stored_name)`.
- **Database Defense (RLS):**
```sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY; ALTER TABLE otp_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE rooms ENABLE ROW LEVEL SECURITY; ALTER TABLE room_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE files ENABLE ROW LEVEL SECURITY; ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
```

## 10. Development & Operations
- `docker-compose.yml` provides Postgres 15 on port 5432 with Flyway auto-migration on startup.
- Backup: `pg_dump -h $DB_HOST -U $DB_USER -d $DB_NAME --no-owner --clean > backup.sql`.
- In dev profile, OTPs are logged to app console for testing without active SMTP.

## 11. Open Questions
- *File Retention:* Purge files immediately on room end or keep for a grace period?
- *OTP Pruning:* Schedule cron job to purge expired consumed challenges weekly?
