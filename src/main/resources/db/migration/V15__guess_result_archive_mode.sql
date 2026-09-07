-- Replaying a past daily round is its own game, not the daily one.
--
-- The whole value of a daily streak is that it can only be kept by turning up on the day. If a
-- replayed round counted as DAILY it would stamp today as "played the daily" without today's
-- chart ever being opened, so a streak could be held indefinitely by working through the
-- archive — which is the same as not having a streak at all.
--
-- A third mode keeps them apart for free: the streak query already filters on mode = 'DAILY',
-- and the unique constraint already includes mode, so an archived round is limited to one
-- attempt of its own without touching the day it was originally set.
ALTER TABLE guess_results DROP CONSTRAINT ck_guess_results_mode;

ALTER TABLE guess_results
    ADD CONSTRAINT ck_guess_results_mode CHECK (mode IN ('PRACTICE', 'DAILY', 'ARCHIVE'));
