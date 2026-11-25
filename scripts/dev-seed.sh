#!/usr/bin/env bash
# Loads the synthetic demo data into a running local stack (docs/guides/local-development.md):
# example client organisations with users and tickets, agents and an admin - all made-up names on
# .example domains (the QA round data). Runs the api's own seeding command inside the api
# container, so nothing is installed on the host. Safe to run again: nothing is added twice and existing passwords stay.
#
#   scripts/dev-seed.sh                    seed (start the stack first: scripts/dev-up.sh)
#   scripts/dev-seed.sh --reset-passwords  give every demo account a new password
#
# The demo accounts' passwords and two-step sign-in secrets are written only to demo-credentials.txt
# (mode 600, git-ignored) in the repo root; nothing secret is printed.
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

EXTRA=()
for a in "$@"; do
  case "$a" in
    --reset-passwords) EXTRA=(--reset-passwords) ;;
    *)
      echo "usage: scripts/dev-seed.sh [--reset-passwords]" >&2
      exit 64
      ;;
  esac
done

LOCAL="demo-credentials.txt"
# The seeding command reads and rewrites the sheet inside the attachment store, which only the api
# container can see; it is copied in and out through docker compose exec and removed afterwards.
INSIDE="/data/attachments/.demo-credentials.txt"

[ -f .env ] || {
  echo "dev-seed: no .env - start the stack first with scripts/dev-up.sh" >&2
  exit 1
}
docker compose exec -T api true < /dev/null > /dev/null 2>&1 || {
  echo "dev-seed: the api container is not running - start it with scripts/dev-up.sh" >&2
  exit 1
}

remove_inside() {
  docker compose exec -T api rm -f "$INSIDE" < /dev/null > /dev/null 2>&1 || true
}
trap remove_inside EXIT

remove_inside
if [ -f "$LOCAL" ]; then
  docker compose exec -T api sh -c "umask 077; cat > '$INSIDE'" < "$LOCAL"
fi

echo "dev-seed: loading the synthetic demo data (one to three minutes)"
code=0
LOG="$(mktemp "$ROOT/.demo-seed-log.XXXXXX")"
# A second JVM in the api container: a smaller heap share so it fits next to the running api.
# Its start-up log goes to a temporary file; only the seeding summary is shown, the whole log only
# on failure. The log holds no secrets (the command never prints passwords).
docker compose exec -T api java -XX:MaxRAMPercentage=25 -jar /app/app.jar qa-seed \
  "--credentials-file=$INSIDE" ${EXTRA[@]+"${EXTRA[@]}"} < /dev/null > "$LOG" 2>&1 || code=$?
if [ "$code" -eq 0 ]; then
  grep '^qa-seed:' "$LOG" | sed 's/^qa-seed:/dev-seed:/' || true
else
  echo "dev-seed: the seeding command failed (exit $code); its log:" >&2
  tail -n 40 "$LOG" >&2
fi
rm -f "$LOG"

tmp="$(mktemp "$ROOT/.demo-credentials.XXXXXX")"
if docker compose exec -T api cat "$INSIDE" < /dev/null > "$tmp" 2> /dev/null && [ -s "$tmp" ]; then
  chmod 600 "$tmp"
  mv "$tmp" "$LOCAL"
  echo "dev-seed: demo accounts and passwords are in $LOCAL (mode 600, not committed)"
else
  rm -f "$tmp"
  echo "dev-seed: no credentials sheet was produced" >&2
  [ "$code" -ne 0 ] || code=1
fi
exit "$code"
