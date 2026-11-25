"""QA round email evidence pack (ADR-030), the two steps the host command
ticketsys-qa-mail-evidence runs in a pinned python container (stdlib only):

    collect  python -I qa-mail-evidence.py collect --api http://127.0.0.1:8025/mailpit/api/v1
                 --out /work [--since 2026-11-19T00:00:00Z] [--all]
             in the Mailpit container's network namespace, so it reaches Mailpit's REST API on
             127.0.0.1 without the public URL or the basic-auth password.
    render   python -I qa-mail-evidence.py render --cdp 127.0.0.1:9222 --work /work
             in the network namespace of the pinned headless Chromium container, which runs with
             --network none: it drives Chromium over the DevTools protocol on loopback and saves an
             800 px wide, full-height PNG of every page in plan.tsv. (This headless-shell build no
             longer answers the --screenshot / --dump-dom command-line switches.)

Read-only towards Mailpit: GET requests only, and only the list and raw-source endpoints (opening a
message through /api/v1/message/<id> would mark it read). Writes into --out:
  html/NNN.html   one page per selected email, rendered to PNG by the host command (no network)
  plan.tsv        NNN.html <TAB> png file name
  index.html, index.csv
and prints counts only (never addresses, subjects or bodies).

Selection:
  default  client-facing ticket mail: the subject starts with a bracketed ticket reference and is
           not agent-only "(Internal)" mail.
  --all    every email.
  Always excluded (no flag can include them): sign-in emails - invitations, password reset, magic
  links, first-admin set-up, two-step sign-in (MFA) notices - matched on the app's subject
  templates (api SystemEmail), plus any email whose text holds a sign-in token link.
"""

import argparse
import base64
import csv
import email
import email.policy
import email.utils
import html
import json
import os
import re
import socket
import struct
import time
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# Detection only, for filtering and file names: a bracketed reference at the start of the subject
# as TicketMailComposer sends it ("[ACD-001] ..."). The app's canonical parser stays in the
# reference module (the only place references are built); nothing here builds or normalises
# a reference.
REF_AT_START = re.compile(r"^\s*\[\s*([A-Za-z][A-Za-z0-9]{1,5}-[0-9]{1,10})\s*\]\s*(\(Internal\))?")

# Subjects of the sign-in system emails (api SystemEmail; %s = the configurable support name).
SIGN_IN_SUBJECTS = [
    re.compile(p, re.IGNORECASE)
    for p in (
        r"^\s*You have been invited to .+$",  # SUPPORTER_INVITATION
        r"^\s*Your .+ account is ready to set up\s*$",  # CUSTOMER_INVITATION
        r"^\s*Reset your .+ password\s*$",  # PASSWORD_RESET
        r"^\s*Your .+ sign-in link\s*$",  # CUSTOMER_MAGIC_LINK
        r"^\s*Your .+ administrator account is ready\s*$",  # FIRST_ADMIN_SETUP
        r"^\s*Two-step sign-in was .+ on your .+ account\s*$",  # MFA_ENROLLED / MFA_RESET / MFA_EXEMPTION_GRANTED
        r"verification code|sign-in code|one-time code|security code",
    )
]
# Token links the app mails (AuthProperties.link: <base><path>#token=...).
SIGN_IN_TEXT = re.compile(
    r"#token=|/magic-link\b|/reset-password\b|/accept-invitation\b", re.IGNORECASE
)

MAX_RAW_BYTES = 30 * 1024 * 1024
# Per inline (cid:) image: larger parts are not embedded; the index notes how many were skipped.
MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024
# <meta http-equiv="refresh"> would navigate the message frame away from the message.
META_REFRESH = re.compile(r"<meta\b[^>]*http-equiv\s*=\s*[\"']?\s*refresh[^>]*>", re.IGNORECASE)
PAGE = 200


def get(url, limit):
    req = urllib.request.Request(url, method="GET", headers={"Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = resp.read(limit + 1)
    if len(data) > limit:
        raise ValueError("response too large")
    return data


def list_messages(api):
    out, start = [], 0
    while True:
        q = urllib.parse.urlencode({"start": start, "limit": PAGE})
        page = json.loads(get(f"{api}/messages?{q}", 64 * 1024 * 1024))
        msgs = page.get("messages") or []
        out.extend(msgs)
        start += len(msgs)
        if not msgs or start >= int(page.get("total", 0)):
            return out


def parse_time(value):
    dt = datetime.fromisoformat(value.strip().replace(" ", "T").replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def sent_time(msg, summary):
    try:
        dt = email.utils.parsedate_to_datetime(msg["Date"])
        if dt is not None:
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(timezone.utc)
    except (TypeError, ValueError, IndexError):
        pass
    return parse_time(summary.get("Created", "1970-01-01T00:00:00Z"))


def bodies(msg):
    """(html, text, {content-id: data-uri, or None when over the size cap})."""
    html_body = text_body = None
    cids = {}
    for part in msg.walk():
        if part.is_multipart():
            continue
        ctype = part.get_content_type()
        disposition = part.get_content_disposition()
        cid = (part.get("Content-ID") or "").strip().strip("<>").strip()
        if cid and ctype.startswith("image/"):
            data = part.get_payload(decode=True) or b""
            if len(data) > MAX_INLINE_IMAGE_BYTES:
                cids[cid.lower()] = None
            elif data:
                b64 = base64.b64encode(data).decode("ascii")
                cids[cid.lower()] = f"data:{ctype};base64,{b64}"
            continue
        if disposition == "attachment":
            continue
        if ctype == "text/html" and html_body is None:
            html_body = part.get_content()
        elif ctype == "text/plain" and text_body is None:
            text_body = part.get_content()
    return html_body, text_body, cids


CID_REF = re.compile(r"cid:([^\"'\s)>]+)", re.IGNORECASE)


def inline_cids(body, cids):
    """Replaces cid: references with data: URIs; returns (body, embedded, skipped)."""
    used = skipped = 0

    def repl(m):
        nonlocal used, skipped
        key = urllib.parse.unquote(m.group(1)).lower()
        if key in cids:
            if cids[key] is None:
                skipped += 1
                return "about:blank"
            used += 1
            return cids[key]
        return m.group(0)

    return CID_REF.sub(repl, body), used, skipped


def is_sign_in(subject, html_body, text_body):
    if any(p.search(subject) for p in SIGN_IN_SUBJECTS):
        return True
    return any(b and SIGN_IN_TEXT.search(b) for b in (html_body, text_body))


def addr_list(summary_list):
    out = []
    for a in summary_list or []:
        name, address = (a.get("Name") or "").strip(), (a.get("Address") or "").strip()
        out.append(f"{name} <{address}>" if name else address)
    return ", ".join(out)


def neutral(value):
    """CSV cells: a leading = + - @ tab or CR would start a formula in a spreadsheet."""
    value = "" if value is None else str(value)
    return "'" + value if value[:1] in ("=", "+", "-", "@", "\t", "\r") else value


# No scripts at all in the page: the renderer measures the height over the DevTools protocol.
CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-src about:"

def page(meta, html_body, text_body):
    head = "".join(
        f"<tr><th>{html.escape(k)}</th><td>{html.escape(v)}</td></tr>" for k, v in meta if v
    )
    if html_body is not None:
        # Sandboxed frame (no scripts, no forms, no navigation); same origin only so the renderer
        # can measure its height.
        content = (
            '<iframe id="m" sandbox="allow-same-origin" scrolling="no" '
            f'srcdoc="{html.escape(html_body, quote=True)}"></iframe>'
        )
    else:
        content = f'<pre class="text">{html.escape(text_body or "(empty message)")}</pre>'
    return f"""<!doctype html>
<html lang="en-GB"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="{CSP}">
<style>
html, body {{ margin: 0; background: #ffffff; }}
table.h {{ width: 100%; border-collapse: collapse; font: 13px/1.4 Arial, Helvetica, sans-serif;
  background: #f4f5f7; border-bottom: 1px solid #dde1e6; }}
table.h th {{ text-align: left; width: 70px; padding: 4px 8px; color: #555b62; vertical-align: top; }}
table.h td {{ padding: 4px 8px; color: #1f2328; word-break: break-word; }}
iframe {{ display: block; width: 100%; border: 0; height: 400px; }}
pre.text {{ margin: 0; padding: 16px; white-space: pre-wrap; word-break: break-word;
  font: 14px/1.5 monospace; color: #1f2328; }}
</style></head>
<body><table class="h">{head}</table>{content}
</body></html>
"""


def write_index(out, rows):
    with open(os.path.join(out, "index.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ticket_ref", "subject", "from_name", "to", "sent_utc", "png", "inline_images",
                    "inline_images_skipped"])
        for r in rows:
            w.writerow([neutral(r[k]) for k in
                        ("ref", "subject", "from_name", "to", "sent", "png", "inline", "skipped")])
    body = "".join(
        "<tr>"
        + "".join(f"<td>{html.escape(str(r[k]))}</td>" for k in
                  ("ref", "subject", "from_name", "to", "sent"))
        + f'<td><a href="{html.escape(r["png"])}">{html.escape(r["png"])}</a></td>'
        + "<td>"
        + (f'{r["skipped"]} inline image(s) over 2 MB not embedded' if r["skipped"] else "")
        + "</td></tr>"
        for r in rows
    )
    shots = "".join(
        f'<h2>{html.escape(r["png"])}</h2><img src="{html.escape(r["png"])}" alt="">'
        for r in rows
    )
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(f"""<!doctype html>
<html lang="en-GB"><head><meta charset="utf-8"><title>Email evidence pack</title>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self'; style-src 'unsafe-inline'">
<style>body {{ font: 14px/1.4 Arial, Helvetica, sans-serif; margin: 16px; color: #1f2328; }}
table {{ border-collapse: collapse; }} th, td {{ border: 1px solid #dde1e6; padding: 4px 8px;
text-align: left; vertical-align: top; }} img {{ border: 1px solid #dde1e6; max-width: 100%; }}
h2 {{ font-size: 14px; margin-top: 24px; }}</style></head>
<body><h1>Email evidence pack</h1><p>{len(rows)} email(s), synthetic QA data (ADR-030).</p>
<table><tr><th>Ticket</th><th>Subject</th><th>From</th><th>To</th><th>Sent (UTC)</th><th>Image</th><th>Note</th></tr>
{body}</table>{shots}</body></html>
""")


def collect(args):
    api = args.api.rstrip("/")
    since = parse_time(args.since) if args.since else None
    out = args.out
    os.makedirs(os.path.join(out, "html"), exist_ok=True)
    summaries = list_messages(api)
    picked, sign_in, other, too_large = [], 0, 0, 0
    for s in summaries:
        subject = s.get("Subject") or ""
        if any(p.search(subject) for p in SIGN_IN_SUBJECTS):
            sign_in += 1
            continue
        m = REF_AT_START.match(subject)
        client_facing = bool(m) and not m.group(2)
        if not args.all and not client_facing:
            other += 1
            continue
        if int(s.get("Size") or 0) > MAX_RAW_BYTES:
            too_large += 1
            continue
        mid = urllib.parse.quote(str(s["ID"]), safe="")
        raw = get(f"{api}/message/{mid}/raw", MAX_RAW_BYTES)
        msg = email.message_from_bytes(raw, policy=email.policy.default)
        html_body, text_body, cids = bodies(msg)
        if is_sign_in(subject, html_body, text_body):
            sign_in += 1
            continue
        sent = sent_time(msg, s)
        if since and sent < since:
            other += 1
            continue
        picked.append((sent, s, m.group(1).upper() if m else "noref", html_body, text_body, cids))

    picked.sort(key=lambda p: p[0])
    rows, per_ref, inlined = [], {}, 0
    with open(os.path.join(out, "plan.tsv"), "w", encoding="utf-8") as plan:
        for i, (sent, s, ref, html_body, text_body, cids) in enumerate(picked, start=1):
            per_ref[ref] = per_ref.get(ref, 0) + 1
            png = f"{ref}-{per_ref[ref]}-{sent.strftime('%H%M%S')}.png"
            used = skipped = 0
            if html_body is not None:
                html_body, used, skipped = inline_cids(META_REFRESH.sub("", html_body), cids)
            inlined += used
            frm = s.get("From") or {}
            meta = [
                ("From", addr_list([frm])),
                ("To", addr_list(s.get("To"))),
                ("Cc", addr_list(s.get("Cc"))),
                ("Subject", s.get("Subject") or ""),
                ("Sent", sent.strftime("%Y-%m-%d %H:%M:%S UTC")),
            ]
            name = f"{i:03d}.html"
            with open(os.path.join(out, "html", name), "w", encoding="utf-8") as f:
                f.write(page(meta, html_body, text_body))
            plan.write(f"{name}\t{png}\n")
            rows.append({
                "ref": "" if ref == "noref" else ref,
                "subject": s.get("Subject") or "",
                "from_name": (frm.get("Name") or "").strip(),
                "to": addr_list(s.get("To")),
                "sent": sent.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "png": png,
                "inline": used,
                "skipped": skipped,
            })
    write_index(out, rows)
    print(f"mailpit messages: {len(summaries)}")
    print(f"selected: {len(rows)}")
    print(f"skipped sign-in emails: {sign_in}")
    print(f"skipped by filter: {other}")
    if too_large:
        print(f"skipped too large: {too_large}")
    print(f"inline images embedded: {inlined}")


class Cdp:
    """A minimal DevTools-protocol client over one WebSocket (stdlib only, loopback only)."""

    def __init__(self, url, timeout=60):
        u = urllib.parse.urlparse(url)
        if u.scheme != "ws" or u.hostname != "127.0.0.1":
            raise ValueError("DevTools URL must be ws://127.0.0.1")
        self.sock = socket.create_connection((u.hostname, u.port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        self.sock.sendall(
            (
                f"GET {u.path} HTTP/1.1\r\nHost: {u.hostname}:{u.port}\r\n"
                "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
            ).encode("ascii")
        )
        self.buf = b""
        while b"\r\n\r\n" not in self.buf:
            self._fill()
        head, _, self.buf = self.buf.partition(b"\r\n\r\n")
        if b" 101 " not in head.split(b"\r\n", 1)[0] + b" ":
            raise ConnectionError("DevTools WebSocket upgrade refused")
        self.next_id = 0
        self.events = []

    def _fill(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            raise ConnectionError("DevTools connection closed")
        self.buf += chunk

    def _take(self, n):
        while len(self.buf) < n:
            self._fill()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send_frame(self, opcode, data):
        n = len(data)
        head = bytearray([0x80 | opcode])
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126)
            head += struct.pack(">H", n)
        else:
            head.append(0x80 | 127)
            head += struct.pack(">Q", n)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(bytes(head) + mask + masked)

    def _recv(self):
        parts = []
        while True:
            b0, b1 = self._take(2)
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._take(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._take(8))[0]
            mask = self._take(4) if b1 & 0x80 else None
            data = self._take(n)
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            opcode = b0 & 0x0F
            if opcode == 8:
                raise ConnectionError("DevTools connection closed")
            if opcode == 9:
                self._send_frame(10, data)
                continue
            if opcode in (0, 1, 2):
                parts.append(data)
                if b0 & 0x80:
                    return json.loads(b"".join(parts))

    def call(self, method, params=None):
        self.next_id += 1
        msg_id = self.next_id
        self._send_frame(1, json.dumps({"id": msg_id, "method": method,
                                        "params": params or {}}).encode("utf-8"))
        while True:
            msg = self._recv()
            if msg.get("id") == msg_id:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error'].get('message')}")
                return msg.get("result", {})
            if "method" in msg:
                self.events.append(msg["method"])

    def wait_event(self, name, timeout):
        end = time.monotonic() + timeout
        while name not in self.events:
            if time.monotonic() > end:
                raise TimeoutError(name)
            msg = self._recv()
            if "method" in msg:
                self.events.append(msg["method"])


# Sizes the message frame to its content and returns the full page height.
MEASURE = """(() => {
  const f = document.getElementById('m');
  if (f && f.contentDocument) {
    const d = f.contentDocument;
    f.style.height = '0px';  // so the frame's own viewport does not pad the measurement
    f.style.height = Math.max(d.documentElement.scrollHeight,
      d.body ? d.body.scrollHeight : 0, 50) + 'px';
  }
  return Math.ceil(document.body.getBoundingClientRect().bottom);
})()"""


def render(args):
    """Screenshots every page in plan.tsv through the headless Chromium next door (shared
    network namespace, which has no network of its own)."""
    base = f"http://{args.cdp}"
    if not args.cdp.startswith("127.0.0.1:"):
        raise ValueError("DevTools must be on 127.0.0.1")
    target = None
    for _ in range(60):
        try:
            targets = json.loads(get(f"{base}/json/list", 1024 * 1024))
            target = next((t for t in targets if t.get("type") == "page"), None)
            if target:
                break
        except OSError:
            pass
        time.sleep(0.5)
    if not target:
        raise RuntimeError("headless Chromium did not start")
    cdp = Cdp(target["webSocketDebuggerUrl"])
    cdp.call("Page.enable")
    cdp.call("Emulation.setScriptExecutionDisabled", {"value": True})
    done = 0
    with open(os.path.join(args.work, "plan.tsv"), encoding="utf-8") as plan:
        for line in plan:
            page_name, png = line.rstrip("\n").split("\t")
            cdp.call("Emulation.setDeviceMetricsOverride",
                     {"width": 800, "height": 600, "deviceScaleFactor": 1, "mobile": False})
            cdp.events.clear()
            cdp.call("Page.navigate", {"url": f"file:///work/html/{page_name}"})
            cdp.wait_event("Page.loadEventFired", 30)
            res = cdp.call("Runtime.evaluate", {"expression": MEASURE, "returnByValue": True})
            height = int(res.get("result", {}).get("value") or 1200)
            height = max(200, min(height, 20000))
            cdp.call("Emulation.setDeviceMetricsOverride",
                     {"width": 800, "height": height, "deviceScaleFactor": 1, "mobile": False})
            shot = cdp.call("Page.captureScreenshot", {
                "format": "png",
                "clip": {"x": 0, "y": 0, "width": 800, "height": height, "scale": 1},
                "captureBeyondViewport": True,
            })
            with open(os.path.join(args.work, "png", png), "wb") as f:
                f.write(base64.b64decode(shot["data"]))
            done += 1
    print(f"rendered: {done}")


def main(argv):
    p = argparse.ArgumentParser(prog="qa-mail-evidence")
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("collect")
    c.add_argument("--api", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--since")
    c.add_argument("--all", action="store_true")
    r = sub.add_parser("render")
    r.add_argument("--cdp", required=True)
    r.add_argument("--work", required=True)
    args = p.parse_args(argv)
    if args.cmd == "render":
        render(args)
        return 0
    if not args.api.startswith("http://127.0.0.1:"):
        print("qa-mail-evidence: the API must be Mailpit on 127.0.0.1", file=sys.stderr)
        return 64
    collect(args)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
