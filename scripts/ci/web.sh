#!/usr/bin/env bash
# Web checks in a pinned Node 22 container capped at 2 GB / 2 CPUs (the web stage must
# not be able to take a shared machine's memory). Used by CI (the `web` job) and by hand:
#   scripts/ci/web.sh
# Runs: npm ci, lint, typecheck, format:check, test, build, then scripts/ci/caddy-check.sh (Caddyfile
# Mailpit route) and scripts/ci/mail-evidence-check.sh (QA email evidence pack). npm cache: ticketsys-only directory.
set -euo pipefail

IMAGE="${TICKETSYS_NODE_IMAGE:-node:22.23.5-alpine3.24@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a}"
TAG="${TICKETSYS_CI_TAG:-manual-$(id -un)-$$}"
TAG="$(printf '%s' "$TAG" | tr -c 'A-Za-z0-9_.-' '-')"
NAME="ticketsys-ci-web-${TAG}"
# CI may set TICKETSYS_NPM_CACHE; otherwise (a laptop) a per-user cache dir.
NPM_CACHE="${TICKETSYS_NPM_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/ticketsys/npm}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"

trap 'docker rm -f "$NAME" > /dev/null 2>&1 || true' EXIT
mkdir -p "$NPM_CACHE"
echo "web: image $IMAGE, container $NAME (2 GB, 2 CPUs)"

docker run --rm --name "$NAME" \
  --user "$(id -u):$(id -g)" \
  --memory=2g --memory-swap=2g --cpus=2 \
  -v "$ROOT/web:/work" -w /work \
  -v "$NPM_CACHE:/npm-cache" -e npm_config_cache=/npm-cache \
  -e HOME=/tmp -e CI=true \
  "$IMAGE" sh -euc '
    npm ci --no-audit --no-fund
    npm run lint
    npm run typecheck
    npm run format:check
    npm test
    npm run build
  '

# QA round (ADR-030): the Caddyfile's basic-auth Mailpit route against the pinned Caddy and Mailpit
# images (private network, no host port). TICKETSYS_CI_TAG is passed on so its containers carry it.
TICKETSYS_CI_TAG="$TAG" "$ROOT/scripts/ci/caddy-check.sh"

# QA round (ADR-030): the email evidence pack host command against the pinned Mailpit, python and
# headless Chromium images (no host port; the render container has no network).
TICKETSYS_CI_TAG="$TAG" "$ROOT/scripts/ci/mail-evidence-check.sh"
