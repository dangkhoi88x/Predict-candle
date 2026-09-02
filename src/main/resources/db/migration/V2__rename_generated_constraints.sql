-- Databases that predate Flyway were built by Hibernate and carry its generated constraint
-- names ("uk9er8f1lva7e81sy29lb0uqpw"). They are baselined at V1, so they never ran the
-- readable names in it — this brings them in line, and does nothing on a fresh database that
-- already has them.

DO $$
DECLARE
    target record;
    found  text;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('assets',        'u', 'uq_assets_symbol',                    NULL),
            ('candles',       'u', 'uq_candles_asset_timeframe_open_time', NULL),
            ('users',         'u', 'uq_users_wallet_address',             NULL),
            ('guess_results', 'u', 'uq_guess_results_round_guess',        NULL),
            ('candles',       'f', 'fk_candles_asset',                    'assets'),
            ('guess_results', 'f', 'fk_guess_results_asset',              'assets'),
            ('guess_results', 'f', 'fk_guess_results_user',               'users')
        ) AS t(table_name, kind, wanted, references_table)
    LOOP
        SELECT conname INTO found
        FROM pg_constraint
        WHERE conrelid = target.table_name::regclass
          AND contype = target.kind
          AND conname <> target.wanted
          AND (target.references_table IS NULL
               OR confrelid = target.references_table::regclass)
        LIMIT 1;

        IF found IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I',
                           target.table_name, found, target.wanted);
        END IF;
    END LOOP;
END $$;
