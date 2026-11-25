# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/). Changes arrive as fragments in `changelog.d/` and are folded in at release (see [changelog.d/README.md](changelog.d/README.md)).

## [Unreleased]

## [0.1.0] - 2026-11-25 - first public release

### Added

- Multi-client helpdesk: client organisations, contacts and agents, with every organisation's tickets, people and files isolated from the others, enforced in the SQL of every client-data query and checked by architecture tests.
- Ticket intake from a customer portal, from email (IMAP polling of one or more support mailboxes, replies threaded by per-organisation ticket reference) and from monitoring tools through an API with per-organisation API keys.
- Agent queue and ticket page: public replies and internal notes with images, CC handling, saved replies, email signatures, assignment with a timeline, statuses including "Pending on client" and "Pending on vendor", close reasons and admin reopen.
- Outbound email through each support mailbox's own SMTP account, with an outbox, retries and per-organisation switches for client emails.
- SLA engine: first-response and resolution targets per priority and organisation, business calendars, pausing while pending, at-risk and breach notifications, an SLA panel, an SLA report, and admin-defined automation rules (notify, assign, raise priority, add a note).
- Sign-in: local accounts with a breached-password check, two-step sign-in (authenticator app, recovery codes, trusted devices), step-up before sensitive admin actions, magic links for clients, and optional Microsoft Entra ID single sign-on configured in the app.
- Admin area: organisations and verified domains, agents and contacts, request types, SLA policies and calendars, mailboxes, branding, block list, audit log.
- Data protection: audit log with allow-listed details, scheduled retention with a dry-run mode, erasure of a person's data, encryption of stored secrets with data-key rotation.
- Operations: Docker Compose stack, one-command local start with synthetic demo data (`scripts/dev-up.sh --demo`), nightly backups with a restore drill, and CI on GitHub Actions with a dependency vulnerability scan.
