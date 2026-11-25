#!/usr/bin/env bash
# One-command local start (docs/guides/local-development.md). Works on macOS and Linux with Docker
# (Compose v2.20+) and openssl; nothing else is needed on the host (no JDK, no Node).
#
#   scripts/dev-up.sh            first run: writes .env from .env.local.example with fresh secrets,
#                                then builds and starts the stack and waits until it is healthy
#   scripts/dev-up.sh --demo     the same, then loads the synthetic demo data (scripts/dev-seed.sh)
#
# An existing .env is never overwritten or changed. Settings read only when .env is first written:
#   TS_WEB_PORT      host port for the web UI           (default 8080)
#   TS_MAILPIT_PORT  host port for the Mailpit UI       (default 8025)
#   TS_DEV_PROJECT   compose project, container prefix, image tag (default ticketsys)
# Secrets are written only to .env (mode 600) and never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

DEMO=0
for a in "$@"; do
  case "$a" in
    --demo) DEMO=1 ;;
    -h | --help)
      sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "usage: scripts/dev-up.sh [--demo]" >&2
      exit 64
      ;;
  esac
done

die() {
  echo "dev-up: $*" >&2
  exit 1
}

command -v docker > /dev/null 2>&1 || die "docker is not installed (https://docs.docker.com/get-docker/)"
docker compose version > /dev/null 2>&1 || die "the Docker Compose plugin is missing (docker compose version)"
docker info > /dev/null 2>&1 || die "Docker is not running, or this user cannot reach it"

# port_in_use <port>: something on this machine already answers on 127.0.0.1:<port>.
port_in_use() {
  (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null
}

valid_port() {
  case "$1" in
    '' | *[!0-9]*) return 1 ;;
  esac
  [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

write_env() {
  command -v openssl > /dev/null 2>&1 || die "openssl is needed to generate the secrets"
  [ -f .env.local.example ] || die ".env.local.example is missing"
  local web_port="${TS_WEB_PORT:-8080}" mail_port="${TS_MAILPIT_PORT:-8025}"
  local project="${TS_DEV_PROJECT:-ticketsys}"
  valid_port "$web_port" || die "TS_WEB_PORT must be a number from 1024 to 65535"
  valid_port "$mail_port" || die "TS_MAILPIT_PORT must be a number from 1024 to 65535"
  [ "$web_port" != "$mail_port" ] || die "TS_WEB_PORT and TS_MAILPIT_PORT must differ"
  case "$project" in
    ticketsys | ticketsys-*) ;;
    *) die "TS_DEV_PROJECT must be ticketsys or start with ticketsys-" ;;
  esac
  printf '%s' "$project" | grep -Eq '^[a-z0-9][a-z0-9_-]*$' \
    || die "TS_DEV_PROJECT may hold only lower-case letters, digits, '-' and '_'"
  port_in_use "$web_port" && die "port $web_port is in use - set TS_WEB_PORT to a free port"
  port_in_use "$mail_port" && die "port $mail_port is in use - set TS_MAILPIT_PORT to a free port"

  local tmp
  tmp="$(mktemp .env.XXXXXX)"
  chmod 600 "$tmp"
  # Secrets go from openssl into shell variables and are written with builtins only, so they never
  # appear on a command line (ps) or in the output.
  local pg app setup key line
  pg="$(openssl rand -hex 24)"
  app="$(openssl rand -hex 24)"
  setup="$(openssl rand -hex 32)"
  key="$(openssl rand -hex 32)"
  if [ "${#pg}" -ne 48 ] || [ "${#app}" -ne 48 ] || [ "${#setup}" -ne 64 ] || [ "${#key}" -ne 64 ]; then
    rm -f "$tmp"
    die "openssl rand did not return the expected secrets"
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line//@@POSTGRES_PASSWORD@@/$pg}"
    line="${line//@@APP_DB_PASSWORD@@/$app}"
    line="${line//@@TS_SETUP_TOKEN@@/$setup}"
    line="${line//@@TS_DATA_KEY@@/$key}"
    line="${line//@@WEB_PORT@@/$web_port}"
    line="${line//@@MAILPIT_PORT@@/$mail_port}"
    line="${line//@@PROJECT@@/$project}"
    printf '%s\n' "$line"
  done < .env.local.example > "$tmp"
  if grep -q '@@' "$tmp"; then
    rm -f "$tmp"
    die "could not fill every value in .env.local.example"
  fi
  # ln fails if .env appeared meanwhile, so an existing file is never replaced.
  if ! ln "$tmp" .env 2> /dev/null; then
    rm -f "$tmp"
    die ".env appeared while it was being written - run again"
  fi
  rm -f "$tmp"
  echo "dev-up: wrote .env (mode 600) with new secrets, web port $web_port, Mailpit port $mail_port"
}

if [ -e .env ]; then
  echo "dev-up: using the existing .env (not changed)"
else
  write_env
fi

# env_value <NAME>: the value of NAME in .env (last one wins, like compose), or empty.
env_value() {
  sed -n "s/^$1=//p" .env | tail -1
}

if [ "$(env_value TS_SECURE_COOKIES)" != false ]; then
  echo "dev-up: note - TS_SECURE_COOKIES is not false in .env; over plain http the browser drops the" \
    "sign-in cookie. Use .env.local.example for local runs."
fi

if command -v git > /dev/null 2>&1 && git rev-parse --short HEAD > /dev/null 2>&1; then
  GIT_SHA="$(git rev-parse --short HEAD)"
  export GIT_SHA
fi

START="$(date +%s)"
echo "dev-up: building and starting (the first build downloads dependencies and takes a few minutes)"
docker compose up -d --build --wait --wait-timeout 900
echo "dev-up: stack healthy after $(($(date +%s) - START)) s"

WEB_PORT_V="$(env_value WEB_PORT)"
MAIL_PORT_V="$(env_value MAILPIT_UI_PORT)"
PROFILES="$(env_value COMPOSE_PROFILES)"
URL="http://localhost:${WEB_PORT_V:-80}"

if [ "$DEMO" -eq 1 ]; then
  "$ROOT/scripts/dev-seed.sh"
fi

echo
echo "Ticket System is running: $URL"
case ",$PROFILES," in
  *,dev,* | *,demo,*) echo "Mailpit (every email the app sends): http://localhost:${MAIL_PORT_V:-8025}/mailpit/" ;;
esac
cat << EOF

First admin (once):
  1. Read the one-time setup token from .env (it is not printed here):
       grep '^TS_SETUP_TOKEN=' .env
  2. Open $URL/setup, paste the token, enter your name, email and a password of 12+ characters.
  3. Sign in and set up two-step sign-in with an authenticator app when asked.
  The token stops working once an admin exists.

Synthetic demo data:  scripts/dev-seed.sh   (accounts and passwords go to demo-credentials.txt)
Stop:                 scripts/dev-down.sh   (keeps the data)
Remove everything:    scripts/dev-down.sh --wipe
EOF
