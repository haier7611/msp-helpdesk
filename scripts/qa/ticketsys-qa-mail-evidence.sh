#!/usr/bin/env bash
# QA round email evidence pack (ADR-030). Installed on the host as
# /usr/local/sbin/ticketsys-qa-mail-evidence (with its collect step at
# /usr/local/lib/ticketsys/qa-mail-evidence.py) by your deploy process. Run as root on the
# host shell after the round:
#   sudo ticketsys-qa-mail-evidence [--since <ISO time>] [--all]
# On the demo, Mailpit is the clients' mailbox. This makes one PNG per client-facing email (what the
# client saw when a supporter replied in the portal) plus index.html and index.csv, in
#   /opt/ticket-system/qa-evidence/<UTC timestamp>/   (root, dir 700, files 600)
#
#   default   client-facing ticket mail: subject starts with a ticket reference like [ACD-001],
#             agent-only "(Internal)" mail left out
#   --all     every email
#   --since   only emails sent at or after this time (e.g. 2026-11-19T09:00:00Z; no zone = UTC)
# Sign-in emails (invitations, password reset, magic links, first-admin set-up, two-step sign-in
# notices, anything holding a sign-in token link) are ALWAYS left out; there is no flag for them.
#
# How: (1) a pinned python container shares the Mailpit container's network namespace and reads
# Mailpit's REST API on 127.0.0.1 (GET only, raw source - never opens, marks or deletes mail; no
# public URL, no basic-auth password); it writes one HTML page per email with inline cid: images
# turned into data: URIs. (2) a pinned headless Chromium container with --network none renders each
# page to an 800 px wide, full-height PNG, driven over the DevTools protocol on its loopback by the
# same python image joined to its network namespace. Temporary files sit in a mode-700 work dir that is
# removed on exit or failure. Prints counts and the output path only.
#
# Test hooks (scripts/ci/mail-evidence-check.sh): TS_BASE, TS_QA_MAILPIT_CONTAINER, TS_QA_MAIL_LIB,
# TS_QA_RUN_TAG (container name suffix), TS_QA_NO_ROOT_CHECK=1.
set -euo pipefail
umask 077

PYTHON_IMAGE="python:3.13.18-alpine3.24@sha256:d76f585fb0d5107dd47143da5e61554fd9cae656a30f3470f815cb8475769b6a"
CHROME_IMAGE="chromedp/headless-shell:158.0.8127.37@sha256:ad40f1c6d6b60e149e7ba41af20fbe3a1e4f11f490d5180009d9211c63bd9bcd"

BASE="${TS_BASE:-/opt/ticket-system}"
MAILPIT="${TS_QA_MAILPIT_CONTAINER:-ticketsys-mailpit}"
LIB="${TS_QA_MAIL_LIB:-/usr/local/lib/ticketsys/qa-mail-evidence.py}"
RUN_TAG="${TS_QA_RUN_TAG:-$$-$(date +%s)}"
RUN_TAG="$(printf '%s' "$RUN_TAG" | tr -c 'A-Za-z0-9_.-' '-')"
COLLECT="ticketsys-qa-mailev-collect-${RUN_TAG}"
RENDER="ticketsys-qa-mailev-render-${RUN_TAG}"
PAINT="ticketsys-qa-mailev-paint-${RUN_TAG}"
OUTROOT="$BASE/qa-evidence"

usage() {
  echo "usage: ticketsys-qa-mail-evidence [--since <ISO time>] [--all]"
  exit 64
}

[ "${TS_QA_NO_ROOT_CHECK:-0}" = 1 ] || [ "$(id -u)" -eq 0 ] || {
  echo "qa-mail-evidence: run as root on the host (sudo ticketsys-qa-mail-evidence)"
  exit 1
}

EXTRA=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --all) EXTRA+=(--all) ;;
    --since)
      [ "$#" -ge 2 ] || usage
      # Digits, dashes, colons, dots, T, Z and a +hh:mm offset only.
      [[ "$2" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}([T\ ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]{1,6})?)?)?(Z|[+-][0-9]{2}:[0-9]{2})?$ ]] \
        || { echo "qa-mail-evidence: --since must be an ISO time such as 2026-11-19T09:00:00Z"; exit 64; }
      EXTRA+=(--since "$2")
      shift
      ;;
    *) usage ;;
  esac
  shift
done

[ -f "$LIB" ] || { echo "qa-mail-evidence: $LIB not found - deploy the current release first"; exit 1; }
[ "$(docker inspect -f '{{.State.Running}}' "$MAILPIT" 2> /dev/null || true)" = true ] \
  || { echo "qa-mail-evidence: container $MAILPIT is not running (demo profile only)"; exit 1; }
# Mailpit serves its API under its webroot (MP_WEBROOT=/mailpit on the demo).
WEBROOT="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$MAILPIT" \
  | sed -n 's/^MP_WEBROOT=//p' | head -1)"
WEBROOT="/${WEBROOT#/}"
WEBROOT="${WEBROOT%/}"
[[ "$WEBROOT" =~ ^[A-Za-z0-9/_-]*$ ]] || { echo "qa-mail-evidence: unexpected MP_WEBROOT"; exit 1; }
API="http://127.0.0.1:8025${WEBROOT}/api/v1"

install -d -m 700 "$OUTROOT"
WORK="$(mktemp -d "$OUTROOT/.work.XXXXXX")"
cleanup() {
  docker rm -f "$COLLECT" "$PAINT" "$RENDER" > /dev/null 2>&1 || true
  rm -rf -- "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
mkdir -m 700 "$WORK/html" "$WORK/png"
cp "$LIB" "$WORK/collect.py"
chmod 600 "$WORK/collect.py"
USER_ARGS=(--user "$(id -u):$(id -g)")
HARDEN=(--read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 256)

# (1) Collect: Mailpit's network namespace only, so 127.0.0.1:8025 is Mailpit's own API.
if ! docker run --rm --name "$COLLECT" --platform linux/amd64 --network "container:$MAILPIT" \
  "${USER_ARGS[@]}" "${HARDEN[@]}" --memory 512m --memory-swap 512m --cpus 1 \
  -e PYTHONDONTWRITEBYTECODE=1 -v "$WORK:/work" \
  "$PYTHON_IMAGE" python -I /work/collect.py collect --api "$API" --out /work \
  "${EXTRA[@]+"${EXTRA[@]}"}" > "$WORK/summary.txt" 2> "$WORK/collect.err"; then
  echo "qa-mail-evidence: collect step failed"
  # Error text can quote a URL or a Python trace, never message content; keep it short.
  tail -3 "$WORK/collect.err" | cut -c1-200
  exit 1
fi

# (2) Render: headless Chromium in a container with --network none (only a loopback interface).
# The python renderer joins that same network namespace and drives Chromium over the DevTools
# protocol on 127.0.0.1: per page it measures the full height, then saves an 800 px wide PNG.
if [ -s "$WORK/plan.tsv" ]; then
  # The pages only, read-only, to an unprivileged user (nobody): the work dir and html/ are made
  # traversable/readable for it here, while qa-evidence/ above stays root-only (700), so no other
  # host user can reach them. --no-sandbox stays: Chromium's own sandbox needs user namespaces or
  # extra capabilities that this container deliberately does not get. What contains a hostile email
  # instead: scripts off (sandboxed frame without allow-scripts, CSP without script-src, and script
  # execution disabled over DevTools), no network at all, read-only root, all capabilities dropped,
  # no-new-privileges, a throwaway container removed after the run.
  chmod 711 "$WORK"
  chmod 755 "$WORK/html"
  find "$WORK/html" -type f -exec chmod 644 {} +
  docker run -d --name "$RENDER" --platform linux/amd64 --network none \
    --user 65534:65534 "${HARDEN[@]}" --memory 1g --memory-swap 1g --cpus 2 \
    --tmpfs /tmp:rw,size=512m -e HOME=/tmp -v "$WORK/html:/work/html:ro" \
    --entrypoint /headless-shell/headless-shell "$CHROME_IMAGE" \
    --no-sandbox --use-gl=angle --use-angle=swiftshader --no-first-run --disable-extensions \
    --disable-sync --disable-background-networking --disable-dev-shm-usage --hide-scrollbars \
    --host-resolver-rules="MAP * ~NOTFOUND" --user-data-dir=/tmp/profile \
    --remote-debugging-address=127.0.0.1 --remote-debugging-port=9222 about:blank > /dev/null
  if ! docker run --rm --name "$PAINT" --platform linux/amd64 --network "container:$RENDER" \
    "${USER_ARGS[@]}" "${HARDEN[@]}" --memory 512m --memory-swap 512m --cpus 1 \
    -e PYTHONDONTWRITEBYTECODE=1 -v "$WORK:/work" \
    "$PYTHON_IMAGE" python -I /work/collect.py render --cdp 127.0.0.1:9222 --work /work \
    > /dev/null 2> "$WORK/render.err"; then
    echo "qa-mail-evidence: render step failed"
    tail -3 "$WORK/render.err" | cut -c1-200
    exit 1
  fi
  docker rm -f "$RENDER" > /dev/null 2>&1 || true
  while IFS="	" read -r _ png; do
    [ -s "$WORK/png/$png" ] || { echo "qa-mail-evidence: no image produced for one email"; exit 1; }
  done < "$WORK/plan.tsv"
fi

# Build the finished pack inside the work dir, then move it into place with one rename (same
# filesystem): a failure before this point leaves no partial pack, only the work dir the trap
# removes.
PACK="$WORK/pack"
mkdir -m 700 "$PACK"
find "$WORK/png" -maxdepth 1 -name '*.png' -exec mv {} "$PACK"/ \;
mv "$WORK/index.html" "$WORK/index.csv" "$PACK"/
find "$PACK" -type f -exec chmod 600 {} +
if [ "$(id -u)" -eq 0 ]; then
  chown -R root:root "$PACK"
fi
while :; do
  OUT="$OUTROOT/$(date -u +%Y%m%dT%H%M%SZ)"
  if [ ! -e "$OUT" ] && mv -T "$PACK" "$OUT" 2> /dev/null; then
    break
  fi
  sleep 1
done

sed 's/^/qa-mail-evidence: /' "$WORK/summary.txt"
echo "qa-mail-evidence: images written: $(find "$OUT" -name '*.png' | wc -l | tr -d ' ')"
echo "qa-mail-evidence: pack in $OUT (root only)"
echo "qa-mail-evidence: copy it off the host with, for example:"
echo "  ssh <host> 'sudo tar -C $OUTROOT -cf - $(basename "$OUT")' | tar -C ~/Downloads -xf -"
