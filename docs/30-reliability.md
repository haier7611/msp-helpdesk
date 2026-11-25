# Reliability

How you know a running MSP Helpdesk is healthy, how data is backed up and restored, and what to do
when mail breaks. It assumes the layout in [deployment.md](deployment.md) (`/opt/ticket-system`,
default container names). Day-2 routine is in [09-operations.md](09-operations.md). Diagram:
[diagrams/11-backup-restore.md](diagrams/11-backup-restore.md).

Commands run on the host as root. Where a command reads the database it goes through the postgres
container's local socket, which needs no password.

## Backups

The scripts live in `scripts/backup/`:

| File | Installed as | What |
| --- | --- | --- |
| `ticketsys-backup.sh` | `/usr/local/sbin/ticketsys-backup` | `pg_dump -Fc` and an attachments archive, prune old files |
| `ticketsys-backup.service` / `.timer` | `/etc/systemd/system/` | run it nightly at 02:30 UTC plus up to 15 minutes; `Persistent=true` catches up after downtime |
| `ticketsys-backup-failed.sh` / `.service` | `/usr/local/sbin/`, `/etc/systemd/system/` | `OnFailure=` handler that writes the failure marker |
| `ticketsys-restore-drill.sh` | `/usr/local/sbin/ticketsys-restore-drill` | the restore drill (below) |
| `ticketsys-restore-test.sh` | `/usr/local/sbin/ticketsys-restore-test` | an older, quicker check |
| `ticketsys-export.sh` | `/usr/local/sbin/ticketsys-export` | full export for moving hosts |

### Install

From the current release:

```sh
cd /opt/ticket-system/current
install -m 755 scripts/backup/ticketsys-backup.sh /usr/local/sbin/ticketsys-backup
install -m 755 scripts/backup/ticketsys-backup-failed.sh /usr/local/sbin/ticketsys-backup-failed
install -m 755 scripts/backup/ticketsys-restore-drill.sh /usr/local/sbin/ticketsys-restore-drill
install -m 755 scripts/backup/ticketsys-restore-test.sh /usr/local/sbin/ticketsys-restore-test
install -m 755 scripts/backup/ticketsys-export.sh /usr/local/sbin/ticketsys-export
install -m 644 scripts/backup/ticketsys-backup.service scripts/backup/ticketsys-backup-failed.service \
  scripts/backup/ticketsys-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now ticketsys-backup.timer
```

Re-run it after an upgrade that changes `scripts/backup/`. The scripts expect GNU coreutils (Linux),
the base directory `/opt/ticket-system`, attachments in `/opt/ticket-system/data/attachments` and
the postgres container named `ticketsys-postgres`. Adjust the variables at the top of each script if
your layout differs.

### What a run does

- `pg_dump -Fc` to `backups/db-<UTC>.dump`, written as `.partial` first and kept only if
  `pg_restore --list` can read it;
- a tar of `data/attachments` to `backups/attachments-<UTC>.tar.gz`;
- deletes backup files older than 30 days (`RETENTION_DAYS`). The 30-day window is also how long an
  erased person can survive in backups (spec section 10);
- on success, removes `backups/BACKUP_FAILED` and writes the time to `backups/LAST_SUCCESS`.

Any error exits non-zero; systemd then runs `ticketsys-backup-failed.service`, which writes the time
and a journal hint to `backups/BACKUP_FAILED`. The next good run removes it.

- Run now: `systemctl start ticketsys-backup.service; journalctl -u ticketsys-backup -n 5`.
- Check: `cat /opt/ticket-system/backups/LAST_SUCCESS`; `ls /opt/ticket-system/backups/BACKUP_FAILED`
  should find nothing.
- If it failed: `journalctl -u ticketsys-backup -n 50`. Usual causes are the postgres container being
  down or a full disk. Fix, then run it by hand.

### Copy backups off the host

The nightly files sit on the same disk as the database. A lost disk or host loses both, so copy
them somewhere else. The script has a marked hook (`TODO(off-host)` near the end of
`ticketsys-backup.sh`) for this. A good off-host step:

- encrypts each night's `db-*.dump` and `attachments-*.tar.gz` with a key that is **not** stored
  at the target (for example `age` or `gpg` with a public key);
- copies them with rsync over SSH, or to object storage with its own retention;
- exits non-zero when the copy fails, so the unit fails and `BACKUP_FAILED` is written.

Keep the off-host retention at 30 days or less if you rely on the backup window as the erasure lag.
Keep `TS_DATA_KEY` with your other host secrets, not next to the backups.

## Restore drill

Run monthly, after any change to the backup scripts, and before you load real data:

```sh
ticketsys-restore-drill                        # newest dump
ticketsys-restore-drill db-<UTC>.dump --keep   # a chosen dump; keep the scratch database afterwards
```

It restores the dump into a scratch database `ticketsys_restore_drill` next to the live one and
checks:

1. `pg_restore` finishes without error;
2. every table holds exactly the rows the dump carries (counted from the dump's own data, so changes
   on the live system since the backup do not matter);
3. the attachments archive of the same night exists, is readable and holds every file the restored
   attachment rows point at (missing files are listed by key);
4. the live erasure log (`user_erasure`) is replayed into the restored copy with `ts_erase_user`, and
   no erased person keeps a name or email.

It then drops the scratch database (unless `--keep`), prints the time taken and writes one line to
`backups/RESTORE_DRILL_LAST`: `<time> OK|FAILED dump=... seconds=...`. Exit 0 means passed. It never
writes to the live database or the backup files. It is tested against a real Postgres in
`BackupScriptsIntegrationTest`. Keep a record of each run (date, dump, result, seconds) in your
ops log; the seconds are your evidence for a recovery-time objective.

`ticketsys-restore-test` is an older, quicker check (Flyway history, table count and that the
attachments archive is readable). The drill supersedes it.

## Full restore (real incident)

1. Stop the api so nothing writes: `docker stop ticketsys-api`.
2. If the live database still exists, take a fresh backup first
   (`systemctl start ticketsys-backup.service`) so the restore can be undone.
3. Restore into the live database (replaces its objects):

   ```sh
   docker exec -i ticketsys-postgres sh -c \
     'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error' \
     < /opt/ticket-system/backups/db-<UTC>.dump
   ```

   `--no-owner` leaves every object owned by the restoring role. Hand the two audit-scrub functions
   back to their narrow owner:

   ```sh
   docker exec ticketsys-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "ALTER FUNCTION scrub_audit_email(bigint) OWNER TO ticketsys_audit_scrub; ALTER FUNCTION capture_erased_address() OWNER TO ticketsys_audit_scrub"'
   ```

4. Attachments:
   `tar -C /opt/ticket-system/data -xzf /opt/ticket-system/backups/attachments-<UTC>.tar.gz`, then
   `chown -R 10001:10001 /opt/ticket-system/data/attachments`.
5. Replay the erasure log (spec section 10) before going back online, so nobody erased after the
   backup comes back. The restored backup's own `user_erasure` rows are already applied; take the
   later user ids from the copy saved in step 2 (or your newest record of erasures):

   ```sh
   docker exec ticketsys-postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"SELECT count(*) FROM unnest('{<id>,<id>}'::bigint[]) AS x(id) WHERE ts_erase_user(x.id, now())\""
   ```

6. `docker start ticketsys-api`, then the health check ([09-operations.md](09-operations.md)).

## Full export (moving hosts)

`ticketsys-export [output-dir]` (root, default `/opt/ticket-system/exports`) writes
`ticketsys-export-<UTC>.tar` (mode 600) holding:

- `db.dump`: the whole database without the rows of live sessions, sign-in links, trusted devices
  and job locks;
- `attachments.tar.gz`: the attachment store;
- `settings.env`: the non-secret settings from `.env` (an allow-list; new keys stay out until added);
- `MANIFEST.txt`: time, release, Flyway version, row counts and sha256 of each file.

No settings secrets are included: no database or SMTP password, no `TS_DATA_KEY`, no setup token.
But `db.dump` does hold credential material on purpose, so users keep their sign-in: password
hashes, hashed recovery codes, API-key hashes, and the MFA, mailbox and Microsoft secrets encrypted
with `TS_DATA_KEY`. It is not a sanitised dataset: keep it root-only, copy it only over SSH, never
use it as test or demo data, and delete every copy after the move. Steps:
[runbooks/move-domain-or-server.md](runbooks/move-domain-or-server.md).

## Outbound mail

Every email goes through the `outbox_message` table: business code queues it in its own
transaction, and the api's outbox worker sends it within seconds of the commit (and polls every 15
seconds as a safety net). The sender is chosen each round:

1. the first enabled mailbox (lowest id) with outgoing (SMTP) settings and a stored secret, set up in
   **Admin - Email and signature - Mailboxes**. It sends every email, ticket mail and account mail
   (invitations, resets, sign-in links) alike (ADR-034, ADR-046). Password or OAuth2 (XOAUTH2) sign-in;
2. otherwise the `SMTP_*` and `MAIL_*` settings in `.env` (password sign-in only;
   `SMTP_SECURITY=starttls` for 587 or `tls` for 465; `none` is refused together with a user name
   and password);
3. with neither, mail is queued but not sent, and the api logs a warning at start.

Emails to reserved test domains (`.example`, `.test`, `.invalid`, `.localhost`) are never sent
through a connected mailbox; the ticket shows "Not emailed: test address". A configured mailbox that
cannot send right now (secret unreadable, token request failed) keeps mail queued; it never falls
back to the `.env` sender.

### Mailpit in development

The `dev` and `demo` compose profiles (`COMPOSE_PROFILES` in `.env`) start **Mailpit**, a local mail
catcher, and the local `.env` points `SMTP_*` at it, so nothing leaves the machine.

- Local runs ([guides/local-development.md](guides/local-development.md)): open
  `http://localhost:8025/mailpit/` (or your `MAILPIT_UI_PORT`). The port binds `127.0.0.1` only.
- On a test server, reach the same port with an SSH port forward
  (`ssh -L 8025:127.0.0.1:8025 <host>`), or serve it through the web container at `/mailpit/` behind
  HTTP basic auth: set `MAILPIT_UI_USER` and `MAILPIT_UI_HASH` (a bcrypt hash from
  `caddy hash-password`, single-quoted in `.env`). Without a hash the route answers 404 for
  everyone. Mailpit holds live sign-in links for every account, so switch the route off again when
  you are done.
- Each message's raw source and headers (Message-ID, `Auto-Submitted`) are in the Mailpit UI and its
  API (`/mailpit/api/v1/messages`). It keeps up to 500 messages in memory; a restart empties it.
- Never run Mailpit on a server that holds real data; remove `COMPOSE_PROFILES` in production.

### Mail evidence

`scripts/qa/ticketsys-qa-mail-evidence.sh` makes a review pack of what clients saw: one PNG per
client-facing ticket email in Mailpit, plus `index.html` and `index.csv`, in a root-only folder
under `/opt/ticket-system/qa-evidence/`. It reads Mailpit read-only, renders each email in a
headless browser with no network, and always leaves out sign-in emails (invitations, resets, sign-in
links, MFA notices, anything with a token link). `--all` includes every other email; `--since`
limits by time. Use it on test data only and delete the pack after review.
`scripts/ci/mail-evidence-check.sh` tests it, hostile HTML included.

### Runbook: mail stuck in the outbox

```sh
docker exec ticketsys-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT status, count(*), min(next_attempt_at) FROM outbox_message GROUP BY status"'
docker logs ticketsys-api 2>&1 | grep -E "Outbox|Outbound mail" | tail -20
```

- `queued` rows piling up with `attempts = 0`: no sender configured (startup warning "Outbound mail
  is not configured") or the worker is not running. Check the mailbox and `SMTP_HOST`.
- `queued` with `attempts > 0` and `last_error` "connection or transport error" or "SMTP 4xx": the
  server is unreachable or deferring. Rows retry after 1, 2, 4, 8, 16 and 32 minutes, then fail.
  "SMTP authentication failed" means wrong credentials.
- `failed` with "SMTP 5xx (permanent)": the server refused the message or recipient (bad address,
  sender not allowed). Fix the cause; do not just retry.
- `sending` for more than 10 minutes: the api stopped mid-send. The worker puts these back in the
  queue itself (at-least-once: the recipient may get it twice).
- Retry a failed, non-sensitive row once the cause is fixed:
  `UPDATE outbox_message SET status = 'queued', attempts = 0, next_attempt_at = now(), last_error = NULL WHERE id = <id> AND status = 'failed' AND NOT sensitive;`.
  Sensitive rows (invitation, reset, sign-in link) have blank bodies after failing and cannot be
  resent; the user asks for a new link.
- Logs and `last_error` never contain addresses, subjects or bodies; correlate by outbox id.

## Inbound mail

The api polls every **enabled** mailbox every 30 seconds (ADR-039) on its own thread. Nothing is
configured from `.env`: without a mailbox nothing is polled. Each mail is processed in its own
transaction and logged in `inbound_mail` with an outcome (CREATED, TRIAGE, CLIENT_REPLY,
SYSTEM_ENTRY, NOTE, PUBLIC_REPLY, FOLDED, BLOCKED, PROBE or DUPLICATE) and, for triage, a reason.
Attachments are processed after the ticket or comment is committed, from the kept raw `.eml`.
A round handles at most 200 messages and 100 MB per mailbox; the rest wait for the next round. A
mail over 30 MB keeps only its first 256 KB and goes to triage as unparseable. A mailbox that cannot
be opened is recorded on that mailbox, and the others are still polled.

### Mailbox prerequisites

1. **A dedicated support mailbox.** Microsoft 365: an Entra app with the `IMAP.AccessAsApp`
   application permission (and `SMTP.SendAsApp` to send with OAuth2), admin consent, access to the
   mailbox granted through an Exchange service principal, and a client secret; sign-in `OAUTH2`.
   Google or another host: an app password with sign-in `PASSWORD`
   ([runbooks/connect-gmail.md](runbooks/connect-gmail.md)).
2. **Plus-addressing on**: `support+internal@` must arrive in the same mailbox. Without it, agents'
   replies to internal alerts go astray.
3. **Trusted authserv-id**: the name the receiving server writes at the start of its
   `Authentication-Results` header. Microsoft 365 writes none: `(none)`. An empty field is filled in
   as `mx.google.com` for Gmail hosts and `(none)` for `outlook.office365.com`. A wrong value sends
   every mail to triage as not authenticated.
4. **Client domains publish DMARC**, otherwise their mail always goes to triage.
5. **Test connection**, then wait until the probe "arrived". "Did not arrive" after 30 minutes means
   plus-addressing is off or the mail is filtered.
6. **Enable the mailbox.** Only mail that arrives after it is saved enabled becomes tickets (ADR-033);
   mail already in the folder stays out. A `MAILBOX_UIDVALIDITY_CHANGED` audit event means the server
   renumbered the folder and polling restarted from new mail: check for anything that arrived around
   that time.

### Runbook: email is not turning into tickets

```sh
docker exec ticketsys-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT id, enabled, last_poll_at, last_success_at, last_error, consecutive_failures, next_poll_at FROM mailbox"'
docker exec ticketsys-postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"SELECT outcome, reason, count(*) FROM inbound_mail WHERE received_at > now() - interval '1 day' GROUP BY 1, 2\""
docker logs ticketsys-api 2>&1 | grep -E "mail poll|mailbox [0-9]+" | tail -20
```

- `last_error` "login failed": wrong or expired password or app password, or (OAuth2) the app lost
  its permission. "token request failed": wrong tenant or client id, or the client secret expired.
  "connection failed": host, port or security wrong, or the server is down. "stored secret
  unreadable": enter the secret again. Failures back off 1, 2, 4 up to 30 minutes; saving the
  mailbox clears the back-off. Mail is not lost meanwhile: it stays on the server.
- The poller seems stuck: `SELECT * FROM mail_poller_lock;`. A crashed poller's lease expires after
  5 minutes by itself.
- Many `TRIAGE` rows with reason `NOT_AUTHENTICATED`: check the trusted authserv-id against a real
  mail's header, then whether the client's domain publishes DMARC.
- A mail "disappeared": search `inbound_mail` by `from_address` and `received_at`. BLOCKED rows are in
  the Blocked mail log for 30 days; DUPLICATE rows are a repeat delivery; everything else has a
  `ticket_id`.
- A temporary database error leaves the mail unread and retries it up to 3 times.
- A message that crashes processing becomes a triage ticket "Email could not be processed" (reason
  `PROCESSING_FAILED`) with the raw `.eml`; report it as a bug with the `inbound_mail` id.
- Raw `.eml` is cleared after 90 days. It is personal data; handle it as such.
- Several api instances are safe: the lease means only one polls at a time, and the unique
  Message-ID stops a second processing.

## Data retention jobs

`RetentionJob` runs nightly at 03:40 UTC, after the 02:30 UTC backup, so anything it deletes is still
in that night's backup for 30 days. It works in bounded batches, each its own transaction; a failing
batch rolls back alone, is logged and retries the next night. Every step that removed rows writes a
`RETENTION_RUN` audit event with counts only.

| Data | Kept for | Job |
| --- | --- | --- |
| Closed tickets with comments, notes, history, participants, attachments, mail log and outbox rows | the organisation's retention period since closing (default 36 months; 12 to 120) | `RetentionJob` |
| Audit events | 1 year | `RetentionJob` |
| Sent or failed outbox rows | 90 days | `RetentionJob` |
| Sign-in link tokens | 30 days after expiry | `RetentionJob` |
| Raw `.eml` | 90 days | `InboundRetentionJob` (hourly) |
| Blocked mail log | 30 days | `InboundRetentionJob` (hourly) |
| Expired sessions | at expiry (8 h idle, 24 h absolute) | Spring Session cleanup (every minute) |
| Backups | 30 days | `ticketsys-backup` |

Dry run: [09-operations.md](09-operations.md) "Retention job and dry run".

## Data-key rotation

`docker compose exec api java -jar /app/app.jar rotate-data-key [--check]`; steps and failure handling
in [runbooks/rotate-data-key.md](runbooks/rotate-data-key.md).

## Break-glass: reset a lost admin MFA

When the only admin has lost their authenticator and recovery codes, or no other admin is available:

1. On the host, in the release directory:
   `docker compose --env-file ../.env exec api java -jar /app/app.jar reset-mfa --email=<their email>`.
2. It prints `reset-mfa: two-step sign-in reset for user id N ...` and exits 0 (exit 2 = no such agent
   or admin, nothing changed). It never prints a secret.
3. All their sessions end and trusted devices are revoked; the reset email is queued and sent by the
   running app. At their next sign-in they set up a new authenticator.
4. The audit log has an `MFA_RESET` event with method `break_glass` and no actor. Record who ran it and
   why in your ops log.

The command starts the application without a web server, uses the database settings already in the
api container and exits (`BreakGlassCommandIntegrationTest`). If `TS_DATA_KEY` itself is lost, every
agent must enrol again: run this command for one admin, who then resets the others.

## Service levels

Suggested starting targets for a single-host install; set your own.

- **Availability**: share of probe checks of `/actuator/health` at the public address that succeed.
  99.0% over 30 days allows about 7 hours 18 minutes down a month.
- **Email intake**: 95% of mail to the support mailbox becomes a ticket within 5 minutes (the poller
  runs every 30 seconds).
- **Outbound mail**: 99% of queued mail sent within 10 minutes (outbox retries cover short outages).
- **Recovery**: RPO 24 hours (nightly backup), RTO one working day (the restore drill's timing is the
  evidence).

## Runbook template

One per alert; every alert has a runbook or it is not a real alert.

```
# Runbook: <alert name>
- Symptom / what fired:
- Impact (who / what is broken):
- First checks (commands, dashboards):
- Fix steps (ordered, safe to paste):
- If that fails / escalate to:
- Related: SLO, dashboard, past postmortems.
```

## Postmortem template (blameless)

Blame the system and the process, never the person.

```
# Postmortem: <incident>
- Summary and user impact:
- Duration (UTC start - end):
- Timeline (UTC):
- Root cause (systems and process, not people):
- What went well / what was hard:
- Action items (owner, due):
```
