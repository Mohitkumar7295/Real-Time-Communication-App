CREATE TABLE otp_challenges (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    otp_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts <= 5),
    resend_count INT NOT NULL DEFAULT 0 CHECK (resend_count BETWEEN 0 AND 3),
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
