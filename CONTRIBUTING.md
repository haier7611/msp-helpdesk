# Contributing

Thanks for taking an interest. Bug reports, fixes and documentation improvements are welcome. For a new feature, please open an issue first so the behaviour can be agreed before code is written - the product is specified in [docs/15-product-spec.md](docs/15-product-spec.md) and design decisions are recorded in [docs/10-decision-log.md](docs/10-decision-log.md).

Security problems go through private reporting, never a public issue: see [SECURITY.md](SECURITY.md).

## Getting started

You need Docker (with Compose v2.20+), bash and openssl. No JDK or Node is needed on the host: builds and tests run in pinned containers.

```sh
scripts/dev-up.sh --demo     # build, start and load synthetic demo data
scripts/dev-down.sh          # stop (keeps data); --wipe removes it
```

Full details: [docs/guides/local-development.md](docs/guides/local-development.md). The code layout and the main rules are in [docs/guides/engineer-guide.md](docs/guides/engineer-guide.md) and [docs/40-architecture.md](docs/40-architecture.md).

## Build and test

The same scripts run in CI (`.github/workflows/ci.yml`):

| What | Command |
|---|---|
| api: format check, compile, unit and Testcontainers tests | `scripts/ci/api.sh` |
| api: fix formatting | `scripts/ci/api.sh spotlessApply` |
| api: one test class | `scripts/ci/api.sh test --tests '*VersionControllerTest'` |
| api: after changing dependencies | `scripts/ci/api.sh dependencies --write-locks` |
| web: install, lint, typecheck, format check, tests, build | `scripts/ci/web.sh` |
| dependency scan (fails on fixable HIGH/CRITICAL) | `scripts/ci/vuln-scan.sh` |

Working on the web app by hand (in `web/`, Node 22): `npm ci`, `npm run dev` (proxies `/api` to `localhost:8080`), `npm run lint`, `npm run typecheck`, `npm run format`, `npm test`.

## Rules the code relies on

- **Organisation isolation.** Every query on client-owned data is scoped by organisation in the service layer and in the SQL itself (`ScopeSql`), and every write that finds no visible row answers 404. Repositories for client data live in `..clientdata..` packages and every method takes an `AccessScope`; `ArchitectureTest` enforces this. Add a test proving another organisation is denied for anything new.
- **Internal notes** never leave agent-only endpoints.
- **Access rules are enforced on the server**, never only in the UI.
- **Flyway migrations are append-only**: never edit a merged migration; add a new one.
- **Ticket references** are generated only by the reference module (`reference` package).
- **Audit events**: one enum per feature package implementing `audit.AuditEvent`, with allow-listed detail keys on `@AuditDetailKeys`. Event names are stored, so never rename one.
- **No real data**: fixtures, seeds, tests and docs use synthetic names and `.example`, `.test` or `.invalid` domains only. Never commit secrets or `.env` files.

## Pull requests

- Branch names `feat/...`, `fix/...`, `chore/...`, `docs/...`; [Conventional Commits](https://www.conventionalcommits.org/) messages.
- A behaviour change updates the product spec (and the diagrams or rules doc it touches) in the same pull request.
- Add a changelog fragment in `changelog.d/` - see [changelog.d/README.md](changelog.d/README.md). Do not edit `CHANGELOG.md`.
- CI must be green. Describe what changed, the tests you added, and include screenshots for UI changes.

## Style

- Java: google-java-format through Spotless (`scripts/ci/api.sh spotlessApply`). Web: ESLint and Prettier.
- User-facing text: British English, plain words, a hyphen (-) rather than an em dash.
- Accessibility: real buttons and labels, WCAG AA contrast, target sizes from the design tokens (`--control-size`, `--choice-size`), never below 24 px.

## Developer Certificate of Origin

Contributions are accepted under the [Developer Certificate of Origin 1.1](https://developercertificate.org/). Sign off every commit to confirm you wrote the change or have the right to submit it under the project's licence:

```sh
git commit -s -m "fix: describe the change"
```

This adds a `Signed-off-by: Your Name <you@example.com>` line. By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
