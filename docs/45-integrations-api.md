# Integrations API - guide for monitoring tools

How a monitoring tool (for example a synthetic "Example Monitor" watching Acme Dental's servers) opens and updates tickets. Spec sections 2, 4 flow 3 and 7 "API" ([product spec](15-product-spec.md)); OpenAPI in `api/src/main/resources/openapi/integrations.yaml`; security notes in [35-security.md](35-security.md).

## Get a key

An admin creates a key for one organisation through the admin API: `POST /api/v1/admin/organisations/{id}/api-keys` from a signed-in admin session with a fresh step-up (there is no screen for keys yet). The full key is shown **once**:

```
tsk_ab12cd34_<43 random characters>
```

`tsk_ab12cd34` is the visible prefix shown in lists later. Only a SHA-256 hash of the key is stored, so a lost key cannot be recovered - create a new one and revoke the old one. A key can have an expiry date; "last used" shows when it last worked (to the minute).

## Send an alert

```sh
curl -sS https://helpdesk.example/api/v1/integrations/tickets \
  -H "Authorization: Bearer $TS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"externalId":"acd-srv01-disk","title":"SRV01 disk C: 95% full","description":"<p>Free space 4 GB</p>"}'
```

Keep the key in an environment variable or the tool's secret store, never in a script or a URL.

| Field | Required | Meaning |
|---|---|---|
| `title` | yes | Ticket subject, plain text, up to 250 characters |
| `description` | no | HTML (cleaned with the same allow list as replies) or plain text |
| `externalId` | no | Your id for this alert, up to 200 characters. Repeats with the same id are folded into one ticket |
| `resolved` | no | `true` when the alert has cleared |

Any other field (an organisation, a priority) is ignored: the ticket always belongs to the key's organisation, uses request type "Alert" and its starting priority (agents change priority).

The answer never contains ticket content - only:

```json
{"reference":"ACD-014","ticketId":1234,"result":"CREATED"}
```

| `result` | When | HTTP |
|---|---|---|
| `CREATED` | New ticket (no `externalId`, or no ticket for it yet) | 201 |
| `UPDATED` | Repeat added as a comment to the open ticket | 200 |
| `REOPENED` | Repeat on a Resolved ticket: comment added, ticket back to Open | 200 |
| `RESOLVED` | `resolved: true` on an open ticket: comment added, ticket Resolved | 200 |
| `FOLLOW_UP_CREATED` | Repeat after the ticket was Closed: a new linked follow-up ticket, which takes over the `externalId` | 201 |
| `IGNORED` | `resolved: true` with no open ticket for that id - nothing changes | 200 |

A repeat alert also moves a Pending (client or vendor) ticket back to Open so the SLA clock resumes, like a client reply; New and Open stay as they are.

All keys of one organisation share the `externalId` space, so replacing a key does not break the folding. Different organisations never share tickets.

## Limits and errors

- 60 requests a minute per key; the 61st gets `429` with `Retry-After` (seconds). Back off and retry.
- Body up to 256 KB, else `413`.
- Missing, wrong, expired or revoked key: `401` with the same body every time.
- Invalid JSON or a missing title: `400` (the input is not echoed back).
- There is no way to read tickets with a key, and a key does not work anywhere else in the API.
- No HMAC request signing ([ADR-009](10-decision-log.md) C16); always use HTTPS. Rate limits are per api instance, and there is no IP allow list per key.

## Email

API alert tickets send **no client email** (created, public reply, resolved, or the timer notices) unless the admin turns on "API alert client emails" for the organisation (`organisation_sla_settings.api_alert_client_emails`, default off; spec 3). Agents are notified as for any new ticket.
