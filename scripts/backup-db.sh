#!/usr/bin/env bash
# Dumps the database, checks the dump holds what it should, and encrypts it.
#
#   DATABASE_URL='postgresql://user:pass@host/db?sslmode=require' \
#   BACKUP_PASSPHRASE='…' scripts/backup-db.sh [out-dir]
#
# Writes <out-dir>/candles-<UTC stamp>.dump.gpg and nothing else: the plaintext dump never
# outlives this script. Encrypted because the nightly job uploads it as an artifact, and on a
# public repository any signed-in GitHub user can download an artifact — the dump holds every
# player's wallet address and history.
#
# Everything runs inside postgres:16, so the only requirement is Docker: pg_dump matches the
# server's major version (a newer client is fine, an older one refuses), and that image already
# carries gpg. restore-db.sh is the other half.
set -euo pipefail

: "${DATABASE_URL:?DATABASE_URL is required}"
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE is required}"

out_dir="${1:-backup}"
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"
name="candles-$(date -u +%Y%m%dT%H%MZ).dump"

# Credentials reach the container as environment, never as arguments, so they do not show up in
# a process list or in the job log's echo of the command.
docker run --rm \
    -e DATABASE_URL -e BACKUP_PASSPHRASE -e NAME="$name" \
    -v "$out_dir:/out" \
    postgres:16 bash -euo pipefail -c '
        dump="/tmp/$NAME"
        pg_dump --format=custom --no-owner --no-privileges --dbname="$DATABASE_URL" --file="$dump"

        # A dump that ran against the wrong database, or an empty one, still exits 0. These are
        # the tables whose loss cannot be undone by re-fetching candles from the exchange.
        listing="$(pg_restore --list "$dump")"
        for table in users guess_results live_predictions pattern_quiz_results demo_accounts demo_trades flyway_schema_history; do
            if ! grep -q "TABLE DATA public $table " <<<"$listing"; then
                echo "backup check failed: no data section for table $table" >&2
                exit 1
            fi
        done
        echo "dump ok: $(du -h "$dump" | cut -f1), $(grep -c "TABLE DATA" <<<"$listing") tables with data"

        gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 3 \
            --symmetric --cipher-algo AES256 --output "/out/$NAME.gpg" "$dump" \
            3<<<"$BACKUP_PASSPHRASE"
        rm -f "$dump"
        chmod 644 "/out/$NAME.gpg"
    '

echo "$out_dir/$name.gpg"
