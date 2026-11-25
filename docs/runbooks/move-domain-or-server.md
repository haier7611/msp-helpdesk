# Runbook - change the public URL or move to a new server

Two cases:

- **A. New address, same server**: a new DNS name, a new TLS proxy or tunnel, or both. Expected time
  about 1 hour plus DNS propagation.
- **B. New server**: everything moves to another host using a full export. Expected time about 2 to
  3 hours.

Rehearse part B on a spare host before you rely on it. Paths follow
[../deployment.md](../deployment.md).

## What depends on the public address

| Thing | Where it is set | What breaks if not changed |
| --- | --- | --- |
| DNS name | your DNS provider | nothing reaches the helpdesk |
| HTTPS (TLS certificate, reverse proxy or tunnel) | your proxy or tunnel configuration | no HTTPS, browsers refuse |
| `TS_PUBLIC_BASE_URL` | `/opt/ticket-system/.env` | links in emails (invitations, resets, sign-in links) point at the old address |
| Microsoft sign-in redirect URI | Entra app registration - Authentication | Microsoft sign-in fails with `AADSTS50011` |
| Mailbox (support address, SMTP sender) | Admin - Email and signature - Mailboxes, and `MAIL_FROM_ADDRESS` / `SMTP_*` in `.env` | mail in or out stops if the mailbox was on the old domain |
| Branding (support name, address, logo, signature template) | Admin - Branding and the company signature template | emails still show the old details |
| Uptime probe and alerts | your monitoring | alerts watch the wrong URL |

## A. New address, same server

1. **Choose the name**, for example `support.newdomain.example`, on a domain you control.
2. **Set up HTTPS for it.** Either:
   - **a reverse proxy on the host** (Caddy, nginx, Traefik): add a site for the new name pointing at
     `http://127.0.0.1:<WEB_PORT>`, create an A or AAAA record to the host, get a certificate. Keep
     the rules in [../deployment.md](../deployment.md) step 2 (`X-Forwarded-Proto`, overwrite
     `X-Forwarded-For`, 25 MB bodies);
   - **a tunnel** (for example Cloudflare Tunnel): run the tunnel client on the host, route the new
     name to it and point its ingress at `http://127.0.0.1:<WEB_PORT>`. The tunnel provider
     terminates TLS; no inbound port is needed.

   Keep `WEB_BIND=127.0.0.1` either way.
3. **Change the public URL**: in `/opt/ticket-system/.env` set
   `TS_PUBLIC_BASE_URL=https://support.newdomain.example` (no trailing slash), then
   `cd /opt/ticket-system/current && docker compose --env-file ../.env up -d --wait`.
4. **Microsoft sign-in** (if switched on): in Entra - App registrations - the helpdesk app -
   Authentication - Web, add `https://support.newdomain.example/api/v1/auth/microsoft/callback`
   ([entra-app-registration.md](entra-app-registration.md) step 1). Then run **Test sign-in** in
   Admin - Sign-in. Remove the old redirect URI once the old address is retired.
5. **Mailbox**: if the support mailbox stays the same, nothing changes. If it was on the old domain,
   set up the new mailbox in Admin - Email and signature - Mailboxes, run Test connection, enable it
   and disable the old one; update `MAIL_FROM_ADDRESS` and `SMTP_*` in `.env` if you use them, and
   apply. Check SPF, DKIM and DMARC of the new sending domain. Tell clients the new support address.
6. **Branding**: Admin - Branding (support name, company address, logo) and the signature template;
   send yourself a test reply and check the signature.
7. **Check**: `curl -fsS https://support.newdomain.example/api/v1/version`, sign in (password and
   two-step), raise a portal ticket, reply to it and check the email links use the new address, send
   an email to the support mailbox and see it become a ticket. Move the uptime probe to the new URL.
8. **Retire the old address**: remove the old proxy site or tunnel route, the old Entra redirect URI
   and, when nobody uses it any more, the old DNS record.

## B. New server

1. **Export on the old host**, as root: `ticketsys-export` (installed with the backup scripts,
   [../30-reliability.md](../30-reliability.md) "Backups"). It writes
   `/opt/ticket-system/exports/ticketsys-export-<UTC>.tar` (mode 600): `db.dump` (whole database
   except live sessions, sign-in links and trusted devices), `attachments.tar.gz`, `settings.env`
   (non-secret settings only) and `MANIFEST.txt` (row counts and sha256 of each file).

   It contains client personal data **and** credential material: `db.dump` holds the password
   hashes, hashed MFA recovery codes and API-key hashes (so users keep their sign-in) and the MFA,
   mailbox and Microsoft secrets encrypted with `TS_DATA_KEY`. It is not a sanitised dataset: keep it
   root-only (mode 600), copy it only over SSH (`scp`), never by email or chat, never use it as test
   or demo data, and delete every copy (old host, new host, any laptop) once step 9 is done.
2. **Carry the secrets separately** (password manager, not the archive): `TS_DATA_KEY` and
   `TS_DATA_KEY_ID` (without them every MFA, mailbox and Microsoft secret in the database is
   unreadable), and the SMTP password if one is used. Database passwords are new on the new host.
3. **Prepare the new host** as in [../deployment.md](../deployment.md): layout, the same release as
   the old host (`GIT_SHA` in `settings.env`), and a new `.env`. Copy the lines from `settings.env`
   that apply (public URL, mail sender), generate new `POSTGRES_PASSWORD` and `APP_DB_PASSWORD`, add
   `TS_DATA_KEY` and `TS_DATA_KEY_ID` from step 2. Leave `TS_SETUP_TOKEN` empty (an admin exists in
   the restored data). Start the stack once so the database exists, then go on.
4. **Stop the api**: `docker stop ticketsys-api`.
5. **Restore**:

   ```sh
   tar -xf ticketsys-export-<UTC>.tar && cd ticketsys-export-<UTC>
   sed -n '/^sha256:/,$p' MANIFEST.txt | tail -n +2 | sha256sum -c
   docker exec -i ticketsys-postgres sh -c \
     'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --exit-on-error' < db.dump
   docker exec ticketsys-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "ALTER FUNCTION scrub_audit_email(bigint) OWNER TO ticketsys_audit_scrub; ALTER FUNCTION capture_erased_address() OWNER TO ticketsys_audit_scrub"'
   tar -C /opt/ticket-system/data -xzf attachments.tar.gz
   chown -R 10001:10001 /opt/ticket-system/data/attachments
   docker start ticketsys-api
   ```

   `--no-owner` leaves every object owned by the restoring role, hence the `ALTER FUNCTION` line,
   which hands the two audit-scrub functions back to their narrow owner. On start, Flyway sets the
   app role's password from the new `.env`.
6. **Compare**: the row counts in `MANIFEST.txt` against
   `SELECT count(*) FROM app_user` (and `organisation`, `ticket`, `attachment`) on the new host.
7. **Address**: follow part A for the new host. Everyone signs in again (sessions were not
   exported); trusted devices must be trusted again.
8. **Backups**: install the backup timer on the new host, then run
   `systemctl start ticketsys-backup.service` and `ticketsys-restore-drill` once.
9. **Retire the old host** only after a week of normal running and a good restore drill on the new
   one; keep its last backup until then.
