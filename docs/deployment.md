# Deployment

How to run MSP Helpdesk on your own Linux server with Docker Compose. For a laptop, use
[guides/local-development.md](guides/local-development.md) instead. Day-2 tasks (health checks,
upgrades, rollback, logs) are in [09-operations.md](09-operations.md); backups and mail are in
[30-reliability.md](30-reliability.md).

## What you need

- A Linux host (x86-64 or arm64) with Docker Engine and the Compose plugin (v2.20 or later), and
  about 3 GB of memory for the stack (the api container is capped at 2 GB, the web at 256 MB).
- A DNS name for the helpdesk, for example `helpdesk.example`.
- A TLS reverse proxy in front of the stack: Caddy, nginx, Traefik, a Cloudflare Tunnel or similar.
  The stack itself speaks plain HTTP only.
- An SMTP account or a support mailbox for outbound mail (Microsoft 365, Google Workspace or any
  IMAP/SMTP host).
- `openssl` to generate secrets.

## Shape

```
browser -> your TLS reverse proxy (HTTPS, public)
        -> 127.0.0.1:<WEB_PORT> on the host -> web container (Caddy: React app, /api and /actuator/health proxied)
        -> api container (Spring Boot, no published port) -> postgres container (no published port)
```

Only the web container publishes a port. Postgres and the api sit on an internal Docker network.
See [40-architecture.md](40-architecture.md) for the full picture.

## Suggested layout

The backup and export scripts in `scripts/backup/` assume this layout, so it is easiest to keep it:

```
/opt/ticket-system/
  .env                  settings and secrets (mode 600, root)
  releases/<version>/   one checkout per release
  current -> releases/<version>
  data/attachments/     attachment store (owned by uid/gid 10001, the api's user in the container)
  backups/              nightly backups (mode 700)
  exports/              full exports for moving hosts (mode 700)
```

```sh
sudo install -d -m 755 /opt/ticket-system /opt/ticket-system/releases /opt/ticket-system/data
sudo install -d -m 750 -o 10001 -g 10001 /opt/ticket-system/data/attachments
sudo install -d -m 700 /opt/ticket-system/backups /opt/ticket-system/exports
sudo git clone https://github.com/haier7611/msp-helpdesk.git /opt/ticket-system/releases/v1
sudo ln -sfn /opt/ticket-system/releases/v1 /opt/ticket-system/current
```

Use a tag or commit you have reviewed rather than an unpinned branch for anything real.

## 1. Write `.env`

```sh
sudo cp /opt/ticket-system/current/.env.example /opt/ticket-system/.env
sudo chmod 600 /opt/ticket-system/.env
```

Then edit it. Generate every secret with `openssl rand` and write it straight into the file with an
editor; never paste a secret into a command line, chat or ticket.

| Variable | Set it to |
| --- | --- |
| `POSTGRES_PASSWORD` | `openssl rand -hex 24` (the database owner role, used by Flyway) |
| `APP_DB_PASSWORD` | another `openssl rand -hex 24`, different from the one above (the api's restricted `ticketsys_app` role; Flyway sets its password on every start) |
| `TS_DATA_KEY` | `openssl rand -hex 32` (64 hex characters; encrypts MFA, mailbox and Microsoft secrets in the database) |
| `TS_DATA_KEY_ID` | leave `k1` |
| `TS_SETUP_TOKEN` | `openssl rand -hex 32` for the first start only (step 4) |
| `TS_PUBLIC_BASE_URL` | the public address, for example `https://helpdesk.example` (no trailing slash; links in emails point here) |
| `TS_SECURE_COOKIES` | `true` (always, behind HTTPS) |
| `ATTACHMENTS_DIR` | `/opt/ticket-system/data/attachments` |
| `WEB_BIND` / `WEB_PORT` | `127.0.0.1` and a free port, for example `8080` (see step 2) |
| `COMPOSE_PROFILES` | remove the line (it starts Mailpit, a test mail catcher) |
| `SMTP_*`, `MAIL_FROM_ADDRESS`, `MAIL_SUPPORT_NAME` | your SMTP server and sender, or leave `SMTP_HOST` empty and connect a mailbox in Admin later (step 5) |

**Placeholder guard.** `.env.example` ships values containing `REPLACE_ME`. The api refuses to
start while any guarded secret (`POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `SMTP_PASSWORD`,
`TS_SETUP_TOKEN`, `TS_DATA_KEY`) still holds such a placeholder, or `change-me` / `changeme`, and
names the variable at fault without logging its value. An empty `SMTP_PASSWORD` or
`TS_SETUP_TOKEN` is allowed.

**Keep `TS_DATA_KEY` safe.** Store a copy in your password manager, separate from the backups. If
it is lost, every agent must set up two-step sign-in again and every mailbox and Microsoft secret
must be entered again. Rotation: [runbooks/rotate-data-key.md](runbooks/rotate-data-key.md).

## 2. Put a TLS reverse proxy in front

The web container listens on `WEB_BIND:WEB_PORT` (default `127.0.0.1:80`). Run your proxy on the
same host and point it at `http://127.0.0.1:<WEB_PORT>`. If the proxy also listens on port 80 on
the host (most do, for certificate challenges and redirects), set `WEB_PORT` to another port such
as `8080`, or the two collide.

**Why `WEB_BIND=127.0.0.1` matters.** The web Caddy and the api trust `X-Forwarded-*` headers from
private addresses (`trusted_proxies static private_ranges` in `web/Caddyfile`, and Tomcat's
remote-IP handling in the api). That is what lets the api see the public `https` URL and the real
client address for rate limits and the audit log. It is safe only because nothing but your proxy
can reach the web container. Bound to `0.0.0.0`, any machine on the same private network could
connect over plain HTTP and claim any client address or scheme. Keep it on `127.0.0.1`, or behind
a firewall that admits only the proxy.

What the proxy must do:

- terminate TLS for your DNS name;
- set `X-Forwarded-Proto: https` and pass the original `Host`;
- overwrite or append to `X-Forwarded-For` with the address it saw, never pass a client's value on
  unchecked (the api uses the right-most address that is not private or loopback);
- accept request bodies of at least 25 MB (attachments are up to 20 MB each).

Caddy on the host:

```
helpdesk.example {
	reverse_proxy 127.0.0.1:8080
}
```

nginx on the host (with a certificate from certbot or similar):

```
server {
    listen 443 ssl;
    server_name helpdesk.example;
    # ssl_certificate / ssl_certificate_key ...
    client_max_body_size 25m;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
}
```

A tunnel (for example `cloudflared`) running on the host works the same way: point its ingress at
`http://127.0.0.1:8080`. No inbound port is then needed on the host.

## 3. Build and start

```sh
cd /opt/ticket-system/current
sudo APP_IMAGE_TAG=v1 GIT_SHA="$(git rev-parse --short HEAD)" \
  docker compose --env-file ../.env up -d --build --wait
```

The first build downloads dependencies and takes several minutes. On start, Flyway creates or
migrates the database schema before the api answers. Check:

```sh
curl -fsS http://127.0.0.1:8080/actuator/health      # {"status":"UP"}
curl -fsS https://helpdesk.example/api/v1/version    # name, version and git sha
```

`APP_IMAGE_TAG` names the images (`ticketsys-api:<tag>`, `ticketsys-web:<tag>`); a distinct tag per
release is what makes rollback quick ([09-operations.md](09-operations.md)).

## 4. Create the first admin

There is no default account. Once:

1. Read `TS_SETUP_TOKEN` from `/opt/ticket-system/.env` on the host.
2. Open `https://helpdesk.example/setup`, paste the token and enter your name, email and a
   password of 12 or more characters.
3. Sign in and set up two-step sign-in with an authenticator app when asked.

Setup refuses for good once any admin exists. Then remove the token from `.env` (or leave the line
empty) and apply it with `docker compose --env-file ../.env up -d --wait`.

## 5. Mail

Outbound mail goes through the first enabled mailbox that has outgoing (SMTP) settings, configured
in **Admin - Email and signature - Mailboxes**; without one, through the `SMTP_*` settings in
`.env`; with neither, mail is queued but not sent and the api logs a warning at start.

- Set up the support mailbox: [guides/admin-guide.md](guides/admin-guide.md) section 2, and for
  Gmail or Google Workspace [runbooks/connect-gmail.md](runbooks/connect-gmail.md).
- Check SPF, DKIM and DMARC of the sending domain cover your mail provider.
- Failure handling: [30-reliability.md](30-reliability.md) "Outbound mail" and "Inbound mail".

## 6. Optional: Sign in with Microsoft

Off by default; passwords and authenticator codes always work. To let agents and customers use
their Microsoft 365 accounts, follow
[runbooks/entra-app-registration.md](runbooks/entra-app-registration.md). Nothing goes into
`.env`: the details are entered in **Admin - Sign-in**.

## 7. Backups

Install the nightly backup timer and run a restore drill before you load real data:
[30-reliability.md](30-reliability.md) "Backups" and "Restore drill". Copy the backups off the
host.

## Checklist before real client data

- [ ] HTTPS works, `TS_SECURE_COOKIES=true`, `WEB_BIND=127.0.0.1`
- [ ] `.env` is mode 600 and owned by root; `TS_DATA_KEY` is stored separately
- [ ] `TS_SETUP_TOKEN` removed after the first admin
- [ ] Every admin has two-step sign-in
- [ ] Nightly backup runs, a restore drill has passed, backups are copied off the host
- [ ] Outbound mail sends from your real address; Mailpit (`COMPOSE_PROFILES`) is off
- [ ] An uptime probe watches `https://<your address>/actuator/health`
- [ ] Security notes read: [35-security.md](35-security.md) and [../SECURITY.md](../SECURITY.md)
