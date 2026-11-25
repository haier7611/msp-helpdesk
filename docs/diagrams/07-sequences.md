# 07 - Sequences

What this shows: six flows step by step, with the actors and the system parts that take part. The compact sign-in sequence in [docs/40-architecture.md](../40-architecture.md) section 2 is the one-page summary; the first two diagrams here split it into password and Microsoft.

Reflects: [ADR-006](../10-decision-log.md), [ADR-025](../10-decision-log.md) sign-in; [ADR-017](../10-decision-log.md) SLA; [ADR-018](../10-decision-log.md) portal; [ADR-014](../10-decision-log.md) threading. Spec: [section 7 Sign-in](../15-product-spec.md), [section 4](../15-product-spec.md), [section 11c](../15-product-spec.md). Security: [docs/35-security.md](../35-security.md) (MFA, step-up).

## 1. Password and MFA sign-in, with step-up

```mermaid
sequenceDiagram
  autonumber
  participant U as Agent (browser)
  participant API as api
  participant DB as Postgres
  U->>API: POST /auth/sign-in (email, password)
  API->>DB: back-off check, Argon2id verify, audit
  alt trusted device cookie valid
    API-->>U: FULL session, no step-up credit
  else no TOTP enrolled yet
    API-->>U: MFA_ENROLMENT_REQUIRED (QR, 10 recovery codes)
  else
    API-->>U: MFA_REQUIRED (finish within 10 min)
    U->>API: POST /auth/mfa/verify (TOTP) or /mfa/recovery
    API->>DB: advance last_used_step (each code works once)
    API-->>U: FULL session, step-up fresh for 10 min
  end
  Note over U,API: Sensitive admin action later
  U->>API: request (role change, API key, mailbox secret ...)
  API-->>U: 403 step_up_required if last TOTP older than 10 min
  U->>API: POST /auth/step-up (TOTP)
  API-->>U: retry succeeds
```

Customers go straight to FULL after the password (no MFA). Details: [docs/35-security.md](../35-security.md) "MFA, step-up, devices and sessions".

## 2. Microsoft sign-in

```mermaid
sequenceDiagram
  autonumber
  participant U as User (browser)
  participant API as api
  participant E as Microsoft Entra
  U->>API: POST /auth/microsoft/sign-in
  API-->>U: authorizationUrl (state, nonce, PKCE kept in session)
  U->>E: sign in
  E-->>U: redirect with code
  U->>API: GET /auth/microsoft/callback
  API->>E: exchange code, fetch signing keys
  API->>API: verify token, find user by (tid, oid) only
  alt agent, tid is the MSP tenant, amr has mfa
    API-->>U: FULL session
  else customer, tid is the organisation's tenant
    API-->>U: FULL session
  else any other agent token
    API-->>U: TOTP still required
  end
```

The feature is off until an admin configures and tests it (spec 11c). Entra's MFA never counts as a step-up.

## 3. Client raises a ticket in the portal

```mermaid
sequenceDiagram
  autonumber
  participant C as Client (portal)
  participant API as api
  participant DB as Postgres
  participant OB as outbox-worker
  participant M as SMTP (Mailpit in dev)
  C->>API: POST portal ticket (type, subject, description, files, idempotency key)
  API->>API: AccessScope = the client's organisation
  API->>DB: one transaction: ticket, reference from counter, event, SLA due times, portal_submission
  API->>DB: auto-assign (request type default, then organisation default)
  API->>DB: queue confirmation in outbox_message
  API-->>C: "Ticket ACD-001 received, we will reply by email"
  OB->>DB: claim queued row
  OB->>M: send confirmation with [ACD-001] in the subject
  Note over API,DB: Agents are notified (in-app, email per preferences, P1 to all)
```

## 4. Engineer replies and the client is emailed

```mermaid
sequenceDiagram
  autonumber
  participant A as Agent (agent UI)
  participant API as api
  participant DB as Postgres
  participant OB as outbox-worker
  participant C as Client mailbox
  A->>API: POST public reply (optional new status)
  API->>API: AccessScope check, sanitise HTML, bind attachments
  API->>DB: public_comment, event, first response time, SLA recalculated
  API->>DB: status change if chosen (e.g. Pending on client)
  API->>DB: outbox_message: new reply only, signature, [REF], In-Reply-To
  API-->>A: saved
  OB->>C: SMTP to reporter and confirmed participants
```

Internal notes follow the same path without the outbox row: they are never emailed to clients.

## 5. Inbound client reply threads to the ticket

```mermaid
sequenceDiagram
  autonumber
  participant C as Client (mail app)
  participant MB as Mailbox (IMAP)
  participant P as ImapPoller
  participant DB as Postgres
  participant A as Assignee
  C->>MB: Reply with [ACD-001] in the subject
  P->>MB: fetch new message
  P->>DB: Message-ID seen? block list? (no)
  P->>DB: find ticket by reference or outbox Message-ID
  P->>P: guards: sender is reporter or participant AND DMARC pass
  P->>DB: public_comment (client, via email), strip quoted history
  P->>DB: Pending on client moves to Open, Resolved reopens
  P->>DB: notification and "client replied" email queued for the assignee
  DB-->>A: in-app alert
```

If a guard fails the mail becomes a triage ticket instead (see [06-email-flows.md](06-email-flows.md)).

## 6. SLA job flags a breach

```mermaid
sequenceDiagram
  autonumber
  participant J as SlaMinuteJob
  participant L as scheduled_job_lock
  participant DB as Postgres
  participant OB as outbox-worker
  participant A as Assignee or organisation's agents
  J->>L: take the lock (skip if held)
  J->>DB: tickets changed or with a next 80% or breach moment due
  J->>J: recalculate due times (business calendar, pauses)
  alt 80% of a target used
    J->>DB: flag "about to breach", notification once
  else target breached
    J->>DB: flag "breached", notification once
  end
  J->>DB: Pending timers: reminder, auto-resolve, auto-close
  DB->>OB: queued emails (assignee preferences)
  OB->>A: email and in-app alert
```

Customers never see any of this (spec 4.7).
