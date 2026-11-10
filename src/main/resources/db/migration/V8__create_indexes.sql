-- Supports finding unexpired OTP challenges by user ID and pruning expired challenges
CREATE INDEX idx_otp_user_expires ON otp_challenges (user_id, expires_at);

-- Supports counting active participants in a room where left_at IS NULL
CREATE INDEX idx_participants_active ON room_participants (room_id, user_id) WHERE left_at IS NULL;

-- Supports listing all files shared in a specific room
CREATE INDEX idx_files_room ON files (room_id);

-- Supports retrieving the most recent chat messages for a room ordered by sent_at DESC
CREATE INDEX idx_messages_room_sent ON messages (room_id, sent_at DESC);
