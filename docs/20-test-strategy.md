# Test strategy

One page. What we test, at which level, and the bar every change must meet. Update it when the shape of testing changes, not per feature.

## Levels (what runs today)

- **Unit** - pure logic with no I/O (SLA calendars and calculator, inbound mail parser, references, TOTP, encryption): JUnit, including seeded property tests and hand-calculated fixtures. Web: Vitest + Testing Library, with axe-core accessibility checks on every page.
- **Integration** - the Spring app against a real `postgres:16-alpine` via Testcontainers (never H2), Flyway included, connecting as the restricted `ticketsys_app` role. **GreenMail** is the in-process SMTP/IMAP server for mail; MockMvc drives the real security chain and sessions.
- **Architecture rules** - ArchUnit (`api/src/test/java/com/ticketsystem/api/architecture/ArchitectureTest.java`): `AccessScope` and organisation isolation (client-data repositories only in `..clientdata..`, no JDBC elsewhere, `AccessScope.system` callers), internal-note separation from client-visible code, and the reference module as the only place that formats or parses references. Negative fixtures prove each rule fails when broken.
- **Security regression** - `AuditRegressionTest` replays hostile inputs found in security reviews (header and quote DoS, oversized mail). Every timeout in it is the shared 5 s limit (`ATTACK_LIMIT`), so a busy CI runner does not cause flakes while a real regression (tens of seconds) still fails. The go-live security suite (tests 1-5, tagged `@SecurityGoLive`) runs in every build.
- **Real-container checks** - `scripts/ci/caddy-check.sh` runs the real Caddy image against the Caddyfile (proxy routes, headers, the optional Mailpit route) and `scripts/ci/mail-evidence-check.sh` proves the mail evidence renderer's containment with hostile HTML.
- **Real stack** - the critical journeys on a local stack (`docker compose up -d --build --wait`, Mailpit receiving all mail, [local development](guides/local-development.md)), then a browser check. Required for anything a user touches before it is called done.

## CI (GitHub Actions)

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`, on GitHub-hosted `ubuntu-latest` runners. Every tool runs in a pinned container started by the scripts in `scripts/ci/`, so the same commands work on any Linux host with Docker.

- **conflict-markers** (`scripts/ci/conflict-markers.sh`) first; then in parallel:
- **api** (`scripts/ci/api.sh spotlessCheck build`): format check, compile, unit and Testcontainers integration tests in a pinned Temurin 21 container. Test reports are uploaded when it fails.
- **web** (`scripts/ci/web.sh`): `npm ci`, lint, typecheck, format check, Vitest, build in a pinned Node 22 container, then the Caddyfile and mail evidence real-container checks.
- **vuln-scan** (`scripts/ci/vuln-scan.sh`): Trivy over `api/gradle.lockfile` and `web/package-lock.json`; fails on fixable HIGH/CRITICAL ([security](35-security.md)).

Least privilege (`permissions: contents: read`, checkout without persisted credentials), every action pinned by full commit SHA with a version comment, and Gradle, npm and Trivy DB caches through `actions/cache` (the scripts take their cache paths from `TICKETSYS_GRADLE_CACHE`, `TICKETSYS_NPM_CACHE`, `TICKETSYS_TRIVY_CACHE`; without them they use `${XDG_CACHE_HOME:-~/.cache}/ticketsys/`). A new push cancels the older run for the same pull request. Dependabot (`.github/dependabot.yml`) opens weekly grouped minor/patch updates for Gradle, npm, Docker base images and the actions. CI builds and tests only; it does not deploy.

## Build speed

One Postgres Testcontainer per test JVM (`TestcontainersConfiguration.sharedContainer()`, a singleton started on first use and reused by every Spring context and by the tests that start the app themselves), and GreenMail in-process (no container). So no test relies on a fresh database: each test makes its own data with unique values (organisation prefixes from a counter, `uniqueEmail`, UUID names) and asserts on that data. The exception is tests that run installation-wide jobs with the clock moved weeks ahead (the SLA minute job and daily digest in `SlaTestBase`): in the shared database those jobs would pick up every ticket the other classes left behind, so `SlaTestBase` asks for its own database in the same container with `@TestPropertySource(properties = TestcontainersConfiguration.DATABASE_PROPERTY + "=ticketsys_it_sla")`. Use the same for any new class whose job scans all tickets. Test users share one Argon2id hash of the test password (`AuthIntegrationTestBase.goodPasswordHash()`), computed once per JVM; sign-in still verifies it with the real encoder. Tests run in one Gradle fork (`maxParallelForks = 1`): a second fork would need its own Postgres and ports and about 1.5 GB more memory.

## What must have a test

- Every business rule in the spec and the [API and business rules](07-api-and-business-rules.md).
- Every acceptance-checklist item in the spec.
- Every bug fix: write the failing regression test first, then the fix.
- Every query on client-owned data: a test proving cross-organisation access is denied.

## The quality bar

- CI is green: `./gradlew build` (unit + Testcontainers integration) and `npm ci && npm run lint && npm run typecheck && npm test && npm run build`, no skipped tests merged.
- New or changed logic is covered, including its **failure paths**: offline, timeouts, partial failure, double submits, retries, empty and huge inputs.
- A test would **fail without the change** - it proves the behaviour, not just that code runs.
- No coverage-percentage target: cover what matters, not framework internals or generated code.
- **No sleeps in tests.** A test never waits a fixed time for something to happen: it waits on an explicit signal (`CountDownLatch`, a future, a test hook such as `InboundAttachmentWorker.awaitIdle`), polls a condition with a deadline (Awaitility `await().atMost(...).until(...)`, on the test classpath through the Spring Boot test starters; Testing Library `waitFor`/`findBy*` in web tests) or moves time with the `MutableClock` bean / Vitest fake timers. A negative check ("nothing changed") first waits for the thing that could have changed it to finish. The only `Thread.sleep` left in `api/src/test` is the simulated network latency inside `InboundMailSpeedBenchmarkTest`'s proxy, which is the behaviour under test, not a wait.

## How to run

On any Linux host with Docker (the scripts need `docker`, `ss`, `flock` and `stat`; no JDK or Node on the host):

- Full api suite (what CI runs): `scripts/ci/api.sh` - Gradle `spotlessCheck build` in a pinned `eclipse-temurin:21-jdk` container. Integration tests start a real `postgres:16-alpine` with Testcontainers, bound to `127.0.0.1` on a free port in 55500-55599 and removed by name afterwards. The script also reserves a few ports for in-process test servers (`TICKETSYS_IT_EXTRA_PORTS`: GreenMail IMAP/SMTP, the rejecting and closed-port SMTP servers, the OAuth token server and the fake Microsoft identity platform) and holds a per-port `flock`, so parallel builds on one host never pick the same port.
- Full web suite (what CI runs): `scripts/ci/web.sh` - `npm ci && npm run lint && npm run typecheck && npm run format:check && npm test && npm run build` in a pinned Node 22 container.
- Dependency vulnerabilities (what CI runs): `scripts/ci/vuln-scan.sh`; exceptions only in `.trivyignore` ([security](35-security.md)).
- Go-live security suite: `scripts/ci/api.sh securityGoLive` - every class tagged `@SecurityGoLive`; `SecurityGoLiveInventoryTest` fails if a test number loses its classes.
- Host scripts: `BackupScriptsIntegrationTest` copies `scripts/backup/ticketsys-restore-drill.sh` and `ticketsys-export.sh` (POSIX sh) into the Testcontainers Postgres and runs them there with `TS_PG_LOCAL=1`; `scripts/ci/api.sh` mounts `scripts/` read-only at `/scripts`.
- One test: `scripts/ci/api.sh test --tests '*VersionControllerTest'` / `npx vitest run src/App.test.tsx`.
- Integration tests share one Spring context: import `TestcontainersConfiguration` (real Postgres, never H2 or mocks of the database). It connects the app as the restricted `ticketsys_app` role and Flyway as the owner, like production; use `TestcontainersConfiguration.ownerJdbc()` for checks the app role may not do. Auth flows are tested through MockMvc with the real security chain and Spring Session JDBC (`auth/Browser` keeps cookies and sends the CSRF header like the web app); time-based rules use the `MutableClock` bean.
- Mail: integration tests run **GreenMail** in-process, stopped and restarted to test retries; a tiny rejecting SMTP server covers the 5xx path. Log-capture tests (`OutputCaptureExtension`) prove no token, password, address or body is logged.
- Real-stack check: see [local development](guides/local-development.md); the `dev` profile starts Mailpit, which receives all outbound mail.
