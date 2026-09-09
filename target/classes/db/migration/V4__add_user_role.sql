-- Until now every authenticated account was equal, and the one place that needed a narrower
-- check (media upload) compared the caller's wallet against a config list by itself. This
-- gives the distinction a name the whole application can read.
--
-- Existing rows become USER: the accounts that should be ADMIN are named in
-- candles.admin.wallets and get promoted at startup, so no row here has to be guessed at.
ALTER TABLE users
    ADD COLUMN role varchar(16) NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));
