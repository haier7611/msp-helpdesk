#!/usr/bin/env bash
# Proves a backup restores: loads a dump into a scratch database next to the live one, compares
# the Flyway history and table count with the live database, checks the attachments archive,
# then drops the scratch database. Never touches the live database.
#   ticketsys-restore-test [db-<time>.dump]     (default: the newest dump)
set -euo pipefail

DIR=/opt/ticket-system/backups
CONTAINER=ticketsys-postgres
SCRATCH=ticketsys_restore_test

DUMP="${1:-$(ls -1t "$DIR"/db-*.dump | head -1)}"
[ -f "$DUMP" ] || DUMP="$DIR/$DUMP"
[ -f "$DUMP" ] || { echo "restore-test: no dump found"; exit 1; }
ARCHIVE="$DIR/attachments-$(basename "$DUMP" .dump | sed 's/^db-//').tar.gz"

psql_c() { docker exec -i "$CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -tAc "$2"' _ "$@"; }

echo "restore-test: dump $(basename "$DUMP")"
psql_c postgres "DROP DATABASE IF EXISTS $SCRATCH"
psql_c postgres "CREATE DATABASE $SCRATCH"
trap 'psql_c postgres "DROP DATABASE IF EXISTS $SCRATCH" > /dev/null || true' EXIT

docker exec -i "$CONTAINER" sh -c "pg_restore -U \"\$POSTGRES_USER\" -d $SCRATCH --no-owner --exit-on-error" < "$DUMP"

LIVE_DB="$(docker exec "$CONTAINER" sh -c 'printf %s "$POSTGRES_DB"')"
Q_HIST="select string_agg(version || ':' || success, ',' order by installed_rank) from flyway_schema_history"
Q_TABLES="select count(*) from information_schema.tables where table_schema = 'public'"
LIVE_HIST="$(psql_c "$LIVE_DB" "$Q_HIST")"
REST_HIST="$(psql_c "$SCRATCH" "$Q_HIST")"
LIVE_TABLES="$(psql_c "$LIVE_DB" "$Q_TABLES")"
REST_TABLES="$(psql_c "$SCRATCH" "$Q_TABLES")"
echo "restore-test: flyway history live=[$LIVE_HIST] restored=[$REST_HIST]"
echo "restore-test: public tables live=$LIVE_TABLES restored=$REST_TABLES"
[ -n "$REST_HIST" ] && [ "$LIVE_HIST" = "$REST_HIST" ] && [ "$LIVE_TABLES" = "$REST_TABLES" ] \
  || { echo "restore-test: FAILED - restored database differs from live"; exit 1; }

if [ -f "$ARCHIVE" ]; then
  tar -tzf "$ARCHIVE" > /dev/null
  echo "restore-test: attachments archive $(basename "$ARCHIVE") readable ($(tar -tzf "$ARCHIVE" | wc -l) entries)"
else
  echo "restore-test: FAILED - no attachments archive for this dump"
  exit 1
fi
echo "restore-test: OK"
