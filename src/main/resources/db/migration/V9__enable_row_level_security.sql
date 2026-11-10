-- Enable Row Level Security (RLS) on all application tables.
-- With RLS enabled and NO policies defined, Supabase's automatic PostgREST HTTP API
-- is blocked from exposing application tables to public / anon web clients,
-- while the MeetApp backend connects directly via standard PostgreSQL JDBC (e.g. postgres role)
-- and retains unrestricted read/write access.

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE files ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE otp_challenges ENABLE ROW LEVEL SECURITY;
