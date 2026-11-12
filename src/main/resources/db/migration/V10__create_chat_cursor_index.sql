-- V10__create_chat_cursor_index.sql
-- Optimizes cursor-based pagination for in-room group chat history

-- Creates composite index (room_id, id DESC) supporting cursor paging:
-- SELECT ... FROM messages WHERE room_id = :roomId AND (:beforeId IS NULL OR id < :beforeId) ORDER BY id DESC LIMIT :limit
CREATE INDEX idx_messages_room_id ON messages (room_id, id DESC);

-- Drops the redundant (room_id, sent_at DESC) index created in V8.
-- Since 'id' is a monotonically increasing identity column, ordering by (room_id, id DESC)
-- provides the exact same chronological sequence with guaranteed uniqueness for cursor pagination,
-- eliminating duplicate B-tree index maintenance overhead on message inserts.
DROP INDEX IF EXISTS idx_messages_room_sent;
