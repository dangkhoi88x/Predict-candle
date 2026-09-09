-- Display order for the picker.
--
-- Until now the four pairs were four buttons written in index.html, in the order someone chose
-- when writing them: BTC first, because it is the pair most players come for. Building the
-- picker from the database replaced that with whatever the query returned — alphabetical, so
-- BNB ended up leading. This puts the choice back, in a place an admin can change.
ALTER TABLE assets ADD COLUMN position integer NOT NULL DEFAULT 100;

-- Seeded from the order in candles.assets as it stands today. A one-off restatement of the
-- previous hard-coded order; anything added later sorts after these on its own default.
UPDATE assets SET position = CASE symbol
    WHEN 'BTCUSDT' THEN 0
    WHEN 'ETHUSDT' THEN 1
    WHEN 'BNBUSDT' THEN 2
    WHEN 'SOLUSDT' THEN 3
    ELSE 100
END;
