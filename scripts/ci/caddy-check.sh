#!/usr/bin/env bash
# Real-stack check of the web Caddyfile's Mailpit route (ADR-030, QA-2), run by scripts/ci/web.sh
# and by hand:  scripts/ci/caddy-check.sh
# Starts the pinned Caddy image from web/Dockerfile with web/Caddyfile in front of the pinned
# Mailpit image from docker-compose.yml (MP_WEBROOT=/mailpit) on a private ticketsys-ci- network,
# publishes no host port, and proves:
#   - the Caddyfile loads with MAILPIT_UI_HASH unset, "disabled" (what compose passes for an empty value) and set;
#   - hash set: no credentials = 401 with a challenge, wrong password = 401, right password = the
#     real Mailpit UI (200, title Mailpit) and the base security headers are still there;
#   - hash unset or "disabled": /mailpit/ answers 404 even with the right credentials (fails closed);
#   - the rest of the site keeps its headers and is not asked for a password;
#   - caching: index.html, SPA routes, root files and a missing /assets/ file answer
#     "Cache-Control: no-cache"; an existing hashed /assets/ file answers
#     "public, max-age=31536000, immutable"; /api/* is not given a Cache-Control by Caddy.
# Synthetic, test-only password (not a secret). Removes only its own containers and network.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"
TAG="${TICKETSYS_CI_TAG:-manual-$(id -un)-$$}"
TAG="$(printf '%s' "$TAG" | tr -c 'A-Za-z0-9_.-' '-')"
NET="ticketsys-ci-caddy-net-${TAG}"
MP="ticketsys-ci-caddy-mailpit-${TAG}"
ON="ticketsys-ci-caddy-on-${TAG}"
EMPTY="ticketsys-ci-caddy-empty-${TAG}"
UNSET="ticketsys-ci-caddy-unset-${TAG}"
CADDY_IMAGE="$(sed -n 's/^FROM \(caddy:[^ ]*\) AS runtime$/\1/p' "$ROOT/web/Dockerfile")"
MAILPIT_IMAGE="$(sed -n 's/^ *image: \(axllent\/mailpit:[^ ]*\)$/\1/p' "$ROOT/docker-compose.yml")"
PASSWORD="synthetic-test-only-password-0123"
[ -n "$CADDY_IMAGE" ] && [ -n "$MAILPIT_IMAGE" ] || { echo "caddy-check: could not read the pinned images"; exit 1; }

cleanup() {
  docker rm -f "$MP" "$ON" "$EMPTY" "$UNSET" > /dev/null 2>&1 || true
  docker network rm "$NET" > /dev/null 2>&1 || true
}
trap cleanup EXIT
fail() { echo "caddy-check: FAIL - $1"; exit 1; }

echo "caddy-check: caddy $CADDY_IMAGE, mailpit $MAILPIT_IMAGE"
docker network create "$NET" > /dev/null
# The hash goes in through stdin, never a command line.
HASH="$(printf '%s' "$PASSWORD" | docker run --rm -i "$CADDY_IMAGE" caddy hash-password)"
[ "${HASH#\$2}" != "$HASH" ] || fail "no bcrypt hash produced"

docker run -d --name "$MP" --network "$NET" --network-alias mailpit --memory=128m \
  -e MP_WEBROOT=/mailpit "$MAILPIT_IMAGE" > /dev/null
# A stub site root so the app routes answer 200 like the real image does.
SITE="$(mktemp -d)"
printf '<!doctype html><div id="root"></div>\n' > "$SITE/index.html"
mkdir "$SITE/assets"
printf 'console.log(1)\n' > "$SITE/assets/index-abc123.js"
printf 'User-agent: *\n' > "$SITE/robots.txt"
chmod 755 "$SITE" "$SITE/assets"
chmod 644 "$SITE/index.html" "$SITE/assets/index-abc123.js" "$SITE/robots.txt"
start_caddy() { # name, then docker -e args
  local name="$1"
  shift
  docker run -d --name "$name" --network "$NET" --memory=128m \
    -v "$ROOT/web/Caddyfile:/etc/caddy/Caddyfile:ro" -v "$SITE:/srv:ro" "$@" "$CADDY_IMAGE" \
    caddy run --config /etc/caddy/Caddyfile --adapter caddyfile > /dev/null
}
start_caddy "$ON" -e MAILPIT_UI_USER=qa -e "MAILPIT_UI_HASH=$HASH"
start_caddy "$EMPTY" -e MAILPIT_UI_USER=qa -e MAILPIT_UI_HASH=disabled
start_caddy "$UNSET"

# status <container> <path> [user:password]  ->  "<code>"; the response headers of the last call
# are kept in $HFILE (read with headers), because $(...) runs status in a subshell.
HFILE="$(mktemp)"
trap 'rm -rf "$HFILE" "$SITE"; cleanup' EXIT
headers() { cat "$HFILE"; }
status() {
  local c="$1" path="$2" cred="${3:-}" auth=""
  if [ -n "$cred" ]; then
    auth="Authorization: Basic $(printf '%s' "$cred" | base64 | tr -d '\n')\r\n"
  fi
  # Plain HTTP/1.0 over busybox nc: the full response (status line, headers, body) comes back.
  # The sleep keeps the request side open until the answer is in (an early close = a 499).
  { printf 'GET %s HTTP/1.0\r\nHost: localhost\r\n%b\r\n' "$path" "$auth"; sleep 1; } \
    | docker exec -i "$c" nc -w 3 127.0.0.1 8080 > "$HFILE" 2> /dev/null || true
  head -1 "$HFILE" | awk '{print $2}'
}
wait_for() { # container path expected-code [cred]
  local i code
  for i in $(seq 1 30); do
    code="$(status "$1" "$2" "${4:-}")"
    [ "$code" = "$3" ] && return 0
    sleep 1
  done
  fail "$1 $2 never answered $3 (last: ${code:-none})"
}
expect() { # container path code [cred] description
  local code
  code="$(status "$1" "$2" "${4:-}")"
  [ "$code" = "$3" ] || fail "$5: expected $3, got ${code:-none}"
  echo "caddy-check: ok - $5 ($3)"
}

# Mailpit is up (through the authenticated route) before anything else is judged.
wait_for "$ON" /mailpit/ 200 "qa:$PASSWORD"

expect "$ON" /mailpit/ 401 "" "no credentials"
headers | grep -qi 'www-authenticate: *basic' || fail "401 carries no Basic challenge"
expect "$ON" /mailpit/ 401 "qa:wrong-password" "wrong password"
expect "$ON" /mailpit/ 401 "other:$PASSWORD" "wrong user"
expect "$ON" /mailpit/api/v1/messages 401 "" "API without credentials"
expect "$ON" /mailpit/ 200 "qa:$PASSWORD" "right credentials reach Mailpit"
headers | grep -qi 'x-content-type-options: *nosniff' || fail "200 lacks nosniff"
headers | grep -qi 'referrer-policy:' || fail "200 lacks Referrer-Policy"
headers | grep -qi 'permissions-policy:' || fail "200 lacks Permissions-Policy"
headers | grep -qi 'content-security-policy:' || fail "200 lacks a CSP"
headers | grep -qi 'x-frame-options: *sameorigin' || fail "200 lacks X-Frame-Options SAMEORIGIN"
headers | grep -qi '^ *server:' && fail "Server header is not removed"
expect "$ON" /mailpit/api/v1/messages 200 "qa:$PASSWORD" "API with credentials"
expect "$ON" /mailpit 308 "qa:$PASSWORD" "bare /mailpit redirects to /mailpit/"
# The title proves it is the real Mailpit UI, not an error page.
status "$ON" /mailpit/ "qa:$PASSWORD" > /dev/null
headers | grep -qi '<title>Mailpit' || fail "Mailpit UI not served"
echo "caddy-check: ok - Mailpit UI served behind the route"

# Fail closed: no hash (unset, or "disabled" as compose passes it) means 404, even with credentials.
for c in "$EMPTY" "$UNSET"; do
  wait_for "$c" /mailpit/ 404 ""
  expect "$c" /mailpit/ 404 "" "$c: route off, no credentials"
  expect "$c" /mailpit/ 404 "qa:$PASSWORD" "$c: route off, any credentials"
  expect "$c" /mailpit/api/v1/messages 404 "qa:$PASSWORD" "$c: API off"
done

# The rest of the site is unchanged: no password prompt, base headers and the strict CSP stay.
expect "$ON" /some/page 200 "" "app routes serve index.html without a password"
headers | grep -qi 'x-frame-options: *deny' || fail "app routes lost X-Frame-Options DENY"
headers | grep -qi "frame-ancestors 'none'" || fail "app routes lost the strict CSP"
# Caching: the app shell must revalidate, hashed assets are immutable, and
# Caddy leaves /api/* to the api's own headers.
cache_control() {
  tr -d '\r' < "$HFILE" | awk -F': *' 'tolower($1)=="cache-control" {print $2}'
}
expect_cache() { # path expected-code expected-cache-control description
  local code cc
  code="$(status "$ON" "$1" "")"
  [ "$code" = "$2" ] || fail "$4: expected $2, got ${code:-none}"
  cc="$(cache_control)"
  [ "$cc" = "$3" ] || fail "$4: Cache-Control is '$cc', expected '$3'"
  echo "caddy-check: ok - $4 (Cache-Control: $cc)"
}
expect_cache / 200 "no-cache" "index.html"
expect_cache /agent/queue 200 "no-cache" "SPA route"
expect_cache /robots.txt 200 "no-cache" "root file"
expect_cache /assets/index-abc123.js 200 "public, max-age=31536000, immutable" "hashed asset"
expect_cache /assets/missing-zzz.js 200 "no-cache" "missing asset falls back to index.html, never immutable"
status "$ON" /api/v1/version > /dev/null
[ -z "$(cache_control)" ] || fail "Caddy set Cache-Control on /api/*"
echo "caddy-check: ok - /api/* gets no Cache-Control from Caddy"

code="$(status "$ON" /actuator/info)"
[ "$code" = "404" ] || fail "/actuator/* is not 404 (got $code)"
echo "caddy-check: all checks passed"
