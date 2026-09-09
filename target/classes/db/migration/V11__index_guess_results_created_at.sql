-- The admin overview groups every guess ever recorded by day, month or year.
--
-- The only index on guess_results is (user_id, created_at), which serves the per-player
-- stats read but cannot help a query with no user_id in it: those scan the whole table, and
-- this is a page people leave open and refresh. A plain (created_at) index lets the range
-- filter cut the scan down to the window actually being charted.
CREATE INDEX IF NOT EXISTS idx_guess_results_created_at ON guess_results (created_at);
