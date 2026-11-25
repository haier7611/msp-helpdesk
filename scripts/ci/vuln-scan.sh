#!/usr/bin/env bash
# Dependency vulnerability scan (docs/35-security.md): pinned Trivy in filesystem mode over the
# repo - api/gradle.lockfile (Gradle dependency locking) and web/package-lock.json. Fails on
# HIGH or CRITICAL findings that have a fixed version (--ignore-unfixed). Used by CI
# (the `vuln-scan` job) and by hand:  scripts/ci/vuln-scan.sh
# Accepted exceptions go in .trivyignore (one CVE id per line, with a dated reason comment).
# The repo is mounted read-only; the vulnerability DB is cached in a ticketsys-only directory.
# This runs in parallel with the api and web stages, so the build output folders they
# are writing (web/node_modules, web/dist, web/coverage, api/build, api/.gradle) are skipped: they
# hold no lockfiles, and scanning files that appear and vanish mid-walk would only add noise.
set -euo pipefail

IMAGE="${TICKETSYS_TRIVY_IMAGE:-aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb}"
TAG="${TICKETSYS_CI_TAG:-manual-$(id -un)-$$}"
TAG="$(printf '%s' "$TAG" | tr -c 'A-Za-z0-9_.-' '-')"
NAME="ticketsys-ci-trivy-${TAG}"
# CI may set TICKETSYS_TRIVY_CACHE; otherwise (a laptop) a per-user cache dir.
TRIVY_CACHE="${TICKETSYS_TRIVY_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/ticketsys/trivy}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"

trap 'docker rm -f "$NAME" > /dev/null 2>&1 || true' EXIT
mkdir -p "$TRIVY_CACHE"
[ -f "$ROOT/api/gradle.lockfile" ] || { echo "vuln-scan: api/gradle.lockfile missing"; exit 1; }
[ -f "$ROOT/web/package-lock.json" ] || { echo "vuln-scan: web/package-lock.json missing"; exit 1; }
echo "vuln-scan: image $IMAGE, container $NAME"

docker run --rm --name "$NAME" \
  --user "$(id -u):$(id -g)" \
  --memory=1g --memory-swap=1g --cpus=1 \
  -v "$ROOT:/src:ro" \
  -v "$TRIVY_CACHE:/cache" \
  -e HOME=/tmp \
  "$IMAGE" fs \
  --cache-dir /cache \
  --scanners vuln \
  --pkg-types library \
  --severity HIGH,CRITICAL \
  --ignore-unfixed \
  --ignorefile /src/.trivyignore \
  --skip-dirs /src/web/node_modules \
  --skip-dirs /src/web/dist \
  --skip-dirs /src/web/coverage \
  --skip-dirs /src/api/build \
  --skip-dirs /src/api/.gradle \
  --exit-code 1 \
  --no-progress \
  /src
