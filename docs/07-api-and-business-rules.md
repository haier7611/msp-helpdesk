# API and business rules

What the HTTP API offers and the rules behind it. The exact request and response shapes are in the OpenAPI files (`api/src/main/resources/openapi/`: `tickets.yaml`, `sla-notifications.yaml`, `admin-config.yaml`, `admin-customers.yaml`, `two-step.yaml`, `sla-automation.yaml`, `integrations.yaml`); the product rules are in [15-product-spec.md](15-product-spec.md) and the decisions in [10-decision-log.md](10-decision-log.md). This page is the map between them. Run `grep -rn "RequestMapping(\"/api" api/src/main/java` to re-check the lists below against the controllers. Not every group is covered by OpenAPI yet; widening `OpenApiCoverageIntegrationTest` beyond `tickets.yaml` is an open follow-up.

## Conventions

- JSON over HTTPS, base path `/api/v1`. The web app uses the session cookie `__Host-TS_SESSION` plus the CSRF header `X-XSRF-TOKEN` on every POST, PUT, PATCH and DELETE (`GET /api/v1/auth/csrf` issues the token). Sessions: 8 h idle, 24 h absolute.
- Another organisation's id answers **404, not 403**, with the same body as an id that does not exist.
- Errors are `{"error": "<code>", "message": "<plain words>"}`. Known codes in the shared handler: `unauthenticated` (401), `forbidden` (403), `not_found` (404), `invalid_request` (400), `weak_password`, `subject_required`, `invalid_prefix`, `invalid_logo`, `invalid_photo` (400), `prefix_taken`, `last_admin`, `own_account`, `not_restored` (409). Rate caps and the sign-in back-off answer 429 `too_many_requests` with a Retry-After header. Feature services add their own codes (for example `contact_exists`, 409; `external_cc_unconfirmed`, `cc_not_allowed`, `cc_limit`, 400); the full list per endpoint is in the OpenAPI files, or in the feature's service for groups without OpenAPI.
- Step-up: an endpoint marked `@RequiresStepUp` answers **403 `step_up_required`** unless a TOTP or recovery code was entered on this session in the last 10 minutes. The web app then asks for a code and retries.

## Status and error codes

| Code | Meaning here |
|---|---|
| 200, 201, 202, 204 | Success; 201 for created things, 204 for deletes and no-content actions |
| 302 | Microsoft sign-in redirects only |
| 400 | Invalid input, weak password, bad image |
| 401 | Not signed in, or the session ended (security version changed, deactivated, timed out). The web client sends the user to `/sign-in` |
| 403 | Wrong role for the tree, MFA owed, or `step_up_required` |
| 404 | Not found, or not yours (organisation scoping) |
| 409 | Conflict: last admin, own account, prefix taken, duplicate contact, state clash |
| 413 | Upload or mail body too large (attachments 20 MB each) |
| 429 | Rate cap or sign-in back-off; the body says to wait |

## Authentication levels

| Level | Meaning | Reaches |
|---|---|---|
| Public | No session | `GET /actuator/health` (only `{"status":"UP"}`), `GET /api/v1/version`, `GET /api/v1/display-name` (only the support name, ADR-045), `/api/v1/auth/**` sign-in, reset, magic link, invitation and setup endpoints |
| Authenticated, MFA owed | Agent signed in but second step not done | `/api/v1/auth/me`, `mfa/verify`, `mfa/recovery`, `mfa/enrolment` and related only |
| Full | Customer, or agent with MFA done | The role's tree below |
| Step-up | Full plus a fresh code within 10 minutes | Sensitive actions (see each group) |
| API key | Bearer key bound to one organisation | `/api/v1/integrations/**` |

Anything under `/api` that matches no rule is denied.

## Endpoint groups

### `/api/v1/auth` - public and self-service

Sign-in and session: `GET csrf`, `POST sign-in`, `POST sign-out`, `GET me`. Passwords: `POST password/change`, `password-reset/request`, `password-reset/confirm`. Customer link: `POST magic-link/request`, `magic-link/consume`. Invitations: `POST invitations/inspect`, `invitations/accept` (the link and a new password only; no code, ADR-046). First admin: `GET|POST setup` (needs the host setup token; refuses once an admin exists). MFA: `GET mfa`, `POST mfa/enrolment`, `mfa/enrolment/confirm`, `mfa/verify`, `mfa/recovery`, `mfa/recovery-codes` (step-up), `POST step-up`. Sessions and devices: `GET sessions`, `DELETE sessions/{id}`, `POST sessions/sign-out-others`, `GET|DELETE trusted-devices`. Microsoft: `/auth/microsoft` (`GET`, `POST sign-in`, `GET callback`, `GET|POST|DELETE link`).
Rate limits (per instance, in memory): sign-in 30 per minute per IP; reset and magic-link requests 10 per 10 minutes per IP and 3 per 15 minutes per address (extra requests are silently not mailed); token and code checks 20 per minute per IP; first-admin setup 5 per 10 minutes per IP. Plus the growing per-account back-off (1 s from the third failure, doubling to 15 minutes) that also guards TOTP, recovery and step-up checks.

### `/api/v1/portal` - customers only

`GET request-types` (hides Alert), `POST attachments` and `POST tickets` (raise a ticket; one form key per submit, a repeat returns the first reference; no priority choice), `GET|PATCH account`. Customers have no ticket view (ADR-018). Level: Full, customer role. No step-up. The portal answers a new ticket with its reference only and an upload with id, name and size only.

### `/api/v1/agent` - agents and admins

- Tickets: `GET tickets` (queue and filters), `GET tickets/export` (CSV, audited), `POST tickets` (new ticket for a contact), `GET tickets/{id}`, `GET tickets/{id}/sla` (the SLA panel alone), `GET tickets/by-reference/{reference}`, and actions `POST tickets/{id}/status | priority | request-type | assignee | organisation | public-replies | internal-notes | duplicate-of | links | follow-ups`, and the composer's read-only CC check `POST tickets/{id}/cc-check` (reason code only: allowed / external / staff / not_allowed; another organisation's contact answers `external`, exactly like an unknown outside address, so no account is revealed). A reply's CC errors are 400 `external_cc_unconfirmed` (outside address not in `confirmExternal`, also for another organisation's contact), `cc_not_allowed` (agent or admin account, deactivated contact of the ticket's organisation, support mailbox) and `cc_limit` (more than 10). Closing needs `closeReason` (400 `close_reason_required` / `close_note_required`, ADR-041). Triage: `GET tickets/{id}/triage-options`, `POST tickets/{id}/triage-assignment`. Request types: `GET request-types`.
- Attachments: `POST attachments`, `GET attachments/{id}`, `GET attachments/{id}/thumbnail`. Every download checks comment, ticket and organisation.
- Customers: `GET|POST contacts`, `POST contacts/{id}/invitation`. Organisations (read only, own scope): `GET organisations`, `organisations/{id}`. Agents list: `GET supporters`.
- Saved replies: `GET|POST saved-replies`, `PUT|DELETE saved-replies/{id}`, `POST tickets/{ticketId}/saved-replies/{replyId}/render`.
- Notifications: `GET notifications`, `unread-count`, `POST {id}/read`, `read-all`. SLA report: `GET sla-report`, `sla-report/csv`.
- My settings (`/agent/me`): profile, signature and preview, photo, preferences, out of office, notifications.
Level: Full, agent or admin. Every query is limited to the caller's organisations. Step-up: none on routine ticket work. Rate caps per agent (in memory): `public-replies` and `internal-notes` share one cap, 30 a minute and 300 an hour by default (`app.rate-limits.agent-messages.short-max|short-period|long-max|long-period`); `cc-check` 60 a minute; over a cap 429 `too_many_requests` with Retry-After and nothing stored. Uploads are additionally limited by the image-decoding semaphore (429 `uploads_busy`).

### `/api/v1/admin` - admins only

Organisations (`organisations`, prefix, domains, delete, `client-emails`, `api-keys`), agents (`supporters`: list, detail, role, deactivate and reactivate, invitations, activity, `security/sign-out-everywhere`, `security/reset-mfa`, `PUT|DELETE security/mfa-exemption` for an engineer, step-up), customer access (`users/{id}/access`, `organisation` (step-up), `supporter-access`), customers (`customers` list and detail, `invitation`, `password-reset`, `sign-out-everywhere` (step-up), `deactivate`, `reactivate`, `PUT organisation` (step-up), ADR-038), erase (`POST users/{id}/erase`), request types and order, saved replies, SLA (`organisations/{id}/sla/...` timers, default assignee, business hours, holidays, policies; `request-types/{id}/default-assignee`), SLA automation (`sla-automation/rules`, `order`, ADR-049), tickets (`POST tickets/{id}/reopen`, step-up, ADR-041), email (`email/mailboxes`, test, `settings`, `block-rules`, `blocked-mail` and restore, `sender-rules`, `public-domains`), signature template and logo, branding, Microsoft sign-in (`sign-in/microsoft`), audit (`audit`, `audit/csv`), invitations.
Step-up applies to, among others: erase, reset MFA, sign out everywhere, agent role and deactivation changes, agent invitations, signature template and logo, Microsoft sign-in settings, mailbox secrets, API key creation, reopening a closed ticket and block-list guards. The exact set is every method annotated `@RequiresStepUp` (`grep -rn RequiresStepUp api/src/main/java`). Rate limits: mailbox test connection 1 per minute per mailbox and 10 per hour per admin.

### `/api/v1/integrations` - monitoring tools

`POST /api/v1/integrations/tickets` with an API key bound to one organisation (`Authorization: Bearer tsk_<id>_<secret>`). Limits: 60 requests per minute per key, 256 KB per body. Keys are random with a visible prefix, stored as SHA-256, optional expiry, "last used" recorded, shown once. Integrator guide: [45-integrations-api.md](45-integrations-api.md).

### Other

`GET /api/v1/version` (name, version, git sha) and `GET /actuator/health` for probes.

`GET /api/v1/display-name` (ADR-045, public): `{"name": "<support name>"}` - the Branding support name, else `MAIL_SUPPORT_NAME`, else "Support desk"; shown in the web top bar, sign-in card and tab title. `Cache-Control: no-cache`.

## Business rules

Each rule names where it is decided. If code and spec differ, the spec wins and the code is fixed.

### Statuses and the client email matrix

- Six statuses: New, Open, Pending on client, Pending on vendor, Resolved, Closed. Allowed manual moves, system-only moves, and what a client reply does are in spec section 3 (ADR-007, ADR-012). Nothing returns to New; a Closed ticket accepts only internal notes until an admin reopens it (ADR-041); a client reply to a Closed ticket opens a linked follow-up.
- The client is emailed only on: ticket created, public reply, Resolved, Pending-on-client reminder (3 business days), Closed notice (5 days after Resolved). Never to automated addresses; no mail at all to triage tickets except an agent's public reply; alert tickets send none unless the organisation opted in (spec 3 matrix, 11b). Admins can switch off the confirmation, Resolved notice, Closed notice and reminder per organisation (ADR-048).
- Pending on client auto-resolves after 7 business days (reminder after 3; one reminder per spell); the 5-day auto-close counts calendar days. Both Pending timers are per organisation and can be switched off (ADR-009, ADR-019).

### Ticket references (ADR-014)

- A reference is `[PREFIX-NNN]` from the per-organisation counter; triage tickets use `TRI-NNN`. Only the reference module builds or parses them (ArchUnit rule).
- Moving a ticket to another organisation issues a new reference; the old one stays as an alias. Marking a duplicate makes the source reference an alias of the target. Retired prefixes stay reserved to their organisation.

### Matching order for inbound email (spec 11b, ADR-022)

Block list, then the reference in the subject (first that resolves), then `In-Reply-To` and `References` against our outbox, then sender and DMARC guards, then (new mail only) the 20-per-hour cap, sender rule, saved contact, verified domain, and finally Email triage with the reason shown. A failing guard opens a new triage ticket and never touches the matched ticket. Auto-attach and auto-create need a DMARC pass.

### Block list

Admin only. Blocks sender addresses and domains; blocked mail makes no ticket and no bounce, is kept 30 days in the Blocked mail log and can be restored (routed as new mail). Guards: warn and step-up when the block would hit a client's verified (sub)domain, a saved contact or an agent; the support mailbox's own domain cannot be blocked. A block also applies to replies on existing tickets. Every change is audited.

### Fold-in (flood control)

The 21st and later new mails in an hour from one sender become a client comment on that sender's latest ticket (a hidden entry if the sender is automated) and the assignee is notified. Counted per authentication class so forged DMARC-failing mail cannot use up or hijack genuine mail. If the latest ticket is Closed, a follow-up opens and becomes the target for the rest of the hour. Fixed at 20 in v1.

### SLA rules (ADR-017, spec 4.7)

- Targets come from the organisation's policy (default or per request type). P1 is always 24/7; other priorities use the organisation's business-hours calendar and holidays (DST safe). Triage tickets run the built-in defaults until they are moved to an organisation (ADR-047).
- The clock runs in New and Open, pauses in Pending on client, runs in Pending on vendor unless the policy pauses it, and stops in Resolved and Closed. A reopen or priority change recalculates from creation.
- Warning at 80% used. SLA information is internal only; clients never see it. Agents set the final priority; impact and urgency are guidance (F6). Admin-defined automation rules can act on at risk, breached and met, each at most once per ticket, target and trigger (ADR-049).

### Assignment

Request type default, then organisation default, else Unassigned; a default who is inactive or has no access is skipped; an out-of-office default stops the search and leaves a system note. Runs once at creation (spec 4.6).

### Retention (spec 10, [30-reliability.md](30-reliability.md))

| Data | Kept |
|---|---|
| Closed tickets and everything on them | Organisation's `retention_months` after closing (default 36, 12-120) |
| Audit events | 1 year |
| Sent or failed outbox rows | 90 days |
| Sign-in link tokens | 30 days after expiry |
| Raw `.eml` / blocked mail log | 90 / 30 days |
| Backups | 30 days (this is also the erasure lag) |

Nightly job at 03:40 UTC, bounded batches, dry-run switch `APP_RETENTION_DRY_RUN`.

### Access and visibility

Every query on client-owned data is scoped by organisation in the service layer with a test for cross-organisation denial (ADR-009 C13), and every client-data write carries the scope condition in its own SQL (ADR-050). Internal notes, their images and attachments never leave agent-only endpoints. Admin-only writes are checked at the URL gate, in the service and in the repository.

### Customer accounts (ADR-038)

Admin only (Admin - Customers). Deactivating a customer revokes their links, ends their sessions and stops ticket email; their tickets stay. Moving a customer to another organisation ends their sessions and removes them as a participant from every ticket of another organisation (ADR-024 rule, timeline line per ticket); tickets they raised stay with the old organisation. Force sign-out and move need step-up.

### Erasure

Admin only, step-up, audited. Name becomes "Former user", address removed everywhere it is stored, account final (cannot be reactivated); agents must be deactivated first; an admin cannot erase themselves (spec 10).

## Diagrams

See [diagrams/00-index.md](diagrams/00-index.md) for the full set. Most relevant here: [01-system-context.md](diagrams/01-system-context.md) for the system boundary, [05-ticket-lifecycle.md](diagrams/05-ticket-lifecycle.md), [06-email-flows.md](diagrams/06-email-flows.md), [07-sequences.md](diagrams/07-sequences.md) and [08-security-and-permissions.md](diagrams/08-security-and-permissions.md).
