#!/usr/bin/env bash
# Nightly backup on the app host (installed as /usr/local/sbin/ticketsys-backup, run by
# ticketsys-backup.timer). Writes to /opt/ticket-system/backups:
#   db-<UTC time>.dump              pg_dump custom format (restore with pg_restore)
#   attachments-<UTC time>.tar.gz   the attachments directory
# and deletes files older than 30 days (spec section 10: 30-day retention = erasure lag).
# Any failure exits non-zero; systemd then runs ticketsys-backup-failed.service, which writes
# backups/BACKUP_FAILED. Alert on that marker and on a stale (> 36 h) dump.
# NOTE: these backups sit on the same disk as the database; copy them off the host as well
# (docs/30-reliability.md).
set -euo pipefail
umask 077

BASE=/opt/ticket-system
DIR="$BASE/backups"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
CONTAINER=ticketsys-postgres
TS="$(date -u +%Y%m%dT%H%M%SZ)"

install -d -m 700 "$DIR"

# Local socket inside the container: trusted by the postgres image, no password needed.
docker exec "$CONTAINER" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > "$DIR/db-$TS.dump.partial"
# A dump that pg_restore cannot list is not a backup.
docker exec -i "$CONTAINER" pg_restore --list < "$DIR/db-$TS.dump.partial" > /dev/null
mv "$DIR/db-$TS.dump.partial" "$DIR/db-$TS.dump"

tar -C "$BASE/data" -czf "$DIR/attachments-$TS.tar.gz.partial" attachments
mv "$DIR/attachments-$TS.tar.gz.partial" "$DIR/attachments-$TS.tar.gz"

find "$DIR" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'attachments-*.tar.gz' -o -name '*.partial' \) \
  -mtime +"$RETENTION_DAYS" -delete

# Off-host copy (not built in): encrypt db-$TS.dump and attachments-$TS.tar.gz with a key kept
# outside the target and copy them off the host here (rsync over SSH or object storage). Let a failed copy exit non-zero so
# the unit fails and BACKUP_FAILED is written (docs/30-reliability.md "Backups").

# Success clears the failure marker left by ticketsys-backup-failed (OnFailure= in the unit).
rm -f "$DIR/BACKUP_FAILED"
date -u +%Y-%m-%dT%H:%M:%SZ > "$DIR/LAST_SUCCESS"

echo "backup: db-$TS.dump ($(stat -c %s "$DIR/db-$TS.dump") bytes)," \
  "attachments-$TS.tar.gz ($(stat -c %s "$DIR/attachments-$TS.tar.gz") bytes)"
