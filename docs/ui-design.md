# UI design

## Design reference

Brand colour `#2B5797`, fonts Inter and Space Grotesk (self-hosted). Tokens and components: `web/src/styles/tokens.css` and `web/src/components/`. Copy is British English in plain words; controls meet WCAG 2.2 AA (contrast, real buttons and labels, target sizes from `--control-size` and `--choice-size`, larger under `@media (pointer: coarse)`).

## Live screens by role

Routes come from `web/src/routes.tsx`. Some entries in `web/src/navigation.ts` are placeholders and are marked below.

### Everyone (public)

| Route | Screen |
|---|---|
| `/sign-in` | Sign in (password, Microsoft button when switched on, customer magic link) |
| `/forgot-password`, `/reset-password` | Password reset request and confirm |
| `/magic-link` | Customer one-time link landing |
| `/accept-invitation` | Accept an invitation (set a password; no code, [ADR-046](10-decision-log.md)) |
| `/setup` | First-admin setup (needs the host setup token; refuses once an admin exists) |
| `/mfa/enrol`, `/mfa/verify` | Authenticator enrolment and code entry (agents) |
| any other path | Not found page |

### Client (`/portal`)

| Route | Screen |
|---|---|
| `/portal/new-ticket` | New ticket form (raise-only, [ADR-018](10-decision-log.md)) |
| `/portal/account` | My account |

### Engineer and admin (`/agent`)

| Route | Screen |
|---|---|
| `/agent/queue` | Queue with filters, export and the triage view |
| `/agent/tickets/new` | New ticket for a contact |
| `/agent/tickets/:ref` | Ticket page: conversation, composer (reply or internal note), timeline, SLA, status and assignment, duplicates and links |
| `/agent/customers` | Customers: search, create, invite contacts |
| `/agent/organisations` | Organisations the engineer is assigned to (read only) |
| `/agent/reports` | SLA report (filters, summary tiles, breaches, times, by engineer) with CSV export |
| `/agent/settings/profile`, `security`, `notifications`, `preferences`, `out-of-office` | My settings |
| `/agent/settings/saved-replies` | My saved replies (placeholder page) |

A restricted engineer sees the same screens limited to their organisations.

### Admin (`/admin`)

| Route | Screen |
|---|---|
| `/admin/supporters` | Agents: list, side panel, invite, role, deactivate wizard, two-step exemption |
| `/admin/organisations` | Organisations, prefixes, verified domains, calendar, timers, default assignee, client email switches |
| `/admin/customers` | Customers across organisations and admin actions ([ADR-038](10-decision-log.md)) |
| `/admin/sign-in` | Microsoft sign-in settings and Test sign-in |
| `/admin/request-types` | Request types |
| `/admin/sla` | SLA policies |
| `/admin/sla-automation` | SLA automation rules ([ADR-049](10-decision-log.md)) |
| `/admin/email` | Mailboxes, sender rules, email settings |
| `/admin/block-list` | Block list and Blocked mail log |
| `/admin/branding` | Branding and company signature template |
| `/admin/audit-log` | Audit log viewer and CSV export |
| `/admin/saved-replies` | Shared saved replies |

API keys have no screen yet (`FEATURES.apiKeys` is off in `web/src/navigation.ts`); admins manage them through the API ([45-integrations-api.md](45-integrations-api.md)).

## Errors

No silent failures: every user action that fails shows a pop-up error (`useReportError()` / `showError`, top of the screen, stays until closed) as well as any inline field error, worded by the API client's status mapping (`web/src/api/client.ts`); background refreshes (the bell count) never pop up - they show a small "Couldn't refresh" hint instead.

## Screens not built yet

Customer ticket view (Later, [ADR-018](10-decision-log.md)), knowledge base, CSAT, time tracking, Teams and Slack notifications.

## Keeping this page right

Add a row in the same pull request that adds a route. Screenshots belong in the pull request description, not in the repository.
