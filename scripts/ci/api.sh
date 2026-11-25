#!/usr/bin/env bash
# Builds and tests api/ inside a pinned eclipse-temurin:21-jdk container (no JDK on the host).
# Used by CI (the `api` job) and by hand on any Linux host with Docker:
#  scripts/ci/api.sh [gradle tasks...]
# The repo's scripts/ folder is mounted read-only at /scripts (= ../scripts from /work), so the
# backup tests can run the backup scripts (restore drill, export) against the test Postgres.
# Default tasks: spotlessCheck build (build = compile + unit + Testcontainers integration tests).
#
# Rules for a CI machine shared with other projects: only ticketsys- names, host ports only in
# 55500-55599, nothing system wide. The build container and the Testcontainers Postgres get exact ticketsys- names and are
# removed by those names (here on exit, and again by any CI clean-up step). Ryuk is off
# because its container could not carry a ticketsys- name.
set -euo pipefail

IMAGE="${TICKETSYS_JDK_IMAGE:-eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1}"
TAG="${TICKETSYS_CI_TAG:-manual-$(id -un)-$$}"
TAG="$(printf '%s' "$TAG" | tr -c 'A-Za-z0-9_.-' '-')"
BUILD_NAME="ticketsys-ci-api-${TAG}"
PG_NAME="ticketsys-ci-pg-${TAG}"
# CI may set TICKETSYS_GRADLE_CACHE; otherwise (a laptop) a per-user cache dir.
GRADLE_CACHE="${TICKETSYS_GRADLE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/ticketsys/gradle}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"

if [ "$#" -eq 0 ]; then
  set -- spotlessCheck build
fi

for tool in docker ss flock stat; do
  command -v "$tool" > /dev/null 2>&1 || { echo "api: '$tool' not found - this script needs a Linux host with Docker"; exit 1; }
done

cleanup() {
  docker rm -f "$BUILD_NAME" "$PG_NAME" > /dev/null 2>&1 || true
}
trap cleanup EXIT

# Free ports in the project's range (bound to 127.0.0.1 only): one for the Testcontainers Postgres
# and EXTRA_COUNT for in-process test servers (read from TICKETSYS_IT_EXTRA_PORTS by
# api/src/test/java/com/ticketsystem/api/mail/TestPorts.java). The test JVM asks TestPorts for 8
# ports today: GreenMail IMAP (EmailInOut), IMAP + SMTP (EmailFollowUps), SMTP (MailOutbox), the
# rejecting SMTP server (MailOutbox), the closed port (SmtpMailTransport), the OAuth token server
# (EmailInOut) and the fake Microsoft identity platform (FakeEntra). Reserving only 4 sent the rest
# to TestPorts' unreserved bind probe, which can take a port a parallel build has reserved but not
# yet bound (GreenMail IllegalStateException in EmailInOut under parallel PR builds). 8 + a margin
# of 4 covers the consumers and TestGreenMail's retries; raise it when a new TestPorts.free()
# caller is added.
# Parallel builds used to pick the same first free port before Postgres had bound it:
# each port is now reserved with a per-port flock held until this script exits, and the
# search starts at a per-build offset.
EXTRA_COUNT=16
TOTAL_PORTS=$((EXTRA_COUNT + 1))
LOCK_DIR="${TMPDIR:-/tmp}/ticketsys-ci-ports"
mkdir -p "$LOCK_DIR"
PORTS=()
fd=200
start=$((RANDOM % 100))
for i in $(seq 0 99); do
  p=$((55500 + (start + i) % 100))
  ss -ltnH "sport = :$p" | grep -q . && continue
  eval "exec $fd>\"$LOCK_DIR/port-$p.lock\""
  if flock -n "$fd"; then
    PORTS+=("$p")
    fd=$((fd + 1))
    [ "${#PORTS[@]}" -eq "$TOTAL_PORTS" ] && break
  else
    eval "exec $fd>&-"
  fi
done
[ "${#PORTS[@]}" -eq "$TOTAL_PORTS" ] || { echo "api: not enough free ports in 55500-55599"; exit 1; }
PORT="${PORTS[0]}"
EXTRA_PORTS="$(IFS=,; echo "${PORTS[*]:1}")"

mkdir -p "$GRADLE_CACHE"
DOCKER_GID="$(stat -c %g /var/run/docker.sock)"
echo "api: image $IMAGE, container $BUILD_NAME, test db $PG_NAME on 127.0.0.1:$PORT, test servers on $EXTRA_PORTS"
echo "api: gradle $*"

docker run --rm --name "$BUILD_NAME" \
  --user "$(id -u):$(id -g)" --group-add "$DOCKER_GID" \
  --memory=3g --memory-swap=3g --cpus=2 \
  --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$ROOT/api:/work" -w /work \
  -v "$ROOT/scripts:/scripts:ro" \
  -v "$GRADLE_CACHE:/gradle-home" -e GRADLE_USER_HOME=/gradle-home \
  -e HOME=/tmp \
  -e TICKETSYS_IT_CONTAINER_NAME="$PG_NAME" \
  -e TICKETSYS_IT_PORT="$PORT" \
  -e TICKETSYS_IT_EXTRA_PORTS="$EXTRA_PORTS" \
  -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  "$IMAGE" ./gradlew --no-daemon --console=plain "$@"
