-- Migration V11: Create composite index for file cursor pagination and drop redundant single-column index.
-- Supports GET /api/rooms/{code}/files ordering by (room_id, id DESC)

CREATE INDEX IF NOT EXISTS idx_files_room_id ON files (room_id, id DESC);

DROP INDEX IF EXISTS idx_files_room;
