-- Which trading pairs the game offers stops being a redeploy.
--
-- The list in candles.assets still seeds rows on startup, so nothing about an existing
-- deployment changes; what moves into the database is whether a pair is offered. A disabled
-- asset keeps its candles — it is a pair taken off the menu, not a decision to throw away
-- three years of history that would take a backfill to rebuild.
ALTER TABLE assets ADD COLUMN enabled boolean NOT NULL DEFAULT true;
