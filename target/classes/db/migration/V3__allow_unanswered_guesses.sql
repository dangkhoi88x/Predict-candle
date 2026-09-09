-- The countdown (G6) can expire before the player answers. That guess still happened and
-- still counts against them, but there is no direction to record for it, so "wrong answer"
-- and "no answer" stop being the same row.
ALTER TABLE guess_results ALTER COLUMN guessed_direction DROP NOT NULL;
