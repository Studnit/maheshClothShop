#!/usr/bin/env bash
# Restore a dump. DESTRUCTIVE: replaces all current data. Stop the application first.
# Usage: deploy/restore.sh backups/mahesh_shop_YYYYMMDD_HHMMSS.dump
set -euo pipefail
cd "$(dirname "$0")/.."
export PGHOST="${DB_HOST:-localhost}" PGPORT="${DB_PORT:-5432}" PGUSER="${DB_USERNAME:-postgres}" PGPASSWORD="${DB_PASSWORD:-}"
DB="${DB_NAME:-mahesh_shop}"
FILE="${1:?usage: restore.sh <dump file>}"
[ -f "$FILE" ] || { echo "No such file: $FILE" >&2; exit 1; }
read -r -p "This REPLACES all data in $DB with $FILE. Application stopped? Type YES to continue: " ok
[ "$ok" = "YES" ] || { echo "Cancelled."; exit 1; }
dropdb --if-exists --force "$DB"
createdb "$DB"
pg_restore -d "$DB" --no-owner "$FILE"
echo "Restored from $FILE"
