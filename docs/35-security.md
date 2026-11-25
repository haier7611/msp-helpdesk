# Security and privacy

The threat model, the controls per area, the risks we accept, what third-party code we ship, and what personal data we hold. The diagrams for roles, the organisation wall and step-up are in [diagrams/08-security-and-permissions.md](diagrams/08-security-and-permissions.md).

## Reporting a vulnerability

Please do not open a public issue. Use GitHub private vulnerability reporting on this repository ("Security" tab, "Report a vulnerability"); details are in [SECURITY.md](../SECURITY.md).

## Accepted risks

These are deliberate trade-offs, stated openly so operators can decide whether they fit. Each is also noted in the section it belongs to.

1. **An invitation link alone activates an account** ([ADR-046](10-decision-log.md)). Agent and admin invitations carry no second factor (the 6-digit code was removed). Anyone who obtains the invitation email within its 48-hour life can set the password and activate the account, possibly an admin one. Kept: step-up to send or resend, single-use hashed link, 48 h expiry, re-invite voids the old link, admin can revoke, rate limits, audit events; the new account must still enrol two-step sign-in, and the email says not to forward it.
2. **Account mail travels through the connected support mailbox** ([ADR-046](10-decision-log.md)). Invitations, resets, sign-in links and security notices are sent with the mailbox's own SMTP sign-in, so its provider, and anyone who controls its SMTP settings or Sent folder, can see live sign-in links; a failing mailbox delays them (no fallback to the server-wide sender). Mitigations: links are single use, hashed and short-lived; sensitive bodies are blanked in the outbox after sending; changing the mailbox's SMTP settings needs step-up and is audited; admins can still sign in with a password to fix a broken mailbox.
3. **A genuine client can copy outside addresses onto their own ticket** ([ADR-043](10-decision-log.md)). The To/Cc addresses of a verified (DMARC pass) client email become confirmed participants who then receive that ticket's mail. A client, or an attacker who controls the client's mailbox or domain, could add any outside address, as they could by forwarding. Agents see every addition in the timeline and can remove it.
4. **Another organisation's contact can be copied on a ticket as an outside address** ([ADR-035](10-decision-log.md)). After the agent's explicit "Add anyway?", they are handled exactly like an unknown outside address: emailed on that ticket, never linked to their account, replies thread onto that ticket only. A small residual signal remains: email intake silently skips another organisation's contact found on a client's CC line, while an unknown address is added, so an absent CC could hint that the address has an account elsewhere.
5. **Agents can learn that an address has an account somewhere.** Creating a contact for an address that already has any account answers 409 with the same message as the admin invite. Agents are trusted MSP staff.
6. **Microsoft 365 "(none)" authserv-id trust.** Exchange Online omits the authserv-id on `Authentication-Results`; trusting id-less headers is an explicit per-mailbox setting. Mail that reaches the mailbox without Exchange stamping it (inbound connectors or rules that bypass filtering, hybrid routes) could carry a forged header that would then be trusted. Tenants with such routes should not use this setting.
7. **Admin test connections can probe internal hosts.** A mailbox test connection is an admin-only network call to an admin-chosen host; its error categories can tell an admin whether an internal host/port answers TLS, and an OAuth2 client secret is sent to the https token URL the admin entered.
8. **Single-instance, in-memory rate limits.** Sign-in back-off, per-IP caps, per-agent message caps and API key limits live in memory: they reset on restart and are not shared across instances. v1 runs one api instance.
9. **CI build containers get the Docker socket.** The api build needs Testcontainers, so its container gets the host Docker socket and host network: root-equivalent on the CI host. On GitHub Actions this is a throwaway runner.
10. **No virus scanning of attachments.** Files are download-only, never executed or rendered by the server, and non-images are always served as `application/octet-stream` downloads.
11. **The full export is not a sanitised dataset.** It keeps password hashes, hashed recovery codes, API key hashes and secrets encrypted with `TS_DATA_KEY` so users keep their sign-in on a new host. It is handled like a backup.
12. **Audit email scrub needs the erase right.** The app role can erase a user (its job), so an attacker running SQL as that role could erase an account to scrub that account's address from the audit log. The erase is itself visible (`erased_at`, `user_erasure`, `AUDIT_EMAIL_SCRUBBED`).

Smaller known limits (no per-customer cap on portal tickets, no per-user storage quota, no IP allow list per API key and others) are listed at the end of each area below.

## Threat model (STRIDE, per element)

STRIDE = Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege. Re-run this pass whenever the data-flow diagram changes.

### Authentication

Elements: browser -> Caddy -> api (`/api/v1/auth`, the three role trees), api -> Postgres (sessions, users, tokens, audit), api -> outbox -> mailbox (link emails).

| Element | Threat (STRIDE) | Mitigation | Status |
|---|---|---|---|
| Browser -> api sign-in | S: password guessing, credential stuffing | Argon2id; 12+ characters and an offline breached-password list; per-account+IP exponential back-off (no hard lock); account-wide back-off across addresses from the 11th failure, skipped for a browser the account has used before; per-IP caps (Bucket4j); every failure and throttle audited; TOTP for agents | done |
| Browser -> api MFA step | S: stolen password, code guessing, code replay | TOTP (RFC 6238, +-1 step) with the last accepted step stored and a conditional UPDATE so a code works once; same back-off on TOTP and recovery codes; the MFA step must finish within 10 min; below FULL only `/me`, sign-out and the MFA endpoints answer | done |
| MFA secrets at rest | I: a database dump reveals TOTP seeds | AES-256-GCM with `TS_DATA_KEY` from the environment, per-value key id, user id as associated data; recovery codes 60-bit, SHA-256 only; `rotate-data-key` re-encrypts under a new key | done |
| Trusted / known device cookies | S: a stolen cookie skips TOTP | Trust lives in its own `__Host-TS_TRUST` cookie (30 days exactly), separate from the known-device marker `__Host-TS_DEVICE`; HttpOnly, Secure, SameSite=Lax; 256-bit tokens, SHA-256 stored; checked only after a correct password and only for the same user; revoked on password, MFA, role change and deactivation ([ADR-019](10-decision-log.md), [ADR-031](10-decision-log.md)); never satisfies step-up | done |
| Sensitive admin actions | E: hijacked or unattended session | `@RequiresStepUp`: fresh TOTP or recovery code in the last 10 min on this session; 403 `step_up_required` | done |
| Live sessions after a security change | E: deactivated or demoted user keeps access | `security_version` bump deletes the user's other session rows in the same transaction; a filter compares version, role and status on every request before the context loads | done |
| Browser -> api sign-in | I: account enumeration | Same 401 text for unknown/wrong/inactive; dummy Argon2 check when no hash; reset and magic-link requests always 202 with the same body | done (residual: request-path timing differs by one database insert for real accounts) |
| Session cookie | S/T: theft, fixation, cross-site use | Server-side Spring Session JDBC; `__Host-TS_SESSION`, HttpOnly, Secure, SameSite=Lax; session id changed at sign-in; 8 h idle, 24 h absolute | done |
| State-changing requests | T: CSRF | CookieCsrfTokenRepository SPA pattern on every POST including sign-in/out; cookie `__Host-XSRF-TOKEN` so a sibling subdomain cannot plant it; token rotated at sign-in | done |
| Link tokens (invite, reset, magic) | S/I: guessing, replay, leakage via logs or Referer | 256-bit random, SHA-256 stored, single use (row lock + `used_at`), 15 min / 48 h / 7 days, newer voids older, token only in the URL fragment and POST body, outbox body blanked after send | done |
| Agent invitation | S: mailbox compromise alone creates an agent | See accepted risk 1. Step-up to send or resend (on both `/api/v1/admin/supporters/invitations` and `/api/v1/admin/invitations` for agent/admin roles), single-use hashed link, 48 h expiry, re-invite voids the old link, revoke, rate limits, audit events, two-step sign-in still required | accepted risk |
| First-admin setup | E: takeover of a fresh install | `TS_SETUP_TOKEN` (32+ characters) compared in constant time, never logged; refused once any admin exists; advisory lock against races; attempts capped and audited | done |
| Role trees | E: customer or agent reaching admin/agent data | URL-level gates before routing (`/portal` customer, `/agent` agent+admin, `/admin` admin) plus FULL auth level; deny by default elsewhere | done |
| Audit log | R/T: deleting or editing evidence | App connects as `ticketsys_app` with INSERT/SELECT only on `audit_event`; field allow list keeps secrets out | done |
| Client IP for caps and audit | S: spoofed X-Forwarded-For | Tomcat RemoteIpValve: right-most address that is not a private/loopback proxy; the api has no published port. The TLS edge in front must append X-Forwarded-For | done |
| Auth endpoints | D: flooding Argon2 or mail | Per-IP caps, 128-character password limit before hashing, 3 mails per address per 15 min | done (in memory, single instance) |

### Organisations and client data

Elements: api -> Postgres (organisations, prefixes, counters, references, domains, user access), the agent and admin organisation endpoints.

| Element | Threat (STRIDE) | Mitigation | Status |
|---|---|---|---|
| Client-owned data queries | I/E: one client's data read by another client or by an agent not assigned to it | Request-scoped `AccessScope` built from the user's current row; every `..clientdata..` repository method takes it and applies it in SQL; ArchUnit fails the build otherwise, and also if any `*Repository` lives outside `clientdata`, any class outside `clientdata` and a named allow-list uses JDBC, or a non-job package creates a SYSTEM scope (negative fixtures prove each rule bites); controllers cannot reach repositories directly | done |
| Client-data writes | T/E: a write trusting an organisation id the caller supplied | The writing statement itself carries the scope condition (`ScopeSql.ticketVisible`, `ScopeSql.ticketInOrganisation`, `ScopeSql.organisationOrTriage`); no visible row answers 404 (`ScopeBoundWriteIntegrationTest`) | done |
| Organisation ids in URLs | I: probing ids to learn which clients exist | Other organisations' ids answer 404 with the same body as a missing id | done |
| Organisation management | E/T: an agent creating, renaming or deleting organisations or editing verified domains ([ADR-022](10-decision-log.md)) | `/api/v1/admin` gate plus `requireAdmin` in the service and in every write method of the repositories; handler-mapping walk test | done |
| Verified domains | S/I: a public mail domain or another client's domain routing strangers' mail into a client's tickets | Public-domain list (and subdomains) refused; the domain is the table's primary key, so one organisation only; edits audited | done (list maintained by hand) |
| Ticket numbers | T: duplicate or reused references | Counter row locked by `UPDATE ... RETURNING` in a `MANDATORY` transaction, never `max()+1`; unique (prefix, number); concurrency test with 8 threads | done |
| Prefixes | S: a new organisation taking a retired prefix so old emailed references land with it | Unique index on `upper(prefix)` across active, retired and reserved rows; retired rows never deleted while references exist | done |
| Sessions after an access change | E: a removed or narrowed user keeps old access | Changes bump `security_version`; `AccessScopeFilter` ends any session whose version, role or status no longer matches (401), and the scope is rebuilt from the database on every request anyway | done |
| SYSTEM scope | E: a job reading every organisation by mistake | Only via `AccessScope.system(reason)`, which logs the reason, and only from job/poller packages; not reachable from any endpoint | done |

### Tickets, comments and attachments

Elements: browser -> api (`/api/v1/agent/tickets/**`, `/api/v1/agent/attachments/**`, `/api/v1/portal/tickets|attachments|request-types`), api -> Postgres, api -> BlobStore (attachment files on the `/data/attachments` volume).

| Element | Threat (STRIDE) | Mitigation | Status |
|---|---|---|---|
| Ticket, comment, note, link, participant, history and attachment queries | I/E: another client's tickets read or changed | Every repository in `ticket.clientdata` applies the `AccessScope` in SQL; 404 identical to a missing id; isolation tests over every agent ticket endpoint for a restricted agent, admin and customer | done |
| Internal notes | I: a note, its images or files reaching a client | Separate `internal_note` table and types; the public read model and attachment listing exclude note files in SQL; ArchUnit: portal/public packages may not depend on note, system-entry, history or agent types; customers have no download route at all | done |
| Attachment download | I: guessing ids; T: stored XSS via HTML/SVG served inline | Random UUID ids; per-request attachment -> comment -> ticket -> organisation check; non-images always `application/octet-stream` + `Content-Disposition: attachment`; `nosniff`, `Content-Security-Policy: sandbox; default-src 'none'`, `Cache-Control: private, no-store`; SVG refused on upload; Caddy keeps the api's CSP on these responses | done |
| Images | I: EXIF/GPS leak; D: decompression bombs | Re-encoded with ImageIO (no metadata written); dimensions read before decoding, over 25 MP or 12,000 px a side refused; at most two decodes at a time | done |
| Uploads | D: disk/memory exhaustion | 20 MB per file, 20 files per comment, pending uploads expire after 24 h | done (no per-user quota) |
| Pending uploads | E: attaching or reading someone else's upload | A pending file is visible and bindable only by its uploader; bound files cannot be bound again, so a note's image cannot be moved to a public reply | done |
| Rich text | T: script injection via composer HTML | OWASP sanitiser on save with the one allow list; DOMPurify on display; `img` only `attachment:<id>` of the same comment | done |
| Ticket moves | I: participants of one client kept on another client's ticket | Customer participants of other organisations removed, email-only participants need re-confirmation, links to the old organisation removed, assignee cleared if they lose access; audit `TICKET_ORGANISATION_CHANGED` | done |
| Reply CC check | I: an agent learning that an address is registered with another client | Another organisation's contact gets exactly the same answers as an unknown outside address; once confirmed they are an outside participant of that ticket only (accepted risk 4); CC checks capped at 60 a minute per agent | done |
| Public replies and notes from the agent UI | D/abuse: a runaway script or stolen agent session flooding tickets or relaying mail | One per-agent cap shared by replies and notes, default 30 a minute and 300 an hour (`app.rate-limits.agent-messages`); over it 429 with Retry-After and nothing stored | done (in memory, single instance) |
| Domain events | I: content leaking to listeners | Events carry ids and enums only; consumers re-read through a scope; delivered after commit only | done |

### Microsoft sign-in

Elements: browser -> Microsoft identity platform -> browser -> api callback; api -> Microsoft token endpoint and signing keys; api -> Postgres (`entra_settings`, `external_identity`); the admin settings endpoints.

| Element | Threat (STRIDE) | Mitigation | Status |
|---|---|---|---|
| Account lookup from a Microsoft token | S: the "email claim" takeover (any tenant sets its email to a victim's) | Users are found only by (`tid`, `oid`), never by email. Agents are never linked at sign-in. Customer auto-link needs `tid` = the organisation's tenant, `xms_edov` = true, not a guest, email equal to the customer's and its domain verified for the organisation | done |
| Tenant allow list | E: a customer of Acme Dental signing in through Northwind Logistics' tenant; an agent through a customer tenant | Agent link and sign-in only from the MSP tenant (only `acct=0` counts as a member, so a missing `acct` fails closed); customers only from their organisation's tenant; re-checked at every sign-in | done |
| MFA claim | S/E: a foreign tenant's `amr=mfa` skipping the agent's TOTP | `amr` containing `mfa` counts only when `tid` is the MSP tenant; Entra MFA never counts as step-up | done |
| ID token | S/T: forged, expired, replayed or misaddressed token | RS256 only, Microsoft's published keys; `aud` exactly the client ID; `iss` exactly `{authority}/{tid}/v2.0`; authority must be `https://` (plain HTTP only to loopback behind a test-only property); `exp`/`nbf`/`iat` with 60 s skew; nonce from the session; personal accounts refused; each failure audited | done |
| Callback | S/T: login CSRF, state replay, code interception | State and nonce (256-bit) and a PKCE S256 verifier in the server-side session, spent on first use, 10 minutes; the session id changes at sign-in | done |
| Client secret | I: dump, API response or log revealing it | AES-256-GCM with `TS_DATA_KEY`; write-only; masked in `toString`; errors log the HTTP status and an `AADSTS` code only | done |
| Settings changes | E/R: a hijacked admin session pointing sign-in at another app or tenant | Step-up on every change; switch on only after a passed Test sign-in of the exact settings version; changing client ID or MSP tenant switches it off; audited with field names only; the authority is a server setting, not an admin one | done |
| Link and unlink | E: someone at an unattended session linking their own Microsoft account | FULL session only; step-up for agents; one link per user and per (`tid`, `oid`); audited. Customers link and unlink without re-authentication (customers have no TOTP) | done (known limit) |
| Microsoft outage | D: sign-in blocked | 10 s timeouts; passwords always work | done |
| Secret expiry | D: SSO outage when the secret lapses | Admin enters the expiry date; banner across Admin from 30 days before | done (no expiry email yet: the service has a `dueExpiryWarning()` hook that nothing calls) |

### Customer portal

| Element | Threat (STRIDE) | Mitigation | Status |
|---|---|---|---|
| Customer API surface | I: a customer reading any ticket content or SLA data | Raise-only ([ADR-018](10-decision-log.md)): exactly five routes under `/api/v1/portal` - `POST tickets`, `GET request-types`, `POST attachments`, `GET` and `PATCH account`; none returns ticket content. `PortalIntegrationTest` fails if any other portal route appears and checks every response for ticket, comment, status, priority, SLA and URL fields | done |
| New ticket form | E/T: raising for another organisation, or setting priority/status/reporter | The request record has no such fields; organisation and reporter come from the `AccessScope`; the Alert type is invisible and refused for customers | done |
| Double submit | D/T: duplicate tickets on retry | One random idempotency key per form; `portal_submission (user_id, idempotency_key)` unique, claimed with `ON CONFLICT DO NOTHING` | done |
| My account | E: changing email, role or another user | `PATCH` takes only `name` for the scope's own user id | done |

### Integrations API

Elements: monitoring tool -> api (`POST /api/v1/integrations/tickets`, own stateless security chain), admin -> api (`/api/v1/admin/organisations/{id}/api-keys`), api -> Postgres.

| Element | Threat (STRIDE) | Mitigation | Status |
|---|---|---|---|
| API key | S: guessed or stolen key | 32 random bytes plus an 8-character visible id; only SHA-256 of the whole key stored, compared in constant time (dummy compare for unknown ids); shown once (`no-store`); optional expiry; revoke takes effect on the next request; 30 bad-key calls a minute per address then 429 | done |
| Integrations tree | E: a cookie or another credential reaching it; CSRF | Separate `SecurityFilterChain` matched first: stateless, CSRF off because no cookie authenticates it; only `POST /api/v1/integrations/tickets`, everything else denied; a session cookie gets 401 | done |
| Key scope | I/E: reading tickets, writing another organisation | `AccessScope.apiKey(key, org)` only; organisation never read from the body; no read route; the key works nowhere else | done |
| Flooding | D: a noisy monitor filling the queue | 60 requests a minute per key, 256 KB body cap, `externalId` dedup serialised with an advisory lock | done (in memory, single instance) |
| Alert content | T: script injection | Title stored as plain text; description through the rich-text sanitiser | done |
| Key management | R/E: unaudited key creation | Admin tree only, step-up on create, `API_KEY_CREATED`/`API_KEY_REVOKED` audit events with ids only | done |

## Controls by area

Concise notes per feature area. Code paths are under `api/src/main/java/com/ticketsystem/api/` unless stated.

### Network trust

- The web container binds `127.0.0.1` by default; only the TLS edge in front should reach it. That is what makes it safe for Caddy (`trusted_proxies private_ranges`) and the api to trust `X-Forwarded-*`. The api and Postgres publish no ports.
- Mailpit (`dev` profile only): its SMTP port is reachable only from the api on the internal `backend` network; its UI binds `127.0.0.1`. Mail in it holds live sign-in links, so never expose the UI. An optional `/mailpit/` route through Caddy exists only with a bcrypt basic-auth hash set (404 otherwise), checked in CI by `scripts/ci/caddy-check.sh`.

### Core auth and sessions

- Spring Session JDBC, principal name = user id (no email in the session table). `TS_SECURE_COOKIES=false` drops the `__Host-` prefix and Secure flag for plain-http local runs only.
- Passwords: Argon2id (16-byte salt, 32-byte hash, 1 lane, 19 MiB, 2 iterations - OWASP minimum), re-encoded at sign-in if parameters rise.
- Link tokens (`auth_token`): agent invitation 48 h (no code, accepted risk 1), customer invitation 7 days, password reset 15 min, magic link (customers only) 15 min. All 32 random bytes, SHA-256 stored, row-locked and spent in the acting transaction; mails marked sensitive (never sent after expiry, bodies blanked).
- Database roles: Flyway runs as the owner; the api runs as `ticketsys_app` (CRUD on application tables, INSERT/SELECT only on `audit_event`, nothing on `flyway_schema_history`). Its password is set from `APP_DB_PASSWORD` with client-side SCRAM, so no plain password reaches SQL text or the Postgres log.
- Secrets: `TS_SETUP_TOKEN` read only by the setup service, hashed in memory, never logged; `.env.example` placeholders are refused at start-up; Spring Boot's default generated user is switched off.

### MFA, step-up, devices and sessions

- Auth levels: a customer is FULL after the password; an agent is `MFA_ENROLMENT_REQUIRED`, `MFA_REQUIRED` or FULL (trusted device). Raising a session to FULL changes the session id again.
- TOTP in plain JDK code (`auth/mfa/Totp.java`, checked against the RFC test vectors): HMAC-SHA1, 6 digits, 30 s, 160-bit secret. QR drawn from server-made SVG path data that the web checks contains only path commands and digits.
- `TS_DATA_KEY` (64 hex characters) encrypts secrets; retired keys stay readable through `TS_DATA_OLD_KEYS`; `rotate-data-key` re-encrypts ([runbook](runbooks/rotate-data-key.md)). Losing the key means every agent enrols again; keep it with the host's other secrets, never in the repository or next to database backups.
- Recovery codes: 10 per enrolment, 60 bits each, SHA-256 of `rc:<user id>:<code>`, each works once; accepted at sign-in and for step-up; regenerating needs step-up.
- Throttling: `(account, IP)` back-off from the 3rd failure, account-wide from the 11th unless the request carries a known-device cookie of that account; 1 s doubling to 15 min. The same keys guard TOTP, recovery and step-up checks.
- Sessions list shows only the caller's sessions, identified by a hash of the session id, never the cookie value.
- Admin actions (step-up): sign out everywhere, reset MFA. Break-glass: `docker compose exec api java -jar /app/app.jar reset-mfa --email=<agent>`; host shell access is the authorisation; audited with method `break_glass`.
- Security notices (via the outbox): MFA enrolled, MFA reset, new-device sign-in, role change, deactivation, exemption granted.

### Two-step exemption per engineer ([ADR-031](10-decision-log.md))

- Admin only, step-up, never for admins (refused for any role but `AGENT`, any status but `ACTIVE`, or yourself). Promotion, deactivation or any other role/status change deletes it (a database trigger covers paths outside the service).
- Time-limited: end time required, at most 30 days ahead, repeated as a database `CHECK`. No permanent and no organisation-wide exemption.
- Ends without a job: checked at every sign-in and, for sessions FULL only because of the exemption, on every request; an expired exemption invalidates those sessions. "End exemption now" ends every session of the engineer.
- While exempt, step-up takes the password behind the same back-off; refused for everyone else.
- The admin's free-text reason is kept out of the append-only audit log (it may describe personal circumstances) and is deleted with the exemption. The engineer gets a notice and an in-app banner.

### Agents and My settings

- Management under `/api/v1/admin/supporters/**`; My settings under `/api/v1/agent/me/**` acts only on the scope's own user.
- Last-admin guard: role change and deactivation lock every active admin row in id order (`SELECT ... FOR UPDATE`), so two admins demoting each other at once cannot both succeed (tested with real lock waits).
- Step-up on role change and on sending or resending any invitation (otherwise a hijacked session could invite an attacker as ADMIN).
- Deactivation deletes every session, clears trusted devices, revokes unused links and blocks sign-in; nothing else is deleted.
- Signature fields are one-line plain text, HTML-escaped once by `SignatureRenderer`; the web preview rebuilds an allow-listed DOM rather than using `innerHTML` (the CSP blocks inline styles).
- Profile photo: 5 MB raw body cap, type from magic bytes (PNG, JPEG, WebP), header dimensions checked first (6000 x 6000, 25 MP), re-encoded to a 256 x 256 PNG; served only to its owner with `no-store`; never in emails.

### Tickets, rich text and the agent UI

- Rich-text allow list (one list, used by the inbound mail converter, the save-time sanitiser and DOMPurify on display): elements `p br strong em u s ul ol li blockquote pre code h2 h3 a img table thead tbody tr th td`; attributes `a href`, `img src`, `img alt` only; links absolute `http:`, `https:` or `mailto:` with `rel="noopener noreferrer nofollow"`; images only attachments of the same message. Remote images in mail become "[Remote image blocked]"; SVG never allowed. `HtmlToSafeHtmlTest` covers 45 hostile inputs.
- Attachments: `BlobStore` on `/data/attachments` (never served by Caddy), server-chosen random keys; type from the bytes (`tika-core`); user uploads wait at most 10 s for a decoder, then 429 `uploads_busy`.
- Drafts in `sessionStorage` keyed by user id; sign-out clears them; storage access wrapped in try/catch.
- Saved-reply placeholders are filled on the server with HTML-escaped values; personal replies visible only to their owner.
- CSV exports (queue, SLA report, audit log): scoped like the screen, capped at 10,000 rows (refused, not truncated, for the audit log), `nosniff`, served as attachments, formula injection neutralised (`= + - @`, tab, CR after any leading whitespace get a leading `'`), each export audit-logged.
- Contacts: agents create and invite customers only in their own organisations.
- Close reason: the server refuses Closed without a reason for every caller; reopen is admin-only with step-up; free text is agent-only plain text, never in the audit log, a client email or a portal response.
- Timeline: built from `ticket_event`, agent-only; names resolved through the viewer's scope, so hidden organisations read "another organisation".
- Known limits: no per-user storage quota; EXIF orientation not applied; orphan blob files can remain if a commit fails after the write.

### Email in and out

- Mailbox credentials ([ADR-020](10-decision-log.md)): admin-entered, write-only, step-up for create/update/delete; AES-256-GCM under `TS_DATA_KEY` with associated data bound to the mailbox id (separate for IMAP and SMTP secrets); responses say only "set"; protocol tracing forced off; errors stored as fixed short categories, never server text.
- Transport: STARTTLS required (not opportunistic) with host name checks; plain-text IMAP or SMTP allowed only to localhost; OAuth2 client credentials (XOAUTH2) supported, tokens in memory only, token URLs must be https.
- Inbound parsing: header caps in the raw bytes before decoding (Subject 2 KB, address headers 32 KB, others 8 KB, block about 256 KB); 30 MB raw, 500 MIME parts, 20 nesting levels; quote stripping linear; anything broken returns "unparseable" so the mail can still go to triage; fuzz tests prove nothing throws. No logging of addresses, subjects or bodies.
- Authentication: only the topmost `Authentication-Results` header counts, and only from a configured trusted authserv-id; DMARC pass must be for the From domain. See accepted risk 6 for Microsoft 365.
- Spoofing guards: a mail joins a ticket only when the sender is the reporter, a confirmed participant or an active agent with access **and** DMARC passes; auto-create (contact, verified domain, sender rule) also needs DMARC pass; otherwise it becomes a triage ticket. Verified and sender-rule domains match the exact domain only.
- CC from client email ([ADR-043](10-decision-log.md)): confirmed only when the email is verified; unverified emails add unconfirmed participants who get no mail. Never added: our own addresses, staff, deactivated accounts, other organisations' contacts (silently), reserved test domains. At most 10 participants per ticket, 50 addresses looked at per email. Accepted risk 3.
- Agent replies by email: `support+internal@` is routing only; a note needs an active agent with access, DMARC pass and the admin setting on; written with the agent's own scope. A reply to an agent-only notification is always internal.
- Loop safety: automated senders never get confirmation/resolved/closed notices; auto-replies are hidden entries; outbound mail carries `Auto-Submitted` and `X-Auto-Response-Suppress`; triage tickets get no confirmation (no backscatter to forged senders); the 21st new mail an hour from one sender folds into their latest ticket, counted per authentication class so forged mail cannot capture genuine mail.
- Inbound attachments go through the same upload checks, with a decode budget (12 MP per image, 10 images / 40 MP per email, 200 MP per poll round). File names lose path, control, quote and bidi-override characters, after a single percent-decode.
- Idempotency: one transaction per mail (log row, ticket change, outbox, UID mark); unique Message-ID; a lease row keeps one poller at a time; new mailboxes start at UIDNEXT (no bulk import of history).
- Outbound: built only from public comments; recipients re-checked at send time; Message-IDs copied into `References` must match a strict pattern; reserved test domains (`.example`, `.test`, `.invalid`, `.localhost`) dropped before any mailbox send; no silent fallback to the server-wide sender. The mailbox is not a relay: no feature lets a user choose arbitrary recipients beyond a ticket's checked participants.
- Block list: the only mail that creates nothing; blocking the support domain is refused; blocking a client's verified domain, a contact or an agent needs confirmation and step-up.
- Known limits: mail arriving between a failed save-time connection and the first successful poll, or across a UIDVALIDITY change, stays in the mailbox and is not imported.

### SLA, notifications and automation

- SLA data is internal ([ADR-017](10-decision-log.md)): repositories refuse customer and API-key scopes; portal responses and client emails are tested for no SLA words.
- Background work runs under SYSTEM scope only from `..job..` packages; each step logs ids and exception types only.
- Notifications never cross organisations: recipients filtered to active agents who can work the organisation; the bell applies the caller's scope in SQL.
- Internal emails carry `Reply-To: support+internal@`, so an agent's reply never reaches a client.
- SLA automation rules ([ADR-049](10-decision-log.md)): admin only, no step-up; the engine runs only under SYSTEM scope; a rule fires at most once per ticket, target and trigger (recorded before acting, in one savepoint); assignment re-checks access at run time; nothing reaches clients.
- Known limits: "every agent of the organisation" notifications are not capped; work lists take at most 2,000 tickets per run.

### Admin configuration

- Every admin screen calls `/api/v1/admin/**` (walked by `RoleGateWalkIntegrationTest`); services re-check the admin scope.
- Step-up for signature template, logo, branding, mailboxes, agent email replies setting, block rules that hit a client; not for request types, SLA settings, sender rules or the audit viewer (all audited).
- Audit details hold ids and the names of changed fields, never values, template text or secrets.
- Logo: 2 MB cap, PNG/JPEG/WebP from the bytes, dimensions checked first, re-encoded PNG at most 30 KB.
- Branding text is one line of plain text (it goes into the From header); brand colour must have 4.5:1 contrast on white. `GET /api/v1/display-name` is public and returns only the support name every email already shows.
- Audit log viewer: admin only, one year back, values rendered as text with control and bidi characters replaced.
- Admin - Customers ([ADR-038](10-decision-log.md)): admin-only three times over (gate, service, repository); step-up for sign out everywhere, move to another organisation and erase; the admin never sees a credential; moving a customer removes them from other organisations' tickets by account and by address.
- Client email switches per organisation ([ADR-048](10-decision-log.md)): admin only, audited; security mail and agent replies cannot be switched off.

### Hardening, erasure and operations

- Erasure: admin, step-up, one database function `ts_erase_user` shared with the restore drill's replay; agents must be deactivated first; erased rows can never be reactivated (database check plus a 409).
- Audit log append-only: the app role has no UPDATE or DELETE on `audit_event`; two narrow SECURITY DEFINER functions are the only changes it can make: `purge_audit_events` (rows older than one year by the database clock, at most 50,000 per call) and `scrub_audit_email(user_id)` (only for a visibly erased user, address captured by a trigger the app role cannot read). Accepted risk 12.
- Retention runs only with a SYSTEM scope from the retention job; attachment files are deleted after the rows commit.
- Key rotation: host-shell command; prints counts and key ids only; a value that does not decrypt is left untouched (exit 3); compare-and-set per value, safe beside the running app.
- Export (`ticketsys-export`): root on the host only, archive mode 600, `.env` values from an allow list (no passwords, no `TS_DATA_KEY`, no setup token), live sessions, sign-in links and trusted devices excluded. Accepted risk 11.
- Restore drill writes only to a scratch database.
- Container logs rotate at 10 MB x 5.
- Go-live security tests 1-5: tagged `@SecurityGoLive`, run in every build and alone with `scripts/ci/api.sh securityGoLive`; `SecurityGoLiveInventoryTest` fails if a test number loses its classes. The SPA's CSP and security headers come from Caddy and are checked against the running stack, not in the api suite: `curl -sI https://helpdesk.example/ | grep -i -E "content-security|x-frame|nosniff"`.

### Demo data and the mail evidence tool

- Demo seed and wipe are host-shell commands (no web route) using a SYSTEM scope from an allow-listed package; they create data only through the real services, track every row, audit counts only, and print no secret; generated credentials go to a root-only mode-600 file.
- `ticketsys-qa-mail-evidence` renders caught mail to files for review. Sign-in emails are always excluded (no flag to include them). Rendering runs in a pinned headless Chromium container with no network, a read-only root, all capabilities dropped and scripts disabled three ways; output is root-only. CI proves the containment with hostile HTML and a canary server (`scripts/ci/mail-evidence-check.sh`).

## Dependencies, licences and SBOM

- **Licence policy**: allow MIT / Apache-2.0 / BSD / ISC and similar permissive licences (EPL-2.0 for test-only tools); anything GPL / AGPL / unknown needs an ADR before it ships.
- **Vulnerability scan**: the CI `vuln-scan` job runs `scripts/ci/vuln-scan.sh`, a pinned Trivy container (repository mounted read-only) in filesystem mode over `api/gradle.lockfile` and `web/package-lock.json`. HIGH or CRITICAL with a fixed version available fails the build (`--ignore-unfixed`). Gradle dependency locking is on, so Trivy sees the full resolved tree without building; update locks with `scripts/ci/api.sh dependencies --write-locks` whenever a dependency changes. Exceptions only in `.trivyignore`, each with a date, reason, owner and re-check date (currently empty). Trivy was chosen over OWASP Dependency-Check (needs an NVD API key and a slow local database) and `npm audit` (npm only). Dependabot opens weekly grouped updates.
- **Not scanned yet**: OS packages in the base images (Temurin, Node, Caddy, Postgres). Planned: `trivy image` on the built images.
- **SBOM**: not generated yet. Planned: CycloneDX from Trivy per release.
- **Support window**: each Spring Boot minor line gets about a year of open-source support; move to the next minor soon after release.

### Direct dependencies

api (`api/build.gradle.kts`; resolved versions in `api/gradle.lockfile`):

| Direct dependency | Version | Licence | Why we need it |
|---|---|---|---|
| Spring Boot starters webmvc, actuator, jdbc, flyway, security, session-jdbc, validation, mail (Spring Framework 7.0, Spring Security 7.1, Spring Session 4.1, Tomcat 11, Jackson 3, HikariCP 7) | 4.1.x | Apache-2.0 | HTTP API, health, database access, migrations, sign-in, sessions, validation, SMTP |
| Flyway core + database-postgresql | 12.x | Apache-2.0 | Schema migrations |
| Angus Mail / Jakarta Mail | 2.x | EPL-2.0 or GPL-2.0 with Classpath Exception or BSD-3-Clause (EDL 1.0) - used under BSD-3-Clause | SMTP and IMAP |
| Thymeleaf | 3.1.x | Apache-2.0 (transitive javassist used under Apache-2.0) | System email templates (HTML auto-escaping) |
| OWASP Java HTML Sanitizer | 20261031.1 | Apache-2.0 | Sanitising HTML with the rich-text allow list |
| PostgreSQL JDBC driver | 42.7.x | BSD-2-Clause | Database driver; client-side SCRAM for the app role password |
| BouncyCastle `bcprov-jdk18on` | 1.86 | MIT | Argon2id |
| Bucket4j core | 8.x | Apache-2.0 | In-memory rate caps |
| ZXing core | 3.5.x | Apache-2.0 | QR code for TOTP enrolment (matrix only) |
| Apache Tika `tika-core` | 4.x | Apache-2.0 (transitive commonmark BSD-2-Clause) | Detecting an upload's type from its bytes (no parsers) |
| TwelveMonkeys ImageIO JPEG, WebP | 3.15.x | BSD-3-Clause | Decoding JPEG (including CMYK) and WebP before re-encoding |
| Nimbus JOSE + JWT | 10.x | Apache-2.0 | Verifying Microsoft ID tokens and loading signing keys |
| Breached-password list (data, `api/src/main/resources/security/breached-passwords-12plus.txt.gz`) | SecLists | MIT | 12+ character entries from the SecLists common-credentials lists, lower-cased and de-duplicated |
| Test only: Spring Boot test starters (JUnit 6, AssertJ, Mockito), Spring Security test, Testcontainers postgresql, GreenMail, ArchUnit | various | Apache-2.0 / EPL-2.0 (JUnit) / MIT | Tests, real Postgres, in-process SMTP/IMAP, architecture rules |
| Build only: Gradle wrapper, Spotless, google-java-format, ktfmt | various | Apache-2.0 | Build and formatting check |

web (`web/package.json`; resolved versions in `web/package-lock.json`):

| Direct dependency | Licence | Why we need it |
|---|---|---|
| react, react-dom, react-router | MIT | UI and routing |
| dompurify | Apache-2.0 / MPL-2.0 (dual) | Sanitising stored HTML on display |
| @fontsource/inter, @fontsource/space-grotesk | OFL-1.1 (fonts), MIT (CSS) | Self-hosted fonts; no runtime font request, which the CSP would block anyway |
| Build and dev: vite, @vitejs/plugin-react, typescript, eslint (+ plugins), prettier, vitest, jsdom, Testing Library, @types/* | MIT / Apache-2.0 | Build, lint, format, tests |
| axe-core (dev) | MPL-2.0 | Accessibility checks in tests; never bundled into `dist/` |

Exact versions live in the lock files; new packages are pinned to versions published at least two weeks earlier. All npm packages in the lock file are permissive (MIT, ISC, Apache-2.0, BSD, BlueOak, MIT-0, CC0, Python-2.0, CC-BY-4.0 data only), plus OFL-1.1 for the unmodified font files and MPL-2.0 for dev-only axe-core (file-level copyleft that applies only if we modify and distribute its files; we do neither).

### Base images (pinned by tag and digest; bumped on purpose)

| Image | Licence | Used for |
|---|---|---|
| eclipse-temurin 21 JDK / JRE | GPL-2.0 with Classpath Exception (OpenJDK) | api build and CI Gradle container / api runtime |
| node 22 alpine | MIT (Node.js), Alpine packages various | web build stage, CI web container |
| caddy 2 alpine | Apache-2.0 | web runtime (static files + reverse proxy) |
| postgres 16 alpine | PostgreSQL Licence | database, Testcontainers (same tag) |
| aquasec/trivy | Apache-2.0 | CI vulnerability scan |
| axllent/mailpit | MIT | Local mail catcher, `dev` profile only |

The OpenJDK runtime is GPL-2.0 with the Classpath Exception: our code runs on it without being linked into it, the normal way every Java application ships. OS packages inside the images are used as-is.

## Privacy / data-processing record (GDPR Art. 30 style)

One row per category of personal data held. "Postgres" means the database on the operator's host.

| Data category | Purpose | Who can read it | Where stored | Retention | Erasure route |
|---|---|---|---|---|---|
| User account: name, email, role, status, Argon2id password hash | Sign-in, notifications | The user; admins (no hashes); never in logs | Postgres | While the account exists; after erasure only id, role, organisation and "Former user" | Admin erase |
| Organisation records: name, verified domains, Entra tenant id, memberships and agent access | Routing tickets and email, access control | Agents (their organisations); admins | Postgres | While the organisation exists | Admin deletes the organisation or edits the user |
| Sessions: user id, sign-in time, auth level, client IP, browser label | Keeping a user signed in; active sessions list | Server; the user | Postgres | 8 h idle / 24 h max | Sign-out, expiry |
| Devices: user id, browser label, last IP, first/last used, trusted until | New-device notice, trusted devices, throttle exemption | Server; the user | Postgres | 400 days after last use | Account deletion; admin erase |
| MFA: encrypted TOTP secret, hashed recovery codes; exemption reason and end | Second factor | Server only (exemption: admins) | Postgres | While enrolled / until the exemption ends | MFA reset; account deletion |
| Notifications and digest log | Bell and notification emails | The recipient | Postgres | With the ticket and user (digest log not yet pruned) | Ticket deletion; admin erase |
| Tickets: subject, description and replies (free text), reporter and participant emails, reply CC lists, internal notes, history | Providing support | Agents of that organisation; clients receive only public comments by email | Postgres | Organisation retention period after Closed (default 3 years) | Retention job; erase removes the person's address from reporter, participant, author and CC fields (redacting a single comment body is not built) |
| Attachments (EXIF stripped from images) | Support evidence | As the comment they belong to | Attachment volume | With their ticket; unattached uploads 24 h | Retention job; cleanup job |
| Agent profile, photo, preferences, out-of-office | Signature, app behaviour, auto-assignment | The agent; admins in part; clients see signature fields | Postgres | While the account exists | The agent clears them; account deletion |
| Linked Microsoft account: tenant id, object id, times | Microsoft sign-in | Server; the user sees the link and tenant | Postgres | While linked | Unlink; account deletion |
| Inbound mail log: raw `.eml`, sender, subject, outcome | Email to tickets; triage and restore evidence | Server; admins (Blocked mail log) | Postgres | Raw `.eml` 90 days; blocked rows 30 days; others with the ticket | Retention jobs |
| Outbox: recipients, subject and bodies of outbound mail | Sending mail | Server only | Postgres | Sent rows 90 days; sensitive bodies blanked at once | Retention job |
| Mailbox settings: address, user name, encrypted secrets, servers | Reading and sending mail | Admins (never the secret) | Postgres | While configured | Admin deletes the mailbox |
| API keys: name, visible id, hash, creator, times | Authenticating monitoring tools | Admins (never the key or hash) | Postgres | While the organisation exists | Revoke; organisation deletion |
| Audit events: user ids, client IP, attempted email on failures | Security monitoring, evidence | Admins | Postgres | 1 year | Retention job; erase scrubs the person's address |
| Erasure log: erased user id, admin id, time | Replaying erasures after a restore | Server; admins via the audit log | Postgres | Kept (ids only) | Not needed |
| Full export archive | Moving to another host ([ADR-028](10-decision-log.md)) | Root on the host only (mode 600) | Host disk until copied | Delete once the move is done | Delete the file |

Keep it current when processing changes. Logs that carry user ids or IP addresses count as personal data.
