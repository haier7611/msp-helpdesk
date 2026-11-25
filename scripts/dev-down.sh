#!/usr/bin/env bash
# Stops the local stack started by scripts/dev-up.sh (docs/guides/local-development.md).
#
#   scripts/dev-down.sh          stop and remove the containers; the database and attachments stay
#   scripts/dev-down.sh --wipe   also delete THIS project's volumes (database, attachments) and
#                                demo-credentials.txt, after asking you to type the project name
#
# Only the compose project named in .env (COMPOSE_PROJECT_NAME) is touched. .env is kept, so the
# next scripts/dev-up.sh starts an empty database with the same settings and setup token.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

WIPE=0
for a in "$@"; do
  case "$a" in
    --wipe) WIPE=1 ;;
    *)
      echo "usage: scripts/dev-down.sh [--wipe]" >&2
      exit 64
      ;;
  esac
done

[ -f .env ] || {
  echo "dev-down: no .env here - nothing was started from this folder by scripts/dev-up.sh" >&2
  exit 1
}

PROJECT="$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' .env | tail -1)"
PROJECT="${PROJECT:-ticketsys}"

if [ "$WIPE" -eq 0 ]; then
  docker compose -p "$PROJECT" down
  echo "dev-down: stopped project $PROJECT; data kept (scripts/dev-up.sh starts it again)"
  exit 0
fi

# Fail closed: a production-style .env (real SMTP, secure cookies) is not a local stack.
if [ "$(sed -n 's/^TS_SECURE_COOKIES=//p' .env | tail -1)" != false ]; then
  echo "dev-down: .env has TS_SECURE_COOKIES other than false - this does not look like a local" \
    "stack from scripts/dev-up.sh, so --wipe refuses. Remove volumes by hand if you really mean it." >&2
  exit 1
fi

VOLS="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" | tr '\n' ' ')"
echo "dev-down: --wipe deletes the containers and these volumes of project '$PROJECT':"
echo "  ${VOLS:-(none found)}"
echo "and demo-credentials.txt. The database, attachments and demo data are gone for good."
if [ ! -t 0 ]; then
  echo "dev-down: reading the confirmation from standard input"
fi
printf "Type the project name (%s) to confirm: " "$PROJECT"
read -r answer || answer=""
if [ "$answer" != "$PROJECT" ]; then
  echo "dev-down: not confirmed, nothing deleted"
  exit 1
fi

docker compose -p "$PROJECT" down --volumes --remove-orphans
rm -f demo-credentials.txt
echo "dev-down: project $PROJECT stopped and its volumes deleted; .env kept"
