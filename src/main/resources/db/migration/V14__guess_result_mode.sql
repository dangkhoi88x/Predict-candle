-- Which game a recorded guess came from.
--
-- Two reasons, and the second is the one that made this a column rather than something derived.
--
-- The daily challenge is a practice-shaped round on a chart chosen from the date, so its guesses
-- have the same (asset, timeframe, start_index, guess_number) shape as any other. Without a mode
-- in the unique constraint, a player who happened to draw today's daily window in practice would
-- find their daily already answered — rare, but wrong, and impossible to explain to them.
--
-- And the point of the daily challenge is to move retention, which is measured off these rows.
-- A guess that cannot say which game it came from cannot answer whether the new game did
-- anything, which would leave the whole feature unfalsifiable.
--
-- Existing rows are all practice: the daily challenge does not exist before this migration.
ALTER TABLE guess_results
    ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'PRACTICE'
        CONSTRAINT ck_guess_results_mode CHECK (mode IN ('PRACTICE', 'DAILY'));

ALTER TABLE guess_results DROP CONSTRAINT uq_guess_results_round_guess;

ALTER TABLE guess_results
    ADD CONSTRAINT uq_guess_results_round_guess
        UNIQUE (user_id, mode, asset_id, timeframe, start_index, guess_number);
