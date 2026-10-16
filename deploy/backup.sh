#!/usr/bin/env bash
# Nightly backup of the shop database with pg_dump. Needs the PostgreSQL client tools.
# Settings come from the same variables the app uses: DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD.
# cron example:  30 1 * * *  /path/to/deploy/backup.sh >> /path/to/backups/backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")/.."
export PGHOST="${DB_HOST:-localhost}" PGPORT="${DB_PORT:-5432}" PGUSER="${DB_USERNAME:-postgres}" PGPASSWORD="${DB_PASSWORD:-}"
DB="${DB_NAME:-mahesh_shop}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
mkdir -p backups
FILE="backups/mahesh_shop_$(date +%Y%m%d_%H%M%S).dump"
pg_dump -d "$DB" --format=custom --no-owner -f "$FILE"
pg_restore --list "$FILE" > /dev/null          # a dump that cannot be listed is not a backup
find backups -name 'mahesh_shop_*.dump' -mtime +"$RETENTION_DAYS" -delete
echo "Backup written: $FILE ($(du -h "$FILE" | cut -f1))"
