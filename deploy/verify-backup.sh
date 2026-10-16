#!/usr/bin/env bash
# Proves the newest backup restores: loads it into a throw-away database and runs scripts/integrity-check.sql.
set -euo pipefail
cd "$(dirname "$0")/.."
export PGHOST="${DB_HOST:-localhost}" PGPORT="${DB_PORT:-5432}" PGUSER="${DB_USERNAME:-postgres}" PGPASSWORD="${DB_PASSWORD:-}"
FILE="$(ls -1t backups/mahesh_shop_*.dump | head -1)"
echo "Verifying $FILE"
dropdb --if-exists mahesh_verify
createdb mahesh_verify
pg_restore -d mahesh_verify --no-owner "$FILE"
OUT="$(psql -d mahesh_verify -v ON_ERROR_STOP=1 < scripts/integrity-check.sql)"
dropdb mahesh_verify
if echo "$OUT" | grep -Eq '^\([1-9][0-9]* rows?\)'; then
  echo "$OUT"; echo "INTEGRITY PROBLEM in the backup - see rows above." >&2; exit 1
fi
echo "Backup verified."
