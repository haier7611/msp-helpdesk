#!/bin/sh
# Full export for moving hosts (ADR-028). Installed on the host as
# /usr/local/sbin/ticketsys-export by your deploy process. Run as root on the host shell (the
# host shell is the authorisation, like break-glass):
#   ticketsys-export [output-dir]        (default /opt/ticket-system/exports)
# Writes ticketsys-export-<UTC>.tar (mode 600) holding:
#   db.dump              pg_dump custom format of the whole database, WITHOUT the rows of live
#                        credentials (sessions, sign-in links, trusted devices) and job locks
#   attachments.tar.gz   the attachment store
#   settings.env         the non-secret settings from .env (public URL, mail sender, ...)
#   MANIFEST.txt         time, release, Flyway version, row counts, sha256 of each file
# Settings secrets are NOT exported: no database or SMTP password, no TS_DATA_KEY, no setup token.
# db.dump DOES hold credential material, on purpose, so users keep their sign-in on the new host:
# Argon2id password hashes, hashed MFA recovery codes, API-key hashes, and the MFA, mailbox and
# Microsoft sign-in secrets encrypted with TS_DATA_KEY (readable only with that key, which you
# carry separately in the password manager, never inside this archive). The nightly backup holds
# the same. So the archive is NOT a sanitised dataset: root-only, mode 600, copied only over SSH,
# deleted when the move is done, never used as test or demo data. Steps: docs/runbooks/move-domain-or-server.md.
#
# Test hooks (ExportScriptIntegrationTest): TS_BASE (default /opt/ticket-system), TS_PG_LOCAL=1 to
# run pg_dump directly inside the postgres container instead of through docker exec.
# POSIX sh on purpose: it also runs under busybox in the postgres:alpine test container.
set -eu
umask 077

BASE="${TS_BASE:-/opt/ticket-system}"
CONTAINER="${TS_PG_CONTAINER:-ticketsys-postgres}"
LOCAL="${TS_PG_LOCAL:-0}"
OUT="${1:-$BASE/exports}"
ENV_FILE="$BASE/.env"
ATTACHMENTS="$BASE/data/attachments"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="ticketsys-export-$TS"

# Settings that are safe to carry in the archive. Everything else in .env is left out on purpose;
# a new key added to .env stays out until it is reviewed and listed here.
SAFE_KEYS="POSTGRES_DB POSTGRES_USER TS_PUBLIC_BASE_URL TS_SECURE_COOKIES TS_DATA_KEY_ID SMTP_HOST SMTP_PORT SMTP_SECURITY SMTP_USERNAME MAIL_FROM_ADDRESS MAIL_SUPPORT_NAME MAIL_MESSAGE_ID_DOMAIN COMPOSE_PROFILES WEB_BIND WEB_PORT APP_IMAGE_TAG GIT_SHA"
# Rows that are live credentials or transient state: their tables are exported, their data not.
EXCLUDE_DATA="spring_session spring_session_attributes auth_token user_device scheduled_job_lock mail_poller_lock"

pgx() {
  script="$1"
  shift
  if [ "$LOCAL" = 1 ]; then
    sh -c "$script" pgx "$@"
  else
    docker exec -i "$CONTAINER" sh -c "$script" pgx "$@"
  fi
}
q() {
  pgx 'psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -tAc "$1"' "$1" < /dev/null
}

[ -f "$ENV_FILE" ] || { echo "export: $ENV_FILE not found"; exit 1; }
[ -d "$ATTACHMENTS" ] || { echo "export: attachment store $ATTACHMENTS not found"; exit 1; }
install -d -m 700 "$OUT"
WORK="$(mktemp -d "$OUT/.export.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/$NAME"
D="$WORK/$NAME"

EXCLUDES=""
for t in $EXCLUDE_DATA; do
  EXCLUDES="$EXCLUDES --exclude-table-data=public.$t"
done
# shellcheck disable=SC2016,SC2086
pgx 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc "$@"' $EXCLUDES < /dev/null > "$D/db.dump"
pgx 'pg_restore --list > /dev/null' < "$D/db.dump" || { echo "export: the dump is not readable"; exit 1; }

tar -C "$(dirname "$ATTACHMENTS")" -czf "$D/attachments.tar.gz" "$(basename "$ATTACHMENTS")"

{
  echo "# Non-secret settings exported $TS. Add the secrets on the new host (runbook)."
  for k in $SAFE_KEYS; do
    grep -E "^$k=" "$ENV_FILE" | tail -n 1 || true
  done
} > "$D/settings.env"

{
  echo "Ticket System export $TS"
  echo "source host: $(hostname)"
  echo "release: $(grep -E '^GIT_SHA=' "$ENV_FILE" | tail -n 1 | cut -d= -f2-)"
  echo "flyway version: $(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
  echo "rows: users=$(q "SELECT count(*) FROM app_user") organisations=$(q "SELECT count(*) FROM organisation") tickets=$(q "SELECT count(*) FROM ticket") attachments=$(q "SELECT count(*) FROM attachment")"
  echo "attachment files: $(tar -tzf "$D/attachments.tar.gz" | grep -c -v '/$' || true)"
  echo "excluded data: $EXCLUDE_DATA"
  echo "secrets: no settings secrets included; bring TS_DATA_KEY (and TS_DATA_KEY_ID) separately"
  echo "credentials: db.dump holds password hashes, hashed recovery codes and API keys, and encrypted MFA/mailbox/Microsoft secrets - not a sanitised dataset; root-only, delete after the move"
  echo "sha256:"
  (cd "$D" && sha256sum db.dump attachments.tar.gz settings.env)
} > "$D/MANIFEST.txt"

tar -C "$WORK" -cf "$OUT/$NAME.tar.partial" "$NAME"
chmod 600 "$OUT/$NAME.tar.partial"
mv "$OUT/$NAME.tar.partial" "$OUT/$NAME.tar"
echo "export: $OUT/$NAME.tar ($(wc -c < "$OUT/$NAME.tar" | tr -d ' ') bytes) - contains client personal data, handle it like a backup"
