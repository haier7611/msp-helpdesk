# Operations (day 2)

How to keep a running MSP Helpdesk healthy. It assumes the setup in [deployment.md](deployment.md):
one app host running the Docker Compose stack behind a TLS reverse proxy, with settings in
`/opt/ticket-system/.env` and the current release checked out at `/opt/ticket-system/current`.
Backups, restore and mail failure handling are in [30-reliability.md](30-reliability.md); one-off
procedures are in `runbooks/`. Diagrams: [diagrams/](diagrams/).

Commands below run on the host as root, from `/opt/ticket-system/current`, and use
`docker compose --env-file ../.env ...`. Container names assume the default prefix
(`ticketsys-web`, `ticketsys-api`, `ticketsys-postgres`).

## Who does what

| Task | Who |
| --- | --- |
| Upgrades, rollback, restarts, `.env` changes | Whoever holds root on the host |
| Backup checks, restore drill, retention dry run | Host operator |
| Break-glass MFA reset, data-key rotation | Host operator; host shell access is the authorisation, and both leave an audit event or need a note in your ops log |
| Day-to-day admin (agents, organisations, mailboxes, SLA, block list) | Helpdesk admin, in the app: [guides/admin-guide.md](guides/admin-guide.md) |

## Daily and weekly checks

1. An external uptime probe on `https://<your address>/actuator/health` (alert after a few minutes
   down).
2. Backup health: `cat /opt/ticket-system/backups/LAST_SUCCESS` is less than 36 hours old and
   `/opt/ticket-system/backups/BACKUP_FAILED` does not exist.
3. Weekly: in the app, the mailbox status in **Admin - Email and signature - Mailboxes** (last
   error), the **Email triage** queue, the outbox (query in [30-reliability.md](30-reliability.md))
   and the api log for `retention` lines and errors.

## Health checks

- Inside the host: `curl -fsS http://127.0.0.1:<WEB_PORT>/actuator/health` returns
  `{"status":"UP"}` (no details, by design; nothing else under `/actuator` is public).
- Through the proxy: the same path at the public address. If the inside check is up but the public
  one is down, the fault is the proxy, DNS or tunnel.
- Version: `curl -fsS https://<your address>/api/v1/version` returns the name, version and git sha
  of the running build.
- Containers: `docker compose --env-file ../.env ps` (all three should be `healthy`).

## Logs

- `docker logs ticketsys-api` (also `ticketsys-web`, `ticketsys-postgres`).
- Every container logs to Docker's json-file driver, rotated at 10 MB by 5 files (`x-logging` in
  `docker-compose.yml`), so at most 50 MB each. Ship them elsewhere if you need a longer history.
- The api logs hold no secrets and use ids instead of personal data (tested by log-capture tests).
  If you find a secret, an email address or message text in a log, treat it as a bug and report it
  ([../SECURITY.md](../SECURITY.md) for anything sensitive).

## Upgrades

1. Read the CHANGELOG for the new version, and take a backup:
   `systemctl start ticketsys-backup.service` ([30-reliability.md](30-reliability.md)).
2. Check out the new version next to the old one, then switch `current`:

   ```sh
   git clone https://github.com/haier7611/msp-helpdesk.git /opt/ticket-system/releases/v2
   cd /opt/ticket-system/releases/v2 && git checkout <tag or commit>
   ln -sfn /opt/ticket-system/releases/v2 /opt/ticket-system/current
   ```

3. Build and start with a new image tag:

   ```sh
   cd /opt/ticket-system/current
   APP_IMAGE_TAG=v2 GIT_SHA="$(git rev-parse --short HEAD)" \
     docker compose --env-file ../.env up -d --build --wait
   ```

4. Flyway migrates the schema when the api starts; `--wait` returns once every container is
   healthy. Check `/actuator/health`, `/api/v1/version` and a sign-in.

Never run `docker compose down -v` and never remove the `ticketsys_pgdata` volume: that is the
database.

## Rollback

Keep the previous release directory and its images (`docker image ls ticketsys-api`). To go back:

```sh
ln -sfn /opt/ticket-system/releases/v1 /opt/ticket-system/current
cd /opt/ticket-system/current
APP_IMAGE_TAG=v1 docker compose --env-file ../.env up -d --wait --no-build
```

Flyway migrations are append-only and are never undone. Before rolling back past a release that
added a migration, check the older code tolerates the newer schema, or restore the backup taken in
step 1 of the upgrade ([30-reliability.md](30-reliability.md) "Full restore").

## Changing `.env`

Edit the file (mode 600; never pass a secret on a command line), then
`docker compose --env-file ../.env up -d --wait`. Compose recreates only the containers whose
settings changed. The api refuses to start on invalid settings or a `REPLACE_ME` placeholder and
names the variable at fault.

## Secrets and key rotation

- **Data key** (`TS_DATA_KEY`): yearly, or when it may have been exposed. Backup first, old key kept
  readable, `rotate-data-key`, `--check`, then remove the old key:
  [runbooks/rotate-data-key.md](runbooks/rotate-data-key.md).
- **Database application password** (`APP_DB_PASSWORD`): edit `.env` and apply. Flyway sets the
  `ticketsys_app` role's password from it on every api start.
- **API keys**: issue a new key for the integration, switch it over, revoke the old one in
  **Admin - API keys**.
- **Microsoft sign-in client secret**: renew before it expires; the app shows admins a banner from
  30 days ahead. [runbooks/entra-app-registration.md](runbooks/entra-app-registration.md) section 9.
- **Mailbox passwords and app passwords**: enter the new one in the mailbox settings and run
  **Test connection**.

## Break-glass: a lost admin MFA

When the only admin has lost their authenticator and recovery codes:

```sh
docker compose --env-file ../.env exec api java -jar /app/app.jar reset-mfa --email=<their email>
```

It prints the user id only, ends their sessions, revokes trusted devices, queues the "two-step
sign-in was reset" email and writes an `MFA_RESET` audit event with method `break_glass`. Exit 2
means no agent or admin has that email. Details in [30-reliability.md](30-reliability.md)
"Break-glass".

## Retention job and dry run

`RetentionJob` runs nightly at 03:40 UTC, after the 02:30 UTC backup, and deletes closed tickets
past each organisation's retention period, old audit events and other expired data (table in
[30-reliability.md](30-reliability.md) "Data retention jobs").

To preview what it would delete:

1. Set `APP_RETENTION_DRY_RUN=true` in `.env` and apply it (`up -d --wait`).
2. After the next nightly run: `docker logs ticketsys-api 2>&1 | grep retention`. A dry run logs
   `retention: dry run, nothing will be deleted` at the start and the counts at the end. If the
   start line is missing, the switch did not take.
3. Set it back to `false` and apply again, or nothing is ever deleted.

## Monitoring hints

The api exposes only `/actuator/health`; there are no metrics or traces endpoints. Useful signals:

| Signal | Where | Alert when |
| --- | --- | --- |
| Availability | probe on `/actuator/health` at the public address | down for more than 5 minutes |
| Backup | `backups/BACKUP_FAILED`, age of `backups/LAST_SUCCESS` | marker exists, or newest backup older than 36 h |
| Restore drill | `backups/RESTORE_DRILL_LAST` | line says `FAILED`, or older than a month |
| Outbound mail | `outbox_message` rows by status | `failed` rows, or `queued` older than 10 minutes |
| Inbound mail | `mailbox.last_error`, `consecutive_failures` | any repeated failure |
| Disk | host disk usage, `/opt/ticket-system/backups` size | above 80% |

A simple check for a stale backup, suitable for cron or a node exporter text file:

```sh
find /opt/ticket-system/backups -maxdepth 1 -name 'db-*.dump' -mmin -2160 | grep -q . || echo "backup stale"
```

## Export and move

`ticketsys-export` writes a portable archive (dump, attachments, non-secret settings, manifest) for
moving to a new server. It holds password hashes and encrypted secrets, so treat it like a backup
and delete it after the move. Steps:
[runbooks/move-domain-or-server.md](runbooks/move-domain-or-server.md).

## Incidents

Use the blameless postmortem template in [30-reliability.md](30-reliability.md). Tell affected
clients about a long outage; do not hide it.
