# Local development

How to run MSP Helpdesk on your own machine, load demo data and run the same checks as CI.

## Run it locally

You need **Docker** with the Compose plugin (v2.20 or later), **openssl** and **bash** (macOS or Linux; on Windows use WSL 2). No JDK or Node is needed on the host: everything builds inside containers.

```sh
git clone https://github.com/haier7611/msp-helpdesk.git
cd msp-helpdesk
scripts/dev-up.sh
```

On the first run `dev-up.sh`:

1. writes `.env` (mode 600) from `.env.local.example`, with fresh random secrets from `openssl rand` (database passwords, the encryption key `TS_DATA_KEY`, the one-time setup token `TS_SETUP_TOKEN`) and local-safe settings: plain http on `localhost`, cookies without the Secure flag, mail sent only to Mailpit. It never overwrites or edits an existing `.env`, and never prints a secret;
2. builds the images and starts the stack (`docker compose up -d --build --wait`): Postgres, the api, the web server and Mailpit. The first build downloads dependencies and takes several minutes; later starts take seconds;
3. prints the app URL, the Mailpit URL and the first-admin steps.

Then open **http://localhost:8080**. Every email the app sends (invitations, password resets, ticket replies) lands in Mailpit at **http://localhost:8025/mailpit/**; nothing leaves your machine.

### First admin

There is no default password. Once:

1. read the setup token from `.env`: `grep '^TS_SETUP_TOKEN=' .env`;
2. open http://localhost:8080/setup, paste the token and enter your name, email and a password of 12 or more characters;
3. sign in and set up two-step sign-in with an authenticator app when asked.

The token stops working once an admin exists. Without a browser, the same flow is `GET /api/v1/auth/csrf` (sets the `XSRF-TOKEN` cookie) then `POST /api/v1/auth/setup` with the header `X-XSRF-TOKEN: <cookie value>` and a JSON body `{"token": "...", "email": "...", "name": "...", "password": "..."}`; it answers 201.

### Demo data

```sh
scripts/dev-seed.sh        # or start with: scripts/dev-up.sh --demo
```

Loads synthetic example data: example client organisations with users and tickets in several states, agents and an admin, all made-up names on `.example` domains. It runs the api's own seeding command inside the api container. The demo accounts' passwords and two-step sign-in recovery codes go only to `demo-credentials.txt` (mode 600, git-ignored) in the repository root; nothing secret is printed. Running it again adds nothing twice and keeps the passwords; `--reset-passwords` gives every demo account a new one. A scripted tour of the demo data is in [demo-walkthrough.md](demo-walkthrough.md).

### Settings

Read only when `.env` is first written (delete `.env` and the volumes to start over):

| Variable | Default | What |
| --- | --- | --- |
| `TS_WEB_PORT` | 8080 | host port of the web UI |
| `TS_MAILPIT_PORT` | 8025 | host port of the Mailpit UI |
| `TS_DEV_PROJECT` | `ticketsys` | compose project name, container prefix and image tag; set another `ticketsys-...` name to run a second copy beside the first |

Example: `TS_WEB_PORT=9080 TS_MAILPIT_PORT=9025 scripts/dev-up.sh`. The script refuses a port that is already in use, and the two ports must differ. Both ports bind `127.0.0.1` only. After the first run, change settings by editing `.env`.

### Stop and clean up

```sh
scripts/dev-down.sh          # stop; the database, attachments and .env stay
scripts/dev-down.sh --wipe   # also delete this project's volumes and demo-credentials.txt
```

`--wipe` lists the volumes it will delete and asks you to type the project name. It removes only the compose project named in `.env`, and refuses when `.env` does not look like a local one (`TS_SECURE_COOKIES` not `false`). `.env` is kept, so the next `dev-up.sh` starts an empty database with the same settings and setup token.

### Production is different

`.env.local.example` is for a laptop only. A real server uses `.env.example`: HTTPS with `TS_SECURE_COOKIES=true`, a real SMTP server or mailbox, no Mailpit, attachments on a host path that is backed up. See [../deployment.md](../deployment.md) and [../30-reliability.md](../30-reliability.md).

## Run the CI checks locally

The same scripts CI runs, each in a pinned container (Linux host with Docker; they use ports 55500-55599 on `127.0.0.1` for test servers):

- `scripts/ci/conflict-markers.sh` - leftover merge-conflict markers
- `scripts/ci/api.sh` - Spotless check, compile, unit and Testcontainers integration tests (`scripts/ci/api.sh spotlessApply` formats; `scripts/ci/api.sh test --tests '*VersionControllerTest'` runs one class)
- `scripts/ci/web.sh` - npm ci, lint, typecheck, format check, tests, build, plus the Caddyfile and mail evidence container checks
- `scripts/ci/vuln-scan.sh` - Trivy over the lockfiles; fails on fixable HIGH/CRITICAL

Caches default to `~/.cache/ticketsys/` (override with `TICKETSYS_GRADLE_CACHE`, `TICKETSYS_NPM_CACHE`, `TICKETSYS_TRIVY_CACHE`). See [../20-test-strategy.md](../20-test-strategy.md) for what each level covers, and [../../CONTRIBUTING.md](../../CONTRIBUTING.md) before opening a pull request.

For the web UI with hot reload, run the stack with `scripts/dev-up.sh`, then in `web/` with Node 22: `npm ci` and `npm run dev` (it proxies `/api` to `http://localhost:8080`; with another web port set `TICKETSYS_API_URL=http://localhost:<port>`).
