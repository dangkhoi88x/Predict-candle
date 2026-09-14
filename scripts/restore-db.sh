#!/usr/bin/env bash
# Restores a dump made by backup-db.sh into a database.
#
#   DATABASE_URL='postgresql://…' BACKUP_PASSPHRASE='…' scripts/restore-db.sh candles-….dump.gpg
#
# Restore into an EMPTY database — on Neon, a new branch — never over the live one. The target
# gets the schema and data exactly as dumped, flyway_schema_history included, so an app pointed
# at it afterwards starts without re-running any migration. Swapping the demo over to the
# restored branch is then a change of DB_URL on Render, and undoing it is changing it back.
set -euo pipefail

: "${DATABASE_URL:?DATABASE_URL is required}"
: "${BACKUP_PASSPHRASE:?BACKUP_PASSPHRASE is required}"
file="${1:?usage: restore-db.sh <file.dump.gpg>}"
[ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }

dir="$(cd "$(dirname "$file")" && pwd)"
docker run --rm \
    -e DATABASE_URL -e BACKUP_PASSPHRASE -e NAME="$(basename "$file")" \
    -v "$dir:/in:ro" \
    postgres:16 bash -euo pipefail -c '
        gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 \
            --decrypt --output /tmp/restore.dump "/in/$NAME" 3<<<"$BACKUP_PASSPHRASE"
        pg_restore --no-owner --no-privileges --exit-on-error --dbname="$DATABASE_URL" /tmp/restore.dump
        echo "restored $NAME"
    '
