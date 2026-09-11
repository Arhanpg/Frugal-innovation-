-- FrugalCCTV v1 uses public Realtime channels with long random room codes.
-- The video itself is WebRTC DTLS-SRTP, so Supabase carries signaling only.
-- For stronger access control, enable Realtime Authorization + private channels later.

-- Enable anonymous sign-ins only if you later switch the signaling channel to private.
-- Current v1 does not require Auth because public Realtime channels keep setup frugal.
