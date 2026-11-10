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
    otp_challenges { VARCHAR id PK, BIGINT user_id FK, VARCHAR otp_hash, TIMESTAMPTZ expires_at, INT attempts, INT resend_count, BOOLEAN consumed, TIMESTAMPTZ created_at, TIMESTAMPTZ last_sent_at }
    rooms { BIGINT id PK, VARCHAR room_code UK, BIGINT host_id FK, BOOLEAN is_active, TIMESTAMPTZ created_at, TIMESTAMPTZ ended_at }
    room_participants { BIGINT id PK, BIGINT room_id FK, BIGINT user_id FK, TIMESTAMPTZ joined_at, TIMESTAMPTZ left_at }
    files { BIGINT id PK, BIGINT room_id FK, BIGINT uploader_id FK, VARCHAR original_name, VARCHAR stored_name UK, VARCHAR content_type, BIGINT size_bytes, TIMESTAMPTZ uploaded_at }
    messages { BIGINT id PK, BIGINT room_id FK, BIGINT sender_id FK, TEXT content, TIMESTAMPTZ sent_at }
```

## 3. Table Definitions
All primary keys use 64-bit `BIGINT GENERATED ALWAYS AS IDENTITY`, except `otp_challenges` which uses a secure UUID `id`.

### 3.1 `users` Table (FR-1, FR-2)
| Column | Type | Nullable | Constraints | Notes |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY, PK` | User ID |
| `name` | `VARCHAR(100)` | No | `CHECK (length(name) >= 2)` | Display name |
| `email` | `VARCHAR(255)` | No | `UNIQUE, CHECK (email = lower(email))` | Lowercase email |
| `password_hash`| `VARCHAR(255)` | No | - | BCrypt hash |
| `created_at` | `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Creation time |

### 3.2 `otp_challenges` Table (FR-3, FR-3A, FR-3B)
| Column | Type | Nullable | Constraints | Notes |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `VARCHAR(64)` | No | `PK` | Random challenge UUID |
| `user_id` | `BIGINT` | No | `FK -> users(id)` | Target user |
| `otp_hash` | `VARCHAR(255)` | No | - | HMAC-SHA256 hash |
| `expires_at` | `TIMESTAMPTZ` | No | - | 5-min expiry timestamp |
| `attempts` | `INT` | No | `DEFAULT 0, CHECK (attempts <= 5)` | Failed attempt counter |
| `resend_count`| `INT` | No | `DEFAULT 0, CHECK (resend_count BETWEEN 0 AND 3)` | Resend limiter (max 3) |
| `consumed` | `BOOLEAN` | No | `DEFAULT FALSE` | Single-use flag |
| `created_at` | `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Creation time |
| `last_sent_at`| `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Cooldown tracker (60s) |

### 3.3 `rooms` Table (FR-4, FR-5, FR-6)
| Column | Type | Nullable | Constraints | Notes |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY, PK` | Room ID |
| `room_code` | `VARCHAR(32)` | No | `UNIQUE, CHECK (length(room_code) >= 6)` | Room code |
| `host_id` | `BIGINT` | No | `FK -> users(id)` | Room host |
| `is_active` | `BOOLEAN` | No | `DEFAULT TRUE` | Open flag |
| `created_at` | `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Start time |
| `ended_at` | `TIMESTAMPTZ` | Yes | - | Close time |

### 3.4 `room_participants` Table (FR-5, FR-7)
| Column | Type | Nullable | Constraints | Notes |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY, PK` | Record ID |
| `room_id` | `BIGINT` | No | `FK -> rooms(id)` | Room reference |
| `user_id` | `BIGINT` | No | `FK -> users(id)` | User reference |
| `joined_at` | `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Join time |
| `left_at` | `TIMESTAMPTZ` | Yes | - | Exit time (`NULL`=active) |

### 3.5 `files` Table (FR-15, FR-16, FR-17)
| Column | Type | Nullable | Constraints | Notes |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY, PK` | File ID |
| `room_id` | `BIGINT` | No | `FK -> rooms(id)` | Target room |
| `uploader_id` | `BIGINT` | No | `FK -> users(id)` | Uploader ID |
| `original_name`| `VARCHAR(255)` | No | - | Sanitized name |
| `stored_name` | `VARCHAR(255)` | No | `UNIQUE` | UUID disk name |
| `content_type` | `VARCHAR(100)` | No | - | MIME type |
| `size_bytes` | `BIGINT` | No | `CHECK (size_bytes > 0)` | Positive file size (limit enforced in code) |
| `uploaded_at` | `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Upload time |

### 3.6 `messages` Table (FR-22)
| Column | Type | Nullable | Constraints | Notes |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `BIGINT` | No | `IDENTITY, PK` | Message ID |
| `room_id` | `BIGINT` | No | `FK -> rooms(id)` | Room ID |
| `sender_id` | `BIGINT` | No | `FK -> users(id)` | Sender ID |
| `content` | `TEXT` | No | `CHECK (length(trim(content)) > 0)` | Chat text |
| `sent_at` | `TIMESTAMPTZ` | No | `DEFAULT NOW()` | Sent time |

## 4. Constraints & Indexes
- **Foreign Keys:** `rooms.host_id -> users(id)` (`RESTRICT`); all others cascade on parent user/room delete.
- **Indexes:**
  - `idx_otp_user_expires` on `otp_challenges(user_id, expires_at)`
  - `idx_participants_active` on `room_participants(room_id, user_id)` WHERE `left_at IS NULL`
  - `idx_files_room` on `files(room_id)`
  - `idx_messages_room_sent` on `messages(room_id, sent_at DESC)`
  *(Note: `users.email` and `rooms.room_code` are indexed automatically by their `UNIQUE` constraints).*

## 5. Flyway Migration Plan
- Sequential migrations (`V1`..`V9`) executed automatically on startup (`ddl-auto=validate`):
  - `V1__baseline_check.sql`: Bootstrap baseline check.
  - `V2__create_users.sql`: Creates `users` table and check constraints.
  - `V3__create_rooms.sql`: Creates `rooms` table with foreign key to `users`.
  - `V4__create_room_participants.sql`: Creates `room_participants` table with cascading foreign keys.
  - `V5__create_files.sql`: Creates `files` table with `size_bytes > 0` constraint.
  - `V6__create_messages.sql`: Creates `messages` table with non-empty text check.
  - `V7__create_otp_challenges.sql`: Creates `otp_challenges` with attempts and resend count limits.
  - `V8__create_indexes.sql`: Creates performance indexes supporting key queries.
  - `V9__enable_row_level_security.sql`: Enables RLS on all 6 application tables without public policies.

## 6. Key Application Queries
```sql
SELECT id, name, email, password_hash FROM users WHERE email = :email;
SELECT id, user_id, otp_hash, expires_at, attempts, consumed FROM otp_challenges WHERE id = :id AND consumed = FALSE AND expires_at > NOW();
INSERT INTO rooms (room_code, host_id, is_active, created_at) VALUES (:roomCode, :hostId, TRUE, NOW()) RETURNING id;
SELECT COUNT(*) FROM room_participants WHERE room_id = :roomId AND left_at IS NULL;
UPDATE rooms SET is_active = FALSE, ended_at = NOW() WHERE room_code = :roomCode;
SELECT m.id, m.content, m.sent_at, u.name AS sender_name FROM messages m JOIN users u ON m.sender_id = u.id WHERE m.room_id = :roomId ORDER BY m.sent_at ASC LIMIT 50;
```

## 7. Data Lifecycle & Row-Level Security
- **Active Call:** Real-time writes for participants, chat, and files.
- **Disk File Deletion:** Deleting a file row triggers `LocalStorageService.deleteFile(stored_name)`.
- **Database Defense (RLS):**
```sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY; ALTER TABLE otp_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE rooms ENABLE ROW LEVEL SECURITY; ALTER TABLE room_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE files ENABLE ROW LEVEL SECURITY; ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
```

## 8. Connecting to Supabase (testing)
MeetApp supports testing against Supabase PostgreSQL:
- **Connection Mode:** Connect through the Supabase **Session Pooler** on port `5432` (`aws-1-ap-south-1.pooler.supabase.com:5432`). Do NOT use Transaction Pooler mode on port `6543`, which disrupts Flyway schema migrations and prepared statements.
- **Username Format:** `postgres.<PROJECT-REF>` (e.g. `postgres.abcdefghijklmnopqrst`).
- **Transport Encryption:** `sslmode=require` is strictly required in the JDBC URL.
- **Password Reset:** If credentials fail, reset your password in the Supabase Dashboard under *Project Settings > Database > Database Password*.
- **Isolation Notice:** Supabase is utilized for local development testing only. Production environments use a dedicated isolated database instance.
- **Connection Pool Limits:** Free-tier connection caps are respected via HikariCP settings: `maximum-pool-size: 5`, `minimum-idle: 1`, `connection-timeout: 20000`.

### Troubleshooting Matrix
| Error | Cause | Resolution |
| :--- | :--- | :--- |
| `Password authentication failed` | Wrong password or unescaped special characters | Reset password in Supabase Dashboard; URL-encode characters like `#` (`%23`) if passing in raw URI |
| `Connection timeout` | Wrong host, blocked outbound port, or paused project | Verify host `*.pooler.supabase.com`, port `5432`, and ensure project is unpaused |
| `SSL error / SSL handshake` | Missing SSL flag in JDBC connection URL | Ensure `?sslmode=require` is appended to `DB_URL` |
| `No tables appearing` | Flyway not executed or pointing to different schema | Check `flyway_schema_history` in `public` schema; verify migrations executed on startup |

## 9. Open Questions
- *File Retention:* Purge files immediately on room end or keep for a grace period?
- *OTP Pruning:* Schedule cron job to purge expired consumed challenges weekly?
