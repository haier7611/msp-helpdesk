#!/bin/sh
# Restore drill (spec section 10). Installed on the app host as
# /usr/local/sbin/ticketsys-restore-drill by your deploy process. Run as root:
#   ticketsys-restore-drill [db-<UTC>.dump] [--keep]
# Restores a nightly backup (default: the newest) into a SCRATCH database next to the live one and
# proves it is usable:
#   1. the dump restores without error;
#   2. every table holds exactly the rows the dump carries (counted from the dump's own data, so
#      it does not matter how far the live database has moved on since the backup);
#   3. the attachments archive of the same night exists, is readable and holds every file the
#      restored attachment rows point at (missing ones are listed by key);
#   4. the erasure log of the LIVE database is replayed into the restored one (ts_erase_user), and
#      no erased person keeps a name or an email address;
# then drops the scratch database (unless --keep) and writes the result to backups/RESTORE_DRILL_LAST.
# It never writes to the live database or the backup files. Exit 0 = drill passed, 1 = failed.
#
# Test hooks (used by RestoreDrillScriptIntegrationTest, not needed on the host):
#   TS_BACKUP_DIR  backups folder (default /opt/ticket-system/backups)
#   TS_PG_LOCAL=1  run psql/pg_restore directly (the script itself runs inside the postgres
#                  container) instead of through `docker exec ticketsys-postgres`
#   TS_DRILL_DB    scratch database name (default ticketsys_restore_drill)
# POSIX sh on purpose: it also runs under busybox in the postgres:alpine test container.
set -eu
umask 077

DIR="${TS_BACKUP_DIR:-/opt/ticket-system/backups}"
CONTAINER="${TS_PG_CONTAINER:-ticketsys-postgres}"
SCRATCH="${TS_DRILL_DB:-ticketsys_restore_drill}"
LOCAL="${TS_PG_LOCAL:-0}"
KEEP=0
DUMP=""
for a in "$@"; do
  case "$a" in
    --keep) KEEP=1 ;;
    -*) echo "usage: ticketsys-restore-drill [db-<UTC>.dump] [--keep]"; exit 64 ;;
    *) DUMP="$a" ;;
  esac
done
case "$SCRATCH" in
  *[!a-z0-9_]* | "") echo "restore-drill: bad scratch database name"; exit 64 ;;
esac

# pgx '<shell script>' args... runs the script where psql lives (inside the postgres container),
# with POSTGRES_USER/POSTGRES_DB from that container's environment. Local socket: no password.
pgx() {
  script="$1"
  shift
  if [ "$LOCAL" = 1 ]; then
    sh -c "$script" pgx "$@"
  else
    docker exec -i "$CONTAINER" sh -c "$script" pgx "$@"
  fi
}
# q <database> <sql>: one value per line, unaligned. stdin closed so loops are never swallowed.
q() {
  pgx 'psql -X -q -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -tAc "$2"' "$1" "$2" < /dev/null
}

START="$(date +%s)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ticketsys-drill.XXXXXX")"
RESULT="FAILED"
finish() {
  code=$?
  if [ "$KEEP" = 0 ]; then
    q postgres "DROP DATABASE IF EXISTS $SCRATCH" > /dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
  if [ -d "$DIR" ] && [ -w "$DIR" ]; then
    printf '%s %s dump=%s seconds=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RESULT" \
      "$(basename "${DUMP:-none}")" "$(($(date +%s) - START))" > "$DIR/RESTORE_DRILL_LAST" || true
  fi
  exit "$code"
}
trap finish EXIT
fail() {
  echo "restore-drill: FAILED - $*"
  exit 1
}

if [ -z "$DUMP" ]; then
  DUMP="$(ls -1t "$DIR"/db-*.dump 2> /dev/null | head -n 1 || true)"
fi
[ -n "$DUMP" ] || fail "no dump found in $DIR"
[ -f "$DUMP" ] || DUMP="$DIR/$DUMP"
[ -f "$DUMP" ] || fail "dump $DUMP not found"
STAMP="$(basename "$DUMP" .dump | sed 's/^db-//')"
ARCHIVE="$DIR/attachments-$STAMP.tar.gz"
LIVE="$(pgx 'printf %s "$POSTGRES_DB"' < /dev/null)"
[ "$LIVE" != "$SCRATCH" ] || fail "scratch database name equals the live one"
echo "restore-drill: dump $(basename "$DUMP") ($(wc -c < "$DUMP" | tr -d ' ') bytes), live database $LIVE, scratch $SCRATCH"

# 1. Restore into a fresh scratch database.
q postgres "DROP DATABASE IF EXISTS $SCRATCH" > /dev/null
q postgres "CREATE DATABASE $SCRATCH" > /dev/null
pgx 'pg_restore -U "$POSTGRES_USER" -d "$1" --no-owner --exit-on-error' "$SCRATCH" < "$DUMP" \
  || fail "pg_restore reported an error"
echo "restore-drill: restored in $(($(date +%s) - START)) s"

# 2. Row counts: the dump's own COPY data against the restored tables.
pgx 'pg_restore -f - --data-only' < "$DUMP" | awk '
  /^COPY / { t = $2; n = 0; inside = 1; next }
  inside && $0 == "\\." { print t, n; inside = 0; next }
  inside { n++ }
' > "$WORK/expected"
[ -s "$WORK/expected" ] || fail "the dump holds no table data"
TABLES=0
MISMATCH=0
ROWS=0
for pair in $(awk '{ print $1 "=" $2 }' "$WORK/expected"); do
  t="${pair%=*}"
  want="${pair##*=}"
  got="$(q "$SCRATCH" "SELECT count(*) FROM $t")"
  TABLES=$((TABLES + 1))
  ROWS=$((ROWS + got))
  if [ "$got" != "$want" ]; then
    echo "restore-drill:   $t: dump $want rows, restored $got"
    MISMATCH=$((MISMATCH + 1))
  fi
done
for t in public.app_user public.organisation public.ticket public.public_comment public.attachment public.audit_event; do
  if grep -q "^$t " "$WORK/expected"; then
    echo "restore-drill:   $t: $(q "$SCRATCH" "SELECT count(*) FROM $t") rows"
  fi
done
[ "$MISMATCH" = 0 ] || fail "$MISMATCH table(s) restored with a different row count"
echo "restore-drill: row counts match the dump for all $TABLES tables ($ROWS rows)"
echo "restore-drill: flyway latest backup=$(q "$SCRATCH" "SELECT max(version::int) FROM flyway_schema_history WHERE success") live=$(q "$LIVE" "SELECT max(version::int) FROM flyway_schema_history WHERE success")"

# 3. Attachments: the archive of the same night holds every file the restored rows point at.
[ -f "$ARCHIVE" ] || fail "no attachments archive for this dump (expected $(basename "$ARCHIVE")) - attachments could not be restored"
tar -tzf "$ARCHIVE" > "$WORK/listing" 2> /dev/null || fail "attachments archive $(basename "$ARCHIVE") is not readable"
sed 's#.*/##' "$WORK/listing" | sort -u > "$WORK/files"
if [ "$(q "$SCRATCH" "SELECT to_regclass('public.attachment') IS NOT NULL")" = "t" ]; then
  q "$SCRATCH" "SELECT storage_key FROM attachment WHERE status = 'BOUND' UNION SELECT thumbnail_key FROM attachment WHERE status = 'BOUND' AND thumbnail_key IS NOT NULL" \
    | sort -u > "$WORK/keys"
else
  : > "$WORK/keys"
fi
comm -23 "$WORK/keys" "$WORK/files" > "$WORK/missing"
if [ -s "$WORK/missing" ]; then
  echo "restore-drill: attachment files missing from $(basename "$ARCHIVE"):"
  sed 's/^/restore-drill:   /' "$WORK/missing"
  fail "$(wc -l < "$WORK/missing" | tr -d ' ') attachment file(s) missing from the archive"
fi
echo "restore-drill: attachments archive $(basename "$ARCHIVE") holds all $(wc -l < "$WORK/keys" | tr -d ' ') referenced file(s) ($(wc -l < "$WORK/listing" | tr -d ' ') entries)"

# 4. Erasure-log replay (spec 10): people erased on the live system stay erased after a restore.
if [ "$(q "$SCRATCH" "SELECT to_regprocedure('ts_erase_user(bigint,timestamp with time zone)') IS NOT NULL")" = "t" ] \
  && [ "$(q "$LIVE" "SELECT to_regclass('public.user_erasure') IS NOT NULL")" = "t" ]; then
  IDS="$(q "$LIVE" "SELECT COALESCE(string_agg(DISTINCT user_id::text, ','), '') FROM user_erasure")"
  if [ -n "$IDS" ]; then
    q "$SCRATCH" "INSERT INTO user_erasure (user_id, erased_at) SELECT u.id, now() FROM app_user u WHERE u.id = ANY('{$IDS}'::bigint[]) AND NOT EXISTS (SELECT 1 FROM user_erasure e WHERE e.user_id = u.id)" > /dev/null
    REPLAYED="$(q "$SCRATCH" "SELECT count(*) FROM unnest('{$IDS}'::bigint[]) AS x(id) WHERE ts_erase_user(x.id, now())")"
    LEFT="$(q "$SCRATCH" "SELECT count(*) FROM app_user WHERE id = ANY('{$IDS}'::bigint[]) AND (email IS NOT NULL OR name <> 'Former user' OR erased_at IS NULL)")"
    [ "$LEFT" = 0 ] || fail "$LEFT erased user(s) still hold personal data after the replay"
    echo "restore-drill: erasure log replayed - $REPLAYED user(s) erased again, none keeps a name or email"
  else
    echo "restore-drill: erasure log is empty - nothing to replay"
  fi
else
  echo "restore-drill: this backup predates the erasure log (WP-12) - replay skipped"
fi

RESULT="OK"
echo "restore-drill: OK in $(($(date +%s) - START)) s$([ "$KEEP" = 1 ] && echo " - scratch database $SCRATCH kept, drop it with: DROP DATABASE $SCRATCH")"
