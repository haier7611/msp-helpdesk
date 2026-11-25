#!/usr/bin/env bash
# Real-stack check of the QA email evidence pack (ADR-030), run by scripts/ci/web.sh and by hand:
#   scripts/ci/mail-evidence-check.sh
# Starts the pinned Mailpit image from docker-compose.yml (MP_WEBROOT=/mailpit, no host port),
# sends it synthetic mail over SMTP from inside its network namespace, runs the real host command
# scripts/qa/ticketsys-qa-mail-evidence.sh against it (pinned python + headless Chromium images)
# and proves:
#   - drift: every sign-in template in the api's SystemEmail (token-bearing, first-admin set-up,
#     two-step sign-in) matches SIGN_IN_SUBJECTS, no other template does, and every token link path
#     the api builds (props.link) matches SIGN_IN_TEXT;
#   - default run: one PNG per client-facing ticket email, 800 px wide, non-trivial, full height
#     (long plain-text reply), the cid: logo really rendered (red pixels), "(Internal)" mail out;
#   - hostile HTML (<script>, onerror, remote <img>/<link>/@import/background, <iframe src>,
#     <meta refresh>, an inline image over the 2 MB cap): the render finishes, the script did not
#     run (no green pixels), nothing reached a canary HTTP server that a networked container CAN
#     reach (positive control), the oversized image is noted in the index;
#   - index.html escapes a hostile subject and from-name; index.csv neutralises = + - @ cells;
#   - every sign-in email (magic link, reset, both invitations, first-admin, MFA set up/reset,
#     verification code, a token link under a ticket subject) is left out, also with --all;
#   - dir 700, files 600; no work dir and no partial pack left; stdout holds no address;
#   - Mailpit is untouched: same message count, nothing marked read;
#   - --since in the future selects nothing; bad arguments exit 64.
# Synthetic data only. Removes only its own containers, network and temp files.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"
TAG="${TICKETSYS_CI_TAG:-manual-$(id -un)-$$}"
TAG="$(printf '%s' "$TAG" | tr -c 'A-Za-z0-9_.-' '-')"
MP="ticketsys-ci-mailev-mailpit-${TAG}"
HELPER="ticketsys-ci-mailev-helper-${TAG}"
CANARY="ticketsys-ci-mailev-canary-${TAG}"
NET="ticketsys-ci-mailev-net-${TAG}"
MAILPIT_IMAGE="$(sed -n 's/^ *image: \(axllent\/mailpit:[^ ]*\)$/\1/p' "$ROOT/docker-compose.yml")"
CMD="$ROOT/scripts/qa/ticketsys-qa-mail-evidence.sh"
PYTHON_IMAGE="$(sed -n 's/^PYTHON_IMAGE="\(.*\)"$/\1/p' "$CMD")"
[ -n "$MAILPIT_IMAGE" ] && [ -n "$PYTHON_IMAGE" ] || { echo "mail-evidence-check: could not read the pinned images"; exit 1; }
LIMITS=(--memory 256m --memory-swap 256m --cpus 1 --pids-limit 128)

TMP="$(mktemp -d)"
cleanup() {
  local t
  docker rm -f "$MP" "$HELPER" "$CANARY" > /dev/null 2>&1 || true
  docker ps -aq --filter "name=${HELPER}-ctl-" | xargs -r docker rm -f > /dev/null 2>&1 || true
  for t in a b c; do
    docker rm -f "ticketsys-qa-mailev-collect-${TAG}-$t" "ticketsys-qa-mailev-paint-${TAG}-$t" \
      "ticketsys-qa-mailev-render-${TAG}-$t" > /dev/null 2>&1 || true
  done
  docker network rm "$NET" > /dev/null 2>&1 || true
  rm -rf -- "$TMP"
}
trap cleanup EXIT
fail() { echo "mail-evidence-check: FAIL - $1"; exit 1; }
ok() { echo "mail-evidence-check: ok - $1"; }

# pyrun <docker args...> -- <python args...>: the pinned python image, read-only, capped.
pyrun() {
  local args=()
  while [ "$1" != "--" ]; do
    args+=("$1")
    shift
  done
  shift
  docker run --rm --user "$(id -u):$(id -g)" --read-only --cap-drop ALL "${LIMITS[@]}" \
    -e PYTHONDONTWRITEBYTECODE=1 "${args[@]}" "$PYTHON_IMAGE" python -I "$@"
}

cat > "$TMP/drift.py" << 'EOF'
import glob, importlib.util, re, sys
spec = importlib.util.spec_from_file_location("ev", "/src/scripts/qa/qa-mail-evidence.py")
ev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ev)
src = open("/src/api/src/main/java/com/ticketsystem/api/mail/SystemEmail.java").read()
entries = re.findall(r'\b([A-Z_]+)\(\s*"([a-z-]+)",\s*"([^"]*)",\s*(true|false),', src)
assert len(entries) >= 15, f"SystemEmail parse found only {len(entries)} templates"
# Sign-in templates that carry no token but still belong out of the pack.
SIGN_IN_NAMES = {"FIRST_ADMIN_SETUP", "MFA_ENROLLED", "MFA_RESET", "MFA_EXEMPTION_GRANTED"}
problems = []
for name, _, fmt, sensitive in entries:
    subject = fmt.replace("%s", "Ticket System")
    matched = any(p.search(subject) for p in ev.SIGN_IN_SUBJECTS)
    must = sensitive == "true" or name in SIGN_IN_NAMES
    if must and not matched:
        problems.append(f"{name} ('{subject}') is a sign-in email but SIGN_IN_SUBJECTS misses it")
    if not must and matched:
        problems.append(f"{name} ('{subject}') matches SIGN_IN_SUBJECTS but is not a sign-in email")
paths = set()
for f in glob.glob("/src/api/src/main/java/**/*.java", recursive=True):
    paths.update(re.findall(r'\.link\(\s*"(/[A-Za-z0-9/_-]+)"', open(f).read()))
assert paths, "no props.link(...) token paths found in the api"
for path in sorted(paths):
    if not ev.SIGN_IN_TEXT.search("https://x.example" + path):
        problems.append(f"token link path {path} is not in SIGN_IN_TEXT")
if problems:
    print("\n".join(problems))
    sys.exit(1)
print(f"ok {len(entries)} templates, {len(paths)} token paths")
EOF

cat > "$TMP/send.py" << 'EOF'
import os, smtplib, struct, sys, time, zlib
from email.message import EmailMessage
from email.utils import formataddr, formatdate

CANARY = f"http://{sys.argv[1]}:8000"

def red_png(size=48):
    raw = b"".join(b"\x00" + b"\xff\x00\x00" * size for _ in range(size))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

now = time.time()
SUPPORT = "support@helpdesk.example"
def msg(subject, to, frm_name, when, text, html=None, related=()):
    m = EmailMessage()
    m["Subject"], m["To"] = subject, to
    m["From"] = formataddr((frm_name, SUPPORT))
    m["Date"] = formatdate(now - when, usegmt=True)
    m.set_content(text)
    if html:
        m.add_alternative(html, subtype="html")
        for data, cid in related:
            m.get_payload()[1].add_related(data, "image", "png", cid=f"<{cid}>", filename=f"{cid}.png")
    return m

reply_html = ("<!doctype html><html><body style='margin:0'><table width='600'><tr><td style='padding:24px;"
              "font-family:Arial'><p>Hello Dana,</p><p>We have restarted the print spooler. Please try again."
              "</p><p>Alex Example<br><img src=\"cid:signature-logo\" width=\"48\" height=\"48\" alt=\"logo\">"
              "</p></td></tr></table></body></html>")
GREEN = "<div style=\\'background:#00ff00;width:800px;height:900px\\'></div>"
hostile_html = (
    f"<html><head><meta http-equiv=\"refresh\" content=\"0;url={CANARY}/refresh\">"
    f"<style>@import url(\"{CANARY}/import.css\"); body {{ background: url(\"{CANARY}/bg.png\"); }}</style>"
    f"<link rel=\"stylesheet\" href=\"{CANARY}/link.css\"></head><body>"
    "<p style='font:16px Arial;padding:24px'>Hostile content test message.</p>"
    f"<img src=\"{CANARY}/pixel.png\" width=\"10\" height=\"10\"><iframe src=\"{CANARY}/frame\"></iframe>"
    "<img src=\"cid:big-part\" width=\"10\" height=\"10\">"
    f"<script>document.body.innerHTML = '{GREEN}'; fetch('{CANARY}/script');</script>"
    "<img src=\"x\" onerror=\"document.body.style.background='#00ff00'\">"
    "</body></html>")
mails = [
    # Default pack (5).
    msg("[ACD-001] Printer offline on level 2", "Dana Reyes <dana@acmedental.example>",
        "Alex Example", 600, "Hello Dana, we have restarted the print spooler.", reply_html,
        related=[(red_png(), "signature-logo")]),
    msg("[NWL-002] VPN drops every hour", "Priya Nair <priya@northwind.example>", "Alex Example", 590,
        "Hello Priya,\n\nPlease update the VPN client to the latest version. The log shows:\n\n"
        + "".join(f"10:{i:02d}:00 tunnel keepalive timeout, reconnecting\n" for i in range(60))
        + "\nAlex"),
    msg("[ACD-004] Hostile content test", "dana@acmedental.example", "Alex Example", 580,
        "Hostile content test (text part).", hostile_html,
        related=[(os.urandom(3 * 1024 * 1024), "big-part")]),
    msg("[ACD-005] <img src=x onerror=alert(1)> \"><script>alert(2)</script>",
        "dana@acmedental.example", "\"><script>alert(3)</script>", 570, "Escaping test."),
    msg("[NWL-006] Formula from-name test", "priya@northwind.example", "=CMD()", 560, "CSV test."),
    # --all only (5).
    msg("[ACD-001] (Internal) Client replied: Printer offline on level 2", "alex@msp.example",
        "Ticket System", 550, "Dana replied on ACD-001."),
    msg("=1+2", "dana@acmedental.example", "Ticket System", 540, "Formula subject."),
    msg("+SUM(1)", "dana@acmedental.example", "Ticket System", 530, "Formula subject."),
    msg("-2+3", "dana@acmedental.example", "Ticket System", 520, "Formula subject."),
    msg("@SUM(1)", "dana@acmedental.example", "Ticket System", 510, "Formula subject."),
    # Sign-in mail, never in a pack (9).
    msg("Your Ticket System sign-in link", "dana@acmedental.example", "Ticket System", 500,
        "Sign in: https://support.example/magic-link#token=synthetic-not-a-token",
        "<p><a href='https://support.example/magic-link#token=synthetic-not-a-token'>Sign in</a></p>"),
    msg("Reset your Ticket System password", "priya@northwind.example", "Ticket System", 490,
        "Reset: https://support.example/reset-password#token=synthetic-not-a-token"),
    msg("[ACD-003] Re: account access", "sam@acmedental.example", "Ticket System", 480,
        "Use https://support.example/accept-invitation#token=synthetic-not-a-token"),
    msg("You have been invited to Ticket System", "riley@msp.example", "Ticket System", 470,
        "Accept: https://support.example/accept-invitation#token=synthetic-not-a-token"),
    msg("Your Ticket System account is ready to set up", "lena@bluefin.example", "Ticket System", 460,
        "Set up your account (synthetic)."),
    msg("Your Ticket System administrator account is ready", "admin@msp.example", "Ticket System",
        450, "Sign in at https://support.example/sign-in"),
    msg("Two-step sign-in was set up on your Ticket System account", "alex@msp.example",
        "Ticket System", 440, "Synthetic MFA notice."),
    msg("Two-step sign-in was reset on your Ticket System account", "alex@msp.example",
        "Ticket System", 430, "Synthetic MFA notice."),
    msg("Your Ticket System verification code", "dana@acmedental.example", "Ticket System", 420,
        "Your code is 000000 (synthetic)."),
]
for i in range(30):
    try:
        with smtplib.SMTP("127.0.0.1", 1025, timeout=30) as s:
            for m in mails:
                s.send_message(m)
        break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit("SMTP not reachable")
print(len(mails))
EOF

cat > "$TMP/state.py" << 'EOF'
import json, sys, urllib.request
d = json.load(urllib.request.urlopen("http://127.0.0.1:8025/mailpit/api/v1/messages?limit=200"))
print(d["total"], sum(1 for m in d["messages"] if m.get("Read")))
EOF

cat > "$TMP/get.py" << 'EOF'
import sys, urllib.request
print(urllib.request.urlopen(sys.argv[1], timeout=10).status)
EOF

cat > "$TMP/verify.py" << 'EOF'
import struct, sys, zlib
def png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, ihdr = 8, b"", None
    while pos < len(data):
        n, t = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if t == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif t == b"IDAT":
            idat += body
        pos += 12 + n
    return len(data), ihdr, zlib.decompress(idat)
def pixels(ihdr, raw):
    w, h, depth, ctype, _, _, interlace = ihdr
    assert depth == 8 and interlace == 0 and ctype in (2, 6), f"unexpected PNG format {ihdr}"
    bpp = 4 if ctype == 6 else 3
    stride, prev, pos = w * bpp, bytearray(w * bpp), 0
    for _ in range(h):
        f, line = raw[pos], bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        for i in range(0, stride, bpp):
            yield line[i], line[i + 1], line[i + 2]
        prev = line
# argv: <check>:<path> ...   checks: basic, logo (red square), tall (> 1200 px), nogreen
for arg in sys.argv[1:]:
    check, path = arg.split(":", 1)
    size, ihdr, raw = png(path)
    assert ihdr[0] == 800, f"{path}: width {ihdr[0]}"
    assert ihdr[1] >= 200, f"{path}: height {ihdr[1]}"
    assert size > 5000, f"{path}: only {size} bytes"
    if check == "tall":
        assert ihdr[1] > 1200, f"{path}: not full height ({ihdr[1]} px)"
    if check == "logo":
        n = sum(1 for r, g, b in pixels(ihdr, raw) if r > 200 and g < 60 and b < 60)
        assert n >= 48 * 48 * 0.8, f"{path}: logo not rendered ({n} red pixels)"
    if check == "nogreen":
        n = sum(1 for r, g, b in pixels(ihdr, raw) if g > 200 and r < 60 and b < 60)
        assert n == 0, f"{path}: the email's script ran ({n} green pixels)"
print("ok")
EOF
chmod 644 "$TMP"/*.py
chmod 755 "$TMP"

# Drift between the api's sign-in templates and the exclusion lists.
drift="$(pyrun --network none -v "$TMP:/t:ro" -v "$ROOT:/src:ro" -- /t/drift.py)" || { echo "$drift"; fail "sign-in exclusion list drifted from the api"; }
ok "drift: $drift"

echo "mail-evidence-check: mailpit $MAILPIT_IMAGE"
docker network create "$NET" > /dev/null
docker run -d --name "$MP" --memory=256m -e MP_WEBROOT=/mailpit -e MP_SMTP_DISABLE_RDNS=true \
  "$MAILPIT_IMAGE" > /dev/null
# Canary web server on its own network; the hostile email points every remote URL at it.
docker run -d --name "$CANARY" --network "$NET" --user "$(id -u):$(id -g)" --read-only \
  --cap-drop ALL "${LIMITS[@]}" -w /tmp --tmpfs /tmp "$PYTHON_IMAGE" \
  python -I -u -m http.server 8000 > /dev/null
CANARY_IP="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$CANARY")"
[ -n "$CANARY_IP" ] || fail "no canary IP"
# Positive control: a container that has a network does reach the canary.
# Each attempt gets its own container name: under load Docker may still be removing the previous
# --rm helper, and reusing one name made every retry fail at once ("name already in use") before
# the canary had started (flaky under parallel CI load). Up to about 60 s.
control_err=""
for i in $(seq 1 30); do
  if control_err="$(pyrun --name "${HELPER}-ctl-$i" --network "$NET" -v "$TMP:/t:ro" -- /t/get.py \
    "http://$CANARY_IP:8000/control" 2>&1 > /dev/null)"; then
    break
  fi
  sleep 2
done
# Read the log into a file first: `docker logs | grep -q` under pipefail fails at random when grep
# exits on the first match and docker logs gets SIGPIPE (the real cause of earlier flakes -
# the canary log showed "GET /control" while the check said it was missing).
docker logs "$CANARY" > "$TMP/canary-control.log" 2>&1 || true
if ! grep -q '"GET /control' "$TMP/canary-control.log"; then
  echo "mail-evidence-check: last control error: ${control_err:-none}" | tail -c 600
  echo "mail-evidence-check: canary log:"; tail -n 5 "$TMP/canary-control.log"
  fail "canary positive control failed"
fi
ok "canary reachable from a networked container"

helper() { pyrun --name "$HELPER" --network "container:$MP" -v "$TMP:/t:ro" -- "$@"; }
sent="$(helper /t/send.py "$CANARY_IP")"
[ "$sent" = 19 ] || fail "could not send the synthetic mail"
for i in $(seq 1 30); do
  [ "$(helper /t/state.py)" = "19 0" ] && break
  sleep 1
done
[ "$(helper /t/state.py)" = "19 0" ] || fail "Mailpit did not receive 19 unread messages"
ok "19 synthetic messages in Mailpit"

# run <tag> [args]: the real host command against the test Mailpit, output under $TMP/base.
run() {
  local t="$1"
  shift
  TS_BASE="$TMP/base" TS_QA_MAILPIT_CONTAINER="$MP" TS_QA_NO_ROOT_CHECK=1 \
    TS_QA_MAIL_LIB="$ROOT/scripts/qa/qa-mail-evidence.py" TS_QA_RUN_TAG="${TAG}-$t" \
    "$CMD" "$@"
}
newest() { ls -1d "$TMP"/base/qa-evidence/2* | sort | tail -1; }
csv_rows() { tail -n +2 "$1/index.csv" | wc -l | tr -d ' '; }
png_of() { ls "$1"/"$2"-[0-9][0-9][0-9][0-9][0-9][0-9].png 2> /dev/null | head -1; }

# Bad arguments never start anything.
code=0
run x --bogus > /dev/null 2>&1 || code=$?
[ "$code" = 64 ] || fail "bad argument exit code $code"
code=0
run x --since 'yesterday; rm -rf /' > /dev/null 2>&1 || code=$?
[ "$code" = 64 ] || fail "bad --since exit code $code"
ok "bad arguments exit 64"

# Default run.
out="$(run a)" || { echo "$out"; fail "default run failed"; }
echo "$out" | sed 's/^/  | /'
echo "$out" | grep -q '@' && fail "stdout holds an address"
D="$(newest)"
echo "$out" | grep -q "pack in $D" || fail "stdout lacks the output path"
echo "$out" | grep -q "sudo tar -C" || fail "stdout lacks the copy command"
echo "$out" | grep -q "skipped sign-in emails: 9" || fail "expected 9 sign-in emails skipped"
[ "$(stat -c %a "$D")" = 700 ] || fail "pack dir is not 700"
[ -z "$(find "$D" -type f ! -perm 600)" ] || fail "a pack file is not 600"
[ -z "$(find "$TMP/base/qa-evidence" -mindepth 1 -maxdepth 1 -name '.*')" ] || fail "work dir left behind"
[ "$(csv_rows "$D")" = 5 ] || fail "default index.csv should have 5 rows, has $(csv_rows "$D")"
[ "$(find "$D" -name '*.png' | wc -l | tr -d ' ')" = 5 ] || fail "default run should make 5 images"
LOGO="$(png_of "$D" ACD-001-1)"
TEXT="$(png_of "$D" NWL-002-1)"
HOSTILE="$(png_of "$D" ACD-004-1)"
ESC="$(png_of "$D" ACD-005-1)"
FROM="$(png_of "$D" NWL-006-1)"
[ -n "$LOGO" ] && [ -n "$TEXT" ] && [ -n "$HOSTILE" ] && [ -n "$ESC" ] && [ -n "$FROM" ] || fail "an expected image is missing"
grep -q '^ACD-001,\[ACD-001\] Printer offline on level 2,Alex Example,Dana Reyes <dana@acmedental.example>,.*,ACD-001-1-.*\.png,1,0$' "$D/index.csv" \
  || fail "ACD-001 index row wrong (1 inline image expected)"
grep -q '^NWL-002,\[NWL-002\] VPN drops every hour,Alex Example,.*,NWL-002-1-.*\.png,0,0$' "$D/index.csv" \
  || fail "NWL-002 index row wrong"
grep -q '^ACD-004,.*,ACD-004-1-.*\.png,0,1$' "$D/index.csv" || fail "oversized inline image not counted"
grep -q '1 inline image(s) over 2 MB not embedded' "$D/index.html" || fail "index.html lacks the oversized-image note"
grep -q "^NWL-006,\[NWL-006\] Formula from-name test,'=CMD()," "$D/index.csv" || fail "formula from-name not neutralised"
grep -qi 'sign-in link\|Reset your\|ACD-003\|Internal\|invited\|ready to set up\|administrator account\|Two-step\|verification code' \
  "$D/index.csv" "$D/index.html" && fail "excluded mail listed in the default pack"
grep -q '<script\|<img src=x\|<img src="x' "$D/index.html" && fail "index.html carries unescaped markup"
grep -q '&lt;script&gt;alert(2)&lt;/script&gt;' "$D/index.html" || fail "subject not escaped in index.html"
grep -q '&lt;script&gt;alert(3)&lt;/script&gt;' "$D/index.html" || fail "from-name not escaped in index.html"
grep -q 'ACD-001-1-' "$D/index.html" || fail "index.html lacks the image"
grep -q '"GET /' <(docker logs "$CANARY" 2>&1 | grep -v '"GET /control') && fail "the render reached the network (canary was hit)"
chmod 755 "$D"
chmod 644 "$D"/*.png
pyrun --network none -v "$TMP:/t:ro" -v "$D:/d:ro" -- /t/verify.py \
  "logo:/d/$(basename "$LOGO")" "tall:/d/$(basename "$TEXT")" "nogreen:/d/$(basename "$HOSTILE")" \
  "basic:/d/$(basename "$ESC")" "basic:/d/$(basename "$FROM")" | grep -q '^ok$' || fail "image checks failed"
chmod 700 "$D"
ok "default pack: 5 images (800 px, full height, logo rendered), index rows, permissions, quiet stdout"
ok "hostile HTML: rendered, script did not run, no network request (canary not hit), big image noted"
ok "index.html escapes subject and from-name; CSV neutralises a formula from-name"

# --all: adds the internal and formula-subject mail, still no sign-in mail.
out="$(run b --all)" || { echo "$out"; fail "--all run failed"; }
D="$(newest)"
echo "$out" | grep -q "skipped sign-in emails: 9" || fail "--all: expected 9 sign-in emails skipped"
[ "$(csv_rows "$D")" = 10 ] || fail "--all index.csv should have 10 rows, has $(csv_rows "$D")"
[ -n "$(png_of "$D" ACD-001-2)" ] || fail "--all: internal mail missing"
for f in "'=1+2" "'+SUM(1)" "'-2+3" "'@SUM(1)"; do
  grep -qF ",$f,Ticket System," "$D/index.csv" || fail "--all: formula subject $f not neutralised"
done
grep -qi 'sign-in link\|Reset your\|ACD-003\|invited\|ready to set up\|administrator account\|Two-step\|verification code' \
  "$D/index.csv" "$D/index.html" && fail "--all lists a sign-in email"
ok "--all: 10 images, formula subjects neutralised, all 9 sign-in emails still left out"

# --since in the future: nothing selected, still a valid (empty) pack.
out="$(run c --since 2099-01-01T00:00:00Z)" || { echo "$out"; fail "--since run failed"; }
D="$(newest)"
[ "$(csv_rows "$D")" = 0 ] || fail "--since future should select nothing"
ok "--since filters by sent time"

# A run that fails late (in the render step, after collect wrote its files) leaves no pack and no
# work dir behind: same command with a collect/render script whose render step raises.
before="$(find "$TMP/base/qa-evidence" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
sed 's/^    cdp.call("Page.enable")$/    raise RuntimeError("synthetic render failure")/' \
  "$ROOT/scripts/qa/qa-mail-evidence.py" > "$TMP/broken.py"
grep -q 'synthetic render failure' "$TMP/broken.py" || fail "could not build the broken render script"
code=0
TS_BASE="$TMP/base" TS_QA_MAILPIT_CONTAINER="$MP" TS_QA_NO_ROOT_CHECK=1 \
  TS_QA_MAIL_LIB="$TMP/broken.py" TS_QA_RUN_TAG="${TAG}-c" "$CMD" > "$TMP/broken.out" 2>&1 || code=$?
[ "$code" != 0 ] || fail "a failing render should fail the command"
grep -q 'render step failed' "$TMP/broken.out" || fail "failing run did not fail in the render step"
after="$(find "$TMP/base/qa-evidence" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
[ "$before" = "$after" ] || fail "a failed run left a pack or work dir behind"
ok "failed render leaves no partial pack and no work dir"

[ "$(helper /t/state.py)" = "19 0" ] || fail "Mailpit changed (count or read flags)"
ok "Mailpit untouched: 19 messages, none marked read"
echo "mail-evidence-check: all checks passed"
