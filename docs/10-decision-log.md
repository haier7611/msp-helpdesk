# Decision log

Append-only. A later ADR overrides an earlier one; a change to a decision is appended under that ADR. ADR numbers match the project's original log, so references in code comments still resolve; decisions that only concerned the original installation (hosting, domains, tooling) are kept as one line, and numbers can have gaps.

### ADR-001 Development workflow - **Accepted**

- Deployment-specific decision for the original installation; not relevant to this repository. Generic summary: spec first, every change reviewed separately before merge, CI green on the exact commit that is merged, decisions recorded here. See [how-this-was-built.md](how-this-was-built.md).

### ADR-002 Product scope: multi-client MSP helpdesk - **Accepted**

- **Context**: a small managed service provider (MSP) supports several client organisations and wants a helpdesk of its own instead of a hosted service desk.
- **Options**: A own MSP clients (multi-organisation helpdesk, one MSP operates it) / B SaaS for sale (signup, billing, tenant isolation) / C internal single-organisation helpdesk / D portfolio-only.
- **Decision**: A. One installation, one MSP, many client organisations. Not multi-tenant SaaS: no self-signup or billing in v1. The data model keeps an `organisation_id` on every client-owned row so B stays possible later.
- **Consequences**: organisations, per-organisation visibility, SLAs and a customer portal are core. Real client data is never imported into fixtures, tests or docs - synthetic data only.

### ADR-003 Intake channels for v1: portal, email, API/webhooks - **Accepted**

- **Decision**: tickets come in from (1) a signed-in customer web portal, (2) email-to-ticket, (3) an authenticated API/webhook for monitoring alerts. Agents can also create tickets in the agent UI (needed for phone calls anyway).

### ADR-004 Stack: Java 21 + Spring Boot, React frontend, PostgreSQL - **Accepted**

- **Options**: A Spring Boot REST API + React (Vite, TypeScript) / B Spring Boot + Thymeleaf/HTMX single app / C Spring Boot API + Next.js. Also considered: Next.js + Supabase, or adopting an existing open-source helpdesk (Zammad, osTicket, FreeScout).
- **Decision**: A. Backend Java 21, Spring Boot, Spring Security, Spring Data JPA, Flyway migrations, PostgreSQL 16, Gradle (Kotlin DSL). Frontend React + Vite + TypeScript. Testcontainers for integration tests.
- **Consequences**: two build pipelines (Gradle, npm). Access rules are enforced in the API service layer and tested there, not in Postgres row-level security.
- **Change (ADR-021)**: Spring Boot 3 became Spring Boot 4.1 (Spring Framework 7, Spring Security 7); Java 21 stays. 4.1 was chosen over 4.0 because it was already released, so 4.0 support would end sooner.

### ADR-005 Email-to-ticket via generic IMAP polling - **Accepted**

- **Options**: A Microsoft Graph on a Microsoft 365 shared mailbox / B inbound webhook provider (a transactional mail service) / C generic IMAP polling.
- **Decision**: C. A scheduled job polls one or more configured IMAP mailboxes, turns new mail into tickets, threads replies onto existing tickets by a ticket reference in the subject plus `In-Reply-To`/`References` headers, and sends outbound mail by SMTP.
- **Consequences**: Microsoft 365 mailboxes no longer accept IMAP basic auth, so the IMAP client supports XOAUTH2 (OAuth2) as well as password auth. The polling interval sets intake latency (target 1-2 minutes). Spoofed senders are a real risk: sender matching alone must not grant access to a ticket (spec section 6).
- **Change (ADR-009 C10/C11, ADR-014)**: the ticket reference format is per organisation (`ACD-001`); matching rules and the DMARC guard are defined in ADR-014; Microsoft 365 uses OAuth2 client credentials (`IMAP.AccessAsApp`).

### ADR-006 Sign-in: local accounts plus optional Microsoft (Entra) SSO - **Accepted**

- **Decision**: email + password (and magic link) for everyone; optional "Sign in with Microsoft" (OIDC, Entra multi-tenant app). Spring Security for both. MFA (TOTP) required for agent and admin roles.
- **Change (ADR-009, ADR-013)**: magic link is for customers only, never agents or admins; Microsoft accounts are linked by (`tid`, `oid`), not by verified email; Entra's MFA claim counts only from the MSP's own tenant.

### ADR-007 Agent replies, "Pending on client" status, internal notes with images - **Accepted**

- **Decision**:
  - Public replies are written in the agent UI and emailed to the client. Email goes out **from the shared support mailbox with the agent's name as display name** plus the agent's signature. Rejected for v1: sending from each agent's own address (needs per-agent SMTP credentials and breaks SPF/DMARC alignment and reply threading).
  - Status `Waiting on customer` is named **`Pending on client`**; it pauses the SLA, a client reply reopens it, with a reminder (3 business days) and auto-resolve (7) that can be configured or switched off.
  - Internal notes and public replies share one rich-text editor with inline image paste/upload and attachments; attachments inherit the comment's visibility and are served only through an authorised endpoint.
- **Change (ADR-011, ADR-009 C12)**: the per-agent rich-text signature is replaced by fixed fields + a company template (ADR-011); a confirmation dialog before a public reply is replaced by the two-tab composer (ADR-009 C12). The shared editor with images and attachments stays.

### ADR-008 Admin area to manage agents; agent "My settings" - **Accepted**

- **Decision**: one sign-in page for all roles with role-based landing; mandatory MFA for agents and admins; first admin via a one-time setup token (no default password); admin "Manage agents" (invite, edit role and organisations, deactivate with ticket reassignment, MFA reset, force sign-out, last-admin guard, audit log); "My settings" for every agent (profile, signature inside a company template, security, notifications, preferences, out of office).
- **Change**: after a design review, accepted as amended by ADR-009 (sessions, step-up, invitations, first-admin token from the environment, manage-agents side panel), ADR-011 (signature fixed fields) and ADR-013 (scope, magic link for customers only). Spec section 7 is the current detail.

### ADR-009 Design review fixes C1-C18 - **Accepted**

- **Context**: three independent reviewers (product, security, architecture/UX) reviewed an early version of the spec; their findings were merged into one list.
- **Options**: A adopt all as a block / B pick item by item / C defer to build time.
- **Decision**: A, except where ADR-011..014 say otherwise.
  - C1 Microsoft accounts linked by (`tid`, `oid`), tenant allow list (MSP tenant for agents, one tenant per client organisation), agents link only from a signed-in session.
  - C2 Entra MFA claim trusted only from the MSP tenant; everyone else gets TOTP.
  - C3 Server-side sessions in Postgres (Spring Session JDBC), one policy for agents (8 h idle, 24 h absolute), step-up TOTP valid 10 minutes, optional trusted device 30 days.
  - C4 `security_version` per user; a role, organisation, MFA or password change ends all other sessions.
  - C5 One rich-text allow list, OWASP Java HTML Sanitizer on save, DOMPurify on display, strict CSP, remote images blocked until clicked, placeholders escaped.
  - C6 `BlobStore` on disk, UUID keys, per-request permission check, download-only non-images, `nosniff`, server-side type detection, images re-encoded, inline images embedded in client emails, separate public and internal comment types.
  - C7 No magic link for agents; all links single use, hashed, short expiry; agent invitations need a 6-digit code, link 48 h (the code was later removed, ADR-046).
  - C8 First-admin setup token from an environment variable, never logged; break-glass MFA reset command in the container.
  - C9 Same-organisation CCs become participants; external CCs need agent confirmation; moving organisation re-checks participants.
  - C10 DMARC pass (from `Authentication-Results`) required to auto-attach or auto-create; ignore auto-replies and bulk; mark our mail `Auto-Submitted`; per-sender rate cap; outbound carries only the new message.
  - C11 IMAP poller tracks UIDVALIDITY + UID, unique Message-ID index, one transaction per mail, single-poller lock, Microsoft 365 via `IMAP.AccessAsApp`, raw `.eml` kept 90 days.
  - C12 Two-tab composer ("Reply to client" / "Internal note"), resets to the user's default per ticket, no confirmation dialog; notes never become public; sent replies not editable.
  - C13 Request-scoped `AccessScope` on every repository method plus an ArchUnit build test; API trees `/portal`, `/agent`, `/admin`; no Hibernate filters.
  - C14 Audit log field allow list, insert/read-only database role on the audit table, audit-log export itself audited.
  - C15 Growing delay instead of hard lockout; passwords of 12+ characters checked against a breached list; Argon2id.
  - C16 API keys: no HMAC; random key with visible prefix, SHA-256 stored, optional expiry, last used, 60/min, 256 KB body cap.
  - C17 Build order: outbound mail core before sign-in; SLA timers and notifications with stored due times and a 1-minute job.
  - C18 Manage agents as list + side panel (Overview / Access / Security / Activity), step-up only on role change, two-step deactivate wizard, disabled "last admin"/"yourself" buttons with a reason.
- **Consequences**: spec sections 7, 8, 9 and 12; five go-live security tests are in the acceptance checklist.

### ADR-010 Missing features F1-F8 added to v1 - **Accepted**

- **Context**: the product review listed features a working MSP helpdesk needs from day one.
- **Options**: A all in v1 / B some to Later / C none.
- **Decision**: A.
  - F1 Mark as duplicate (closes source, links both ways, copies participants, source reference becomes an alias of the target) and link related ticket.
  - F2 Agents create tickets on behalf of contacts and create/invite client contacts in their organisations.
  - F3 Canned responses, shared and personal, with placeholders, inserted with "/".
  - F4 A reply to a Closed ticket opens a linked follow-up ticket.
  - F5 Client email matrix: client emailed only on created, public reply, Resolved, Pending reminder, auto-close.
  - F6 Priority set by agents; portal asks impact/urgency; request type sets starting priority.
  - F7 CSV export of the filtered queue with SLA met and response/resolution times.
  - F8 Agents can reassign any ticket in their organisations without an admin.

### ADR-011 Email signature: fixed fields + company template - **Accepted**

- **Context**: a per-agent rich-text signature inside a company template carried HTML-injection risk, inconsistent branding and images that mail apps block.
- **Options**: A per-agent rich text inside a template / B fixed fields + company template / C one shared signature for everyone.
- **Decision**: B. Each agent fills display name, job title, phone, mobile and one free-text line. The admin-defined company template lays them out with one company logo. No per-agent rich-text editor, no per-agent images. Live preview (HTML + plain text) rendered by the server. Rejected: A (sanitising and support burden), C (no personal contact details).
- **Consequences**: template editing needs step-up.
- **Change**: the default company template follows a common corporate layout: name (large, bold, brand colour), title (bold), A:/E:/P: lines with coloured labels, grey dashed divider, logo below. The address is company-level. Only synthetic sample values are in the repository.

### ADR-012 New status "Pending on vendor" - **Accepted**

- **Context**: MSP tickets often wait on a supplier, ISP or Microsoft; using "Pending on client" for that would send the client reminders and auto-resolve.
- **Options**: A new status / B reuse Pending on client / C a tag on Open.
- **Decision**: A. "Pending on vendor": whether it pauses the SLA is set per SLA policy (default: does not pause); never sends client reminders; never auto-resolves. A client reply moves it to Open (same as Pending on client).
- **Consequences**: the SLA policy gets a "Pending on vendor pauses SLA" flag.

### ADR-013 v1 scope "keep all"; simple default-assignee rule; magic link for customers only - **Accepted**

- **Context**: the review asked whether out of office, notification centre, profile photo/dark theme and magic link should move to Later to shrink v1.
- **Options**: A keep all in v1 / B move some to Later.
- **Decision**: A. Out of office, in-app notification centre (bell, unread) plus daily digest, profile photo plus dark theme, and magic-link sign-in for customers stay in v1. Magic link stays **off** for agents and admins (C7 still applies). Because out of office needs assignment: an optional default assignee per organisation and optionally per request type (request type wins); out-of-office agents are skipped and the ticket goes to Unassigned with an internal system note. No round-robin in v1.
- **Consequences**: supersedes ADR-006's "magic link for everyone" for agents. Round-robin goes to Later.

### ADR-014 Per-organisation ticket references and email threading - **Accepted**

- **Context**: replies by email must land on the right ticket for clients, CC users and agents. Each ticket is identified in email by a bracketed reference such as `[ACD-001]`.
- **Options**: A one global sequence (`TS-1234`) / B per-organisation prefix + sequence (`ACD-001`) / C opaque random ids.
- **Decision**: B.
  - Each organisation has a prefix (2-6 uppercase letters/digits, unique, set at creation). A prefix can be changed later: issued references never change and keep working through the reference/alias table, and the old prefix stays reserved. Numbers come from a per-organisation counter row with a row lock (never `max()+1`), zero-padded to at least 3 digits.
  - Triage tickets get `TRI-nnn`; on assignment to an organisation they get a new organisation reference and the `TRI-` one stays as an alias. Duplicates (F1): the source reference becomes an alias of the target.
  - Every outbound subject carries `[ACD-001]`; the body footer repeats it.
  - Inbound: bracketed reference in the subject first (tolerant of RE/FW/AW, extra brackets, case), then `In-Reply-To`/`References` against outbox Message-IDs. Attach only if the sender is the reporter, a participant or an active agent **and** DMARC passes; a missing reference, or one from another organisation than the sender's, goes to triage with the reason.
  - Agent email replies: client-facing mail uses `Reply-To: support@<mailbox>` and an agent's reply becomes a public reply; agent-only notifications use `Reply-To: support+internal@<mailbox>` with subject `[ACD-001] (Internal)` and a reply becomes an internal note, never emailed to the client. Plus-addressing must be enabled on the mailbox (Microsoft 365 and Gmail support it).
  - Rejected: A (clients cannot tell their tickets apart and one sequence leaks volume across clients), C (not human-friendly on the phone).
- **Consequences**: references are built and parsed only by the reference module (spec section 6). Risks: DMARC on client domains and plus-addressing on the mailbox.

### ADR-015 Logo, alerts, email replies, E: line - **Accepted**

- **Logo**: embedded in each email (`cid:`), not hosted. Supersedes the hosted logo of ADR-011. The logo is resized on upload to keep emails small.
- **Support mailbox role / client-replied alert**: one support mailbox receives client mail, sends public replies and alerts agents; client email replies are stored as client replies, distinct from notes. The "client replied" alert is agent-only (Reply-To `support+internal@`), so replying to it makes an internal note.
- **Agent replies by email**: a three-way admin setting Off / Internal notes only (default) / Notes and public replies; step-up to change; the risk is explained on screen.
- **E: line**: the agent's own email by default; the admin can switch to the support mailbox.
- **Change (full signature always)**: every agent reply email carries the full signature (no short signature on later replies in a thread). The preview and the template settings have no short-signature option.

### ADR-016 Hosting of the original demo - **Accepted**

- Deployment-specific decision for the original installation; not relevant to this repository. Generic lesson: the app and its database run side by side in Docker Compose, a separate public edge terminates HTTPS, and Postgres is never reachable from the internet. See [deployment.md](deployment.md).

### ADR-017 SLA targets, P1 24/7, internal-only SLA, report page - **Accepted**

- **Decision**: default policy P1 30 min / 4 h (24/7 clock), P2 1 h / 8 business hours, P3 4 business hours / 3 business days, P4 1 business day / 10 business days; the admin can edit it per organisation and request type. First response = first public agent reply. Resolution time excludes paused time; a reopen continues the clock. Measured times are stored on the ticket. SLA is internal only - never shown to customers. SLA report page per organisation and engineer, plus CSV. Spec section 4.7.
- **Consequences**: 24/7 and business-hours calendars, P1 all-agent alerts, report page. P1 24/7 implies someone reachable out of hours; an on-call rota is on the Later list.
- **Change (SLA engine interpretations)**: (1) "1 business day" as a duration = the longest weekday's business hours in that calendar (9 h for the default Mon-Fri 08:30-17:30). (2) The first-response clock pauses on the same statuses as resolution. (3) A ticket resolved without any public agent reply has first response "not applicable" (neither met nor breached) and is listed as such in the SLA report. (4) While a ticket is paused its due times are empty and the UI shows "Paused".

### ADR-018 Customer portal is raise-only; clients follow tickets by email - **Accepted**

- **Options**: raise only, follow by email / raise + view own tickets.
- **Decision**: raise only. The customer portal is a New ticket form plus My account. No ticket list, ticket detail, portal replies or status display. There is no organisation-viewer role. Clients add replies and attachments by email only; the email footer has no portal link.
- **Consequences**: a much smaller customer attack surface (no customer read endpoints exist). Clients cannot check a ticket's status or history online - they rely on the client emails (created, reply, resolved, reminder, closed). If clients ask for a status view later, "view own tickets" is the first Later item.

### ADR-019 Clarifications from detailed design - **Accepted**

- **Context**: detailed design raised 15 open questions; technical and low-stakes product details were decided together.
- **Decision**: spec section 11a (invitation expiry, trusted-device revocation, recovery codes for step-up, unique domains, agents not on /portal, timer jobs, calendar vs business days, moving between organisations, duplicates within one organisation, notes on Closed, impact/urgency values, "picks up", triage visibility, From name format, security notice emails).

### ADR-020 Mailbox is admin-configured, any provider; branding from settings only - **Accepted**

- **Decision (mailbox)**: no provider is built in. Admin - Email - Mailboxes accepts any IMAP + SMTP mailbox with password auth or OAuth2 (Microsoft 365 `IMAP.AccessAsApp` / SMTP OAuth, Google). Until a mailbox is configured, a bootstrap SMTP sender is read from server environment variables; once a mailbox is saved in the admin UI it takes over. In development and CI a local mail catcher (Mailpit) receives all outbound mail so invitations and replies can be seen without a real mailbox.
- **Decision (branding)**: the company name, address and logo are entered through Admin - Branding and the company signature template at run time. No company's branding is ever committed to the repository, fixtures, tests or docs.
- **Consequences**: provider-agnostic mailbox settings with a "test connection" check; Mailpit in the compose dev profile.

### ADR-021 Move to Spring Boot 4 before any auth work - **Accepted**

- **Context**: a dependency scan found two Spring Framework 6.2 CVEs with no public fix. Spring Boot 3.x open-source support has ended, so further fixes only come in Boot 4 (Spring Framework 7, Spring Security 7).
- **Options**: A upgrade now, while the code is a skeleton / B stay on 3.5 and upgrade later (auth, sessions and security config would then be migrated across a major Spring Security version) / C buy commercial 3.x support.
- **Decision**: A. Upgrade to Spring Boot 4 (Java 21 stays), update Testcontainers/Flyway/Spotless as needed, regenerate Gradle locks, re-run the scan. All auth work is written against Spring Security 7.
- **Change**: shipped as Spring Boot 4.1 rather than 4.0.

### ADR-022 Matching email to organisations: remember on triage, public domains, agent vs admin rights - **Accepted**

- **Context**: when anyone emails the support mailbox, the system has to decide which organisation the ticket belongs to.
- **Decision**: spec section 11b. Remember-on-triage ticks; public mail domains are never verified domains; agents may change a ticket's organisation; only admins create, rename or delete organisations. Per-organisation addresses (`support+acd@`) not adopted.
- **Change (every email becomes a ticket)**: there is no "Other mail" list or "always ignore" rule; every inbound email creates a ticket (matched organisation or triage) or is added to the ticket it replies to. Loop safety is kept without dropping mail: no automatic emails to bulk or automated senders, and over 20 new emails per sender per hour fold into that sender's latest ticket as comments. Sender rules only route.
- **Change (block list)**: an admin-only block list of sender addresses and domains, the only exception to "every email becomes a ticket". Blocked mail is logged 30 days with admin restore rather than deleted, gets no bounce or reply, needs guards plus step-up for blocking client domains or contacts, the support domain cannot be blocked, and an admin "Block sender / Block domain" quick action on tickets closes them as spam. Spec section 11b.

### ADR-023 Web shell built ahead of the backend - **Accepted**

- Generic summary: the React shell, design tokens and components were built against mock data in parallel with the backend, then wired to real data feature by feature. No lasting effect on the code.

### ADR-024 Clarifications from detailed design (part 2) - **Accepted**

- **Participants on organisation move**: participants are re-checked against the new organisation; those who no longer match are removed (audited) and get no further mail.
- **Alert request type** is hidden from the customer portal; it is only used by the API, sender rules and agents.
- **Public-domain sender failing DMARC**: still becomes a ticket (ADR-022) in Email triage with a "Sender not authenticated" badge; it is never attached to an existing ticket or matched to an organisation automatically.
- **Repeat alert with the same external_id**: if the ticket is Resolved, it reopens to Open with the alert as a comment; if Closed, a new linked follow-up ticket is created (same as F4).
- **Timezones**: P1 alerts go out immediately (no timezone logic). The daily digest is sent at 08:00 in each agent's own timezone preference (default: the configured MSP timezone).

### ADR-025 Microsoft sign-in configured by the admin - **Accepted**

- **Decision**: no Entra app details in server config; spec section 11c. Off by default; the admin enters client ID, secret (encrypted, write-only), MSP tenant ID and per-organisation allowed tenants; Test sign-in before enabling; step-up and audit for changes; secret expiry warning 30 days ahead. An admin creates the Entra app registration in their own tenant ([runbooks/entra-app-registration.md](runbooks/entra-app-registration.md)).
- **Consequences**: the OIDC client registration is built dynamically from database settings, not static application properties.
- **Change**: the inbound mail parsing engine and the SLA engine were built as pure-logic modules first and wired to tickets afterwards.

### ADR-026 Flyway ordering - **Accepted**

- Generic summary: Flyway out-of-order stays off, so applied versions only ever go up. Pull requests merge in the order they are ready; just before merging, a migration whose version is not above the highest on main is renamed to the next free number (file rename only, no content change). This is why migration numbers have gaps ([03-database-design.md](03-database-design.md)).

### ADR-027 Default request types for an IT helpdesk - **Accepted**

- **Decision**: seed a short, general list of client-visible types plus the hidden Alert (admins edit the list later; no per-type custom fields).
- **Change (shorter list)**: 8 client-visible types plus the hidden Alert: Account and access (passwords, locked accounts, permissions) P3; Email and Microsoft 365 P3; Computers and devices (laptops, phones, printers, scanners) P3; Network and internet P2; Software and applications P3; New starter or leaver P3; Security concern (phishing, viruses, suspicious email) P2; Other or general question P4 (renamed from "General"); Alert P2 hidden. Each type gets a short client-facing description shown under the name in the portal form. No type defaults to P1, so a client's choice never starts the 24/7 P1 clock; agents set the final priority (F6). A 14-type first draft is superseded.

### ADR-028 Production hosting of the original installation - **Accepted**

- Deployment-specific decision for the original installation; not relevant to this repository. Generic lesson: keep a tested "move to another domain or server" runbook ([runbooks/move-domain-or-server.md](runbooks/move-domain-or-server.md)) and a full data and settings export, and keep backups off the application host.

### ADR-029 Merge-conflict reduction - **Accepted**

- Generic summary: changelog entries are one fragment file per change (combined at release by a script), and audit event types are one enum per feature package with their allow-listed detail keys, so parallel changes stop editing the same lines. Tests share one Postgres/mail Testcontainer per test JVM, and CI runs the web and dependency-scan stages in parallel with the api stage.

### ADR-030 Test-round tooling - **Accepted**

- Deployment-specific decision for the original installation; not relevant to this repository. It added seed and wipe commands for synthetic test data (marker table `qa_seed`, see [03-database-design.md](03-database-design.md)).

### ADR-031 Two-step sign-in for staff stays required; trusted device after a recovery code; time-limited exemption per engineer - **Accepted**

- **Context**: admins asked for a way to switch two-step sign-in off, and testing found no "Trust this device" option on the recovery-code step, so a code was asked at every sign-in. Options: A organisation-wide switch for engineers, B per-engineer exemption with reason and end date, C keep it required and fix trusted device. Chosen: C + B.
- **Decision**:
  - C: the recovery-code step offers the same "Trust this device for 30 days" option as the 6-digit code step; the trusted-device cookie and record last 30 days (not longer). Trusted devices never skip step-up.
  - B: an admin can exempt one named engineer (role Engineer only, never Admin) from two-step sign-in: reason required (max 200 characters), end date required (default 7 days, max 30 days), step-up needed, audit events granted / revoked / expired, security notice email to the engineer. While exempt the engineer signs in with password (or Microsoft) only and set-up/code steps are skipped; step-up for an exempt engineer is password re-entry. The exemption ends on its own at the end date (checked at sign-in and on each request's auth level), can be revoked any time, and is removed if the engineer becomes an admin or is deactivated. The engineer sees a banner "Two-step sign-in is paused for your account until <date>"; Admin - Agents shows "Exempt until <date>".
  - No organisation-wide off switch (A rejected: one leaked engineer password would expose every client).
- **Consequences**: a migration for the exemption, spec sections 7 and 11a; security review required.

### ADR-032 "Agent" replaces "Supporter" in all user-facing text; every ticket change shown in the timeline - **Accepted**

- **Decision**:
  - Terminology: every user-visible word "Supporter(s)" becomes "Agent(s)" (UI, emails, docs, spec). Code identifiers, API paths (`/api/v1/admin/supporters`), database names and stored audit event names stay unchanged (renaming them is churn and breaks stored names).
  - Timeline: every assignee change appears as a one-line, agent-only system line in the ticket timeline (not a comment card) with date and time in the viewer's timezone: "Assigned to <agent> by <actor>", "Reassigned from <a> to <b> by <actor>", "Unassigned by <actor>", "Assigned to <agent> automatically (<reason>)". Never shown to clients, never emailed. Built from the existing assignee-changed event.
- **Change (organisation dropdown)**: the ticket Details card has an Organisation dropdown (organisations the agent may access); choosing another one shows an inline confirmation with the existing warnings ([Move] / [Cancel]) before the existing change-organisation API is called. No server change.
- **Change (every change in the timeline)**: every ticket change appears as an agent-only one-line timeline entry with actor (agent, client reply or system) and date/time: status (manual and automatic: reopened by client reply, auto-resolved, auto-closed, follow-up), priority, request type, organisation (set from triage, moved), duplicate/merge, related links added/removed, follow-up created, participants added/removed, reporter changed. Names are shown, not ids. Never shown to clients or emailed.

### ADR-033 A newly connected mailbox only turns new emails into tickets - **Accepted**

- **Context**: connecting a mailbox that already held mail turned every existing inbox email into a triage ticket.
- **Decision**: when a mailbox is created, when polling is switched on for the first time, or when its folder changes, the poller records the folder's IMAP UIDNEXT (with UIDVALIDITY) as the starting point and only processes messages with UID at or above it; later polls continue from the last processed UID, so mail that arrives during an outage is still picked up and reading mail in the mailbox client changes nothing. If UIDVALIDITY changes, the poller re-baselines at the current UIDNEXT (no bulk re-import) and logs a warning plus an audit event. Existing mailboxes keep their current position. No backfill option for now (later idea: "import the last N days" for migrations).

### ADR-034 Ticket email can be sent through the support mailbox (per-mailbox SMTP) - **Accepted**

- **Context**: with only a server-wide SMTP setting, replies could not be sent from the support mailbox itself, and the plus-addressing test email could never pass. Options: A keep the server-wide SMTP only, B point the server-wide SMTP at the mailbox provider (all mail real, bounces from synthetic addresses), C per-mailbox SMTP. Chosen: C.
- **Decision**:
  - A mailbox gets optional outgoing settings: SMTP host, port, security (TLS or STARTTLS; "none" refused with credentials), "use the same sign-in as incoming" (default on) or its own user name and password; the secret is stored encrypted like the IMAP secret, never returned, and changing it needs step-up. Admin API, OpenAPI and the mailbox form expose it.
  - Ticket mail (public replies, ticket confirmations, status emails, agent alerts with the +internal Reply-To, and the plus-addressing test email) goes out through the support mailbox's SMTP when set, From = the support address with the agent's display name; otherwise the server-wide SMTP. Account and security mail always uses the server-wide SMTP (changed by ADR-046).
  - Reserved test domains (.example, .test, .invalid, .localhost and subdomains) are never sent through a real mailbox SMTP: dropped with a log line and a system note, so seeded synthetic contacts cannot bounce into the mailbox.
  - Test connection also checks the SMTP sign-in (no message sent); the mailbox shows the last sending error.

### ADR-035 Agents can edit CC from the reply composer - **Accepted**

- **Context**: CC participants only came from client emails (same-organisation CCs auto-added, external CCs need confirming); agents could not add or change CC when replying.
- **Options**: A editable CC in the reply composer with a "keep on future replies" choice; B manage CC only in the side panel. Chosen: A.
- **Decision**: the "Reply to client" composer line reads "To: <reporter>   CC: <names>   Edit". "Edit" opens an inline CC editor: current participants as removable chips, an add box (name or email, suggestions from the ticket organisation's contacts), "Keep on future replies" (default on; off = CC on this reply only), Done. To stays the reporter (changing the reporter is a separate action). Rules: same-organisation contacts are added at once; an address outside the organisation's verified domains needs an explicit confirmation ("not from <org> - add anyway?"), and an agent's confirmation counts as the external-CC confirmation; at most 10 CC per ticket; restricted agents only see and add contacts of organisations they may access (server-side); reserved test domains are never emailed (ADR-034 guard); every add and remove is a timeline line ("<name> added as CC by <agent>", "... (this reply only)"). Server-side validation of every rule; no CC on internal notes.
- **Change (contacts of another organisation)**: an address that belongs to a contact of another organisation is no longer refused; on that ticket it is treated exactly like an unknown outside address (same answer in the CC check, needs "Add anyway?", then emailed as an outside participant, never linked to their account, no portal access to the ticket; their email replies thread onto that ticket only, as an outside participant). This avoids revealing that an address is registered with another client. Agent and admin accounts, deactivated contacts of the ticket's own organisation and support mailboxes are still refused.

### ADR-036 Compact density on desktop; large touch targets only on touch screens - **Accepted**

- **Context**: applying a 48 px minimum to every control on every device made desktop forms tall (about 60-65 px per input), short values sat in full-width boxes and dialogs scrolled.
- **Decision**: shared form and control styles use a compact desktop size (about 36-40 px controls, tighter label/help spacing) for fine pointers, and keep 44-48 px targets under `@media (pointer: coarse)` (phones, tablets). WCAG 2.2 AA target size (2.5.8, 24 px minimum) stays met everywhere. Inputs are sized to their content (port, security, numbers, dates narrow; hosts and emails medium; free text full width). Long forms use sections side by side on wide screens (for example the mailbox dialog: Incoming | Outgoing), radio groups inline, help text shortened to one line with detail behind an info toggle, and rarely used fields under a collapsed "Advanced".
- **Consequences**: an app-wide visual change; see [ui-design.md](ui-design.md).

### ADR-037 Address move of the original installation - **Accepted**

- Deployment-specific decision for the original installation; not relevant to this repository.

### ADR-038 Build the Admin - Customers screen - **Accepted**

- **Decision**: an admin-only Customers screen across all client organisations: search (name, email), filter by organisation and status, a detail panel (organisation, sign-in methods, last sign-in, tickets raised, invited/verified state), and admin actions - resend invitation, send password reset, force sign-out (end all sessions), deactivate / reactivate, move to another organisation (re-checks ticket participation as the organisation-move rules do), and erase (privacy request; existing erase endpoint, fresh step-up, typed confirmation). Every action is server-side admin-only, step-up where the spec requires it, audit-logged, and visible to agents nowhere. The agent top-menu Customers page stays as it is.

### ADR-039 Faster email pickup (step 1); Microsoft 365 gets its own Graph connector later - **Accepted**

- **Context**: an email could take several minutes to become a ticket. Measured: the mail provider receives in about 15 s; normal replies were processed 15-60 s later (60 s poll); a 4.2 MB email with 9 attachments took about 3 minutes because attachments were processed before the ticket existed, on the single shared scheduler thread, in a 1 GB container on 2 CPUs. For comparison, common helpdesks poll every minute or use forwarding; modern integrations use Gmail API watch/Pub/Sub or Microsoft Graph change notifications.
- **Decision (step 1, now)**: poll every 30 s; the mail poller gets its own scheduler thread so SLA and other jobs never wait behind it; the ticket or comment is created first and attachments are processed right after (with a visible "processing attachments" state and no lost files on failure); attachment processing made cheaper (measure first; e.g. subsampled image decoding, content-type detection without full parsing); api container memory 1 GB -> 2 GB.
- **Later**: step 2 IMAP IDLE for near-instant pickup on IMAP mailboxes (polling stays as fallback); step 3 a separate **Microsoft 365 connector via Microsoft Graph** (read with change notifications, send with Graph sendMail, OAuth app registration, subscription renewal) because Microsoft has retired basic authentication for IMAP/POP and is retiring it for SMTP AUTH; the existing IMAP OAuth2 client-credentials option stays for reading.

### ADR-040 A ticket an agent opens for a client is emailed like an agent reply - **Accepted**

- **Context**: a ticket an agent created for a client sent the generic confirmation ("Thank you. We have received your request ... This is what you sent: <the agent's text>"), which reads oddly when the agent wrote the text.
- **Decision**: for tickets an agent creates for a client (source = agent, F2), the client email is sent like a public reply: From the agent's display name with the support name, Reply-To the support mailbox, a short opening line ("We've logged your request as <REF>.") followed by the agent's description as the agent's own message (no "This is what you sent"), the agent's full signature, and the usual reference footer. It goes through the same outbound path as replies (mailbox SMTP per ADR-034, reserved-domain guard) and appears on the ticket as the first public message from the agent. Tickets raised by the client (portal, email, API) keep the existing "Thank you, we received your request" confirmation.

### ADR-041 Closing a ticket needs a reason; admins can reopen a closed ticket - **Accepted**

- **Context**: Closed was final (spec 11a: a Closed ticket refuses every change except internal notes; a client reply creates a follow-up), so a close by mistake could not be undone, and nothing recorded why a ticket was closed.
- **Decision**: setting a ticket to Closed (status dropdown or any close action) opens a dialog with a required reason from a fixed list - Duplicate (switches to the existing "mark as duplicate" picker), Spam / junk (offers "block this sender" via the block list, admin only), Resolved - no follow-up needed, Client cancelled / no longer needed, No response from client, Other (note required, 3-500 characters) - plus an optional internal note, and a clear warning that agents cannot reopen it. The reason is stored server-side (required by the API for manual closes), shown as a timeline line ("Closed by <agent>: <reason>"), included in the CSV export and available to reports. Automatic closes (Resolved -> Closed after 5 days, duplicates) record their own system reason. **Admins can reopen** a closed ticket with a required reason: step-up, audit event, timeline line ("Reopened by <admin>: <reason>"), status back to Open, SLA resolution clock carries on as for a client reopen. Agents still cannot reopen.

### ADR-042 SLA panel with countdown, met and breached on the ticket page - **Accepted**

- **Context**: the ticket page showed only a small SLA badge.
- **Decision**: an agent-only "SLA" card with one row per target (First response, Resolution): running = live countdown ("3h 20m left", amber from the existing warn point, red near the end), paused = "Paused" with time left frozen, outside business hours = clock stopped until the next opening (P1 24/7 always runs), met = green tick with the time taken, breached = red cross "breached by <duration>" kept after resolution; a tooltip/details line shows the target, due time and policy. The server computes remaining business time and the state (no client-side business-hours maths); the page ticks between refreshes and re-syncs on change. Never shown to clients. Consistent with the queue SLA column and the Reports page.
- **Change (placement)**: the SLA card sits at the top right of the ticket header rather than in the Details panel, so agents notice it; content unchanged, breached shown as "<time> over" with a Breached tag.

### ADR-043 People on CC of a client's email become ticket participants by default - **Accepted**

- **Context**: same-organisation CCs were added automatically and CCs from another domain waited for an agent to confirm them; clients expected everyone they copied to stay on the ticket.
- **Decision**: when the inbound client email is **verified** (DMARC pass from a trusted authserv-id) every CC address becomes a **confirmed** participant automatically - on new tickets and on client replies - so replies go to them like the sender; when the email is **not verified**, CC addresses are added as unconfirmed (waiting for an agent), because otherwise a forged email could add outsiders and receive client data. Always excluded: our own support and +internal addresses, staff/agent accounts, other organisations' contacts, reserved test domains; at most 10 participants per ticket (extra CCs listed in a hidden system note). Each addition is a timeline line ("<name> added as CC (from the client's email)"). Triage tickets follow the same rule; moving a ticket to another organisation re-checks participants.

### ADR-044 Demo data reset - **Accepted**

- Deployment-specific decision for the original installation; not relevant to this repository. Generic lesson: a data reset must keep rows that migrations seed (for example the `TRI` row in `ticket_counter`; check every `INSERT INTO` under `db/migration`), and must be followed by a smoke test of inbound email, a portal ticket and an invitation email, not only row counts.

### ADR-045 Web UI shows the support name from settings - **Accepted**

- **Context**: emails used the configured support name, but the web UI showed fixed text ("Support desk" in the top bar and sign-in card, a fixed product name in the browser tab). Options: A the UI reads the name from settings, B leave the fixed label. Chosen: A.
- **Decision**: the top bar, sign-in screens and browser tab title show the configured support name (Branding / signature support name, falling back to `MAIL_SUPPORT_NAME`, then a neutral default "Support desk"). The name is public, so an unauthenticated read-only endpoint returns only the display name. No product or client name is written into the code.

### ADR-046 Account mail through the connected mailbox's SMTP; no 6-digit code on agent invitations - **Accepted**

- **Context**: invitations were "sent" but never arrived: account mail (invitations, password reset, setup and magic links, security notices) used the server SMTP from the environment, which in a fresh installation is often only a mail catcher, while ticket mail already went through the connected mailbox's own SMTP (ADR-034). The 6-digit code on agent invitations also had to be passed on separately, which slowed onboarding. Options: A configure the server SMTP separately for account mail, B copy links from the mail catcher, C send account mail through the connected mailbox and accept an invitation with the link alone.
- **Decision 1 (mail route)**: account mail goes through the SMTP of the connected mailbox (the first enabled mailbox with working SMTP settings, lowest id), From = that mailbox's address and the support name; if no mailbox has SMTP set, it falls back to the server SMTP from the environment (unchanged behaviour). Reserved test domains are still never sent. This overrides the ADR-034 split for account mail only.
- **Decision 2 (invitation)**: agent and admin invitations no longer need the 6-digit code; accepting means opening the link (valid 48 hours, single use) and setting a password. Overrides ADR-009 C7 on this point.
- **Accepted risk**: anyone who obtains the invitation email within 48 hours can activate the account (possibly an admin account). Kept as mitigations: sending or resending an agent invitation still needs the inviting admin's step-up (authenticator code), links are single use and stored hashed, re-inviting voids the old link, admins can revoke a pending invitation, and the two-step sign-in rules for the new account are unchanged (an agent sets up TOTP at first sign-in). This risk is also stated in [35-security.md](35-security.md).
- **Trade-off of Decision 1**: whoever controls the sending mailbox (its provider, or an admin who can change its SMTP settings with step-up) can see sign-in links and resets in its Sent folder, and a broken mailbox delays them until it is fixed.

### ADR-047 Queue shows the first-response countdown; triage tickets get an SLA - **Accepted**

- **Decision 1 (queue)**: the agent queue's Organisation column is replaced by "First response": the time left (e.g. "1h 25m"), "Met", "<time> over" when breached, "Paused", or a dash when not applicable. Under 10 minutes left the value turns red (with an icon and hidden text, never colour alone); breached is red too. The organisation name moves to the grey line under the subject. The queue reloads its data every minute (paused while the browser tab is hidden, no flicker, no lost scroll position or selection) and the countdown is recomputed on each reload.
- **Decision 2 (triage SLA)**: a ticket in triage (no organisation) runs the built-in default SLA policy and business hours - the same defaults a new organisation gets - so it has First response and Resolution targets and the SLA card shows on its page. When it is moved to an organisation, its targets are recalculated under that organisation's policy from the original creation time (the clock is not restarted). Reports treat triage SLA like any other.

### ADR-048 Admins can turn client emails on or off per organisation - **Accepted**

- **Options**: per organisation, one global setting, global default + override. Chosen: per organisation, for all four switchable emails.
- **Decision**: Admin - Organisations - <client> gets a "Client emails" section with on/off for: **Ticket received** (confirmation with the reference), **Resolved notice** (manual or auto-resolve), **Closed notice** (auto-close), **Pending reminder** (already switchable; moves into the same section). Default on for every existing and new organisation (no behaviour change until an admin switches one off). Agent public replies and the agent-opened ticket email are never switchable (they are the conversation). Triage tickets (no organisation) keep sending as before. When an email is skipped because it is switched off, the ticket timeline gets one slim system line ("Resolved notice not sent - turned off for <organisation>") so agents are not surprised. Changes are audited (admin config audit event). No step-up (not a security setting).

### ADR-049 SLA automation rules defined by admins (v1) - **Accepted**

- **Options**: escalate at risk, raise priority on breach, client "met" email, admin-defined rules. Chosen: admin-defined rules.
- **Decision (v1)**: Admin - SLA automation: a list of rules (name, enabled, position). Each rule = **trigger** + optional **conditions** + one or more **actions**.
  - Triggers: First response at risk (80%), First response breached, First response met, Resolution at risk, Resolution breached, Resolution met - detected by the existing SLA evaluation (minute job and after-commit reactions), at the moment the ticket's stored SLA state changes.
  - Conditions (all optional, combined with AND; empty = any): organisations (including Email triage), priorities, request types.
  - Actions: notify chosen agents (email + bell, as an SLA notification); notify every agent of the ticket's organisation; assign to a chosen agent only if the ticket is unassigned (skipped with a timeline line if the agent cannot work that organisation or is deactivated); raise priority one level (never above P1; SLA is recalculated as for a manual change); add an internal note with fixed text.
  - Safety: a rule fires at most once per ticket, per target and per trigger (recorded, so restarts and recalculations never repeat it and rules cannot loop, including after a priority raise); rules run in position order; every action appears in the ticket timeline as done by "Automation (<rule name>)" and in the audit log; disabled rules do nothing; deleting a rule keeps its history. Admin only; no step-up. Existing built-in SLA notifications (ADR-017) are unchanged.
  - Out of v1 (Later): time-based triggers ("X minutes before due"), OR conditions, actions on client email, webhooks/Teams.

### ADR-050 Public release from cleaned snapshots - **Accepted**

- **Decision**: the project is developed in a private repository and published here as cleaned snapshots under the **Apache-2.0** licence. An export script leaves out material that only concerns the original installation and refuses to publish if a secret scanner or a denylist matches. CI for this repository runs on GitHub Actions. How the project was built is described in [how-this-was-built.md](how-this-was-built.md).
- **Engineering rules adopted before the first snapshot**:
  - Every client-data write carries the scope condition in the statement itself (`ScopeSql.ticketVisible` / `ScopeSql.ticketInOrganisation` / `ScopeSql.organisationOrTriage`); an early Java check on a caller-supplied organisation id may stay but is never the only guard. A write that finds no visible row answers 404 (never 403, never a silent success). Admin-only and system-only writes are exempt because those scopes see every organisation. See [40-architecture.md](40-architecture.md) section 4.
  - A contact of another organisation added as CC is handled as an ordinary outside address (ADR-035 change), so neither the CC check nor later client emails reveal another client's contacts.
  - Agent public replies and internal notes share a per-agent rate limit; tests avoid sleep-based timing; the project starts locally with one command ([guides/local-development.md](guides/local-development.md)).
  - Invitation-link activation (ADR-046) stays as decided and is stated in the public security notes.
