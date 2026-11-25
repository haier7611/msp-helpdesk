# Architecture overview

One page, C4-lite: who uses the system, what runs where, how people sign in, and how the data hangs together. Synthetic names only. Decisions are in [docs/10-decision-log.md](10-decision-log.md); detailed diagrams (containers, deployment, data model by area, lifecycle, email, sequences, security, CI, backup) are in [docs/diagrams/00-index.md](diagrams/00-index.md); detail is in the [spec](15-product-spec.md), [security](35-security.md) and [reliability](30-reliability.md) docs.

## 1. Context and deployment

```mermaid
flowchart LR
  subgraph People
    C["Clients (customers)"]
    S["Agents (engineers and admins)"]
    A["Admins"]
  end
  subgraph Outside["Outside systems"]
    MON["Monitoring tools (API keys)"]
    MP["Mail provider (IMAP in, SMTP out)"]
    ENT["Microsoft Entra (OIDC sign-in)"]
    PRB["Uptime probe (optional)"]
  end
  EDGE["HTTPS reverse proxy / TLS edge (any)"]
  subgraph HOST["Docker host, compose project ticketsys"]
    WEB["web: Caddy + React app"]
    API["api: Spring Boot"]
    PG[("Postgres 16")]
    ATT[("attachment volume")]
    MAILP["Mailpit (dev only)"]
  end
  BK[("Backups, separate store")]
  C & S & A --> EDGE
  MON --> EDGE
  PRB --> EDGE
  EDGE --> WEB
  WEB -->|"/api and /actuator/health"| API
  API --> PG
  API --> ATT
  API -->|"dev: all outbound mail"| MAILP
  API <-->|"IMAP poll, SMTP send"| MP
  API <-->|"authorisation code + PKCE"| ENT
  PG -.->|"nightly dump"| BK
  ATT -.->|"nightly archive"| BK
```

- **Clients** raise tickets through the portal, by email or (monitoring tools) the API; they never see a ticket page ([ADR-018](10-decision-log.md)). **Agents** work the queue; **admins** also manage organisations, SLAs, mailboxes, API keys and sign-in settings.
- **Containers** (compose project `ticketsys`): `ticketsys-web` (Caddy serving the React build, proxying `/api` and `/actuator/health`), `ticketsys-api` (Spring Boot 4.1, Java 21), `ticketsys-postgres` (Postgres 16, named volume, no host port), the attachment disk volume mounted at `/data/attachments`, and **Mailpit** in the `dev` profile, which catches all outbound mail.
- **Inside the api**: the IMAP poller and the scheduled jobs (outbox sender, SLA timers, reminders, notification digest, retention) run in the same process; there is no separate worker. Job locks (`scheduled_job_lock`, `mail_poller_lock`) keep a second instance from doubling work.
- **Edge**: the web container publishes one HTTP port, bound to `127.0.0.1` by default (`WEB_BIND`, `WEB_PORT`). Put any HTTPS reverse proxy or TLS terminator in front of it (nginx, Caddy, a cloud load balancer, a tunnel). The api and Postgres publish no ports. Caddy and the api trust `X-Forwarded-*` only from private and loopback addresses, so the web port must not be reachable from untrusted networks ([security](35-security.md)). An uptime probe can watch the public health URL ([reliability](30-reliability.md)).
- **CI**: GitHub Actions builds and tests every pull request ([test strategy](20-test-strategy.md)); it does not deploy.

## 2. Sign-in

Levels of a session are kept server-side (Spring Session JDBC): not signed in, half-signed-in (waiting for MFA set-up or code), and FULL. Only FULL reaches `/portal`, `/agent` and `/admin`.

```mermaid
sequenceDiagram
  autonumber
  participant U as User (browser)
  participant API as api
  participant DB as Postgres
  participant E as Microsoft Entra
  U->>API: POST /auth/sign-in (email, password)
  API->>DB: check Argon2id hash, back-off, audit
  alt customer
    API-->>U: FULL session
  else engineer or admin
    alt trusted device cookie valid
      API-->>U: FULL session (no TOTP, no step-up credit)
    else no TOTP yet
      API-->>U: MFA enrolment required (QR, 10 recovery codes)
    else
      U->>API: POST /auth/mfa/verify (TOTP) or /mfa/recovery (one-time code)
      API-->>U: FULL session, step-up fresh for 10 min
    end
  end
  Note over U,API: Sensitive admin action (role change, invite, API key, mailbox secret, ...)
  U->>API: request
  API-->>U: refused until step-up, if the last TOTP is older than 10 min
  U->>API: POST /auth/step-up (TOTP)
  API-->>U: retry succeeds
  Note over U,E: Microsoft path (admin-configured, off by default, ADR-025)
  U->>API: POST /auth/microsoft/sign-in
  API-->>U: authorizationUrl (state, nonce, PKCE in session)
  U->>E: sign in
  E-->>U: redirect with code
  U->>API: GET /auth/microsoft/callback
  API->>E: exchange code, fetch signing keys
  API->>API: verify token, look up user by (tid, oid) only
  alt agent and tid = MSP tenant and amr has mfa
    API-->>U: FULL session
  else customer and tid = organisation's tenant
    API-->>U: FULL session
  else any other agent token
    API-->>U: TOTP still required (as after a password)
  end
```

`security_version` on each user is bumped by a role, access, MFA or password change and by deactivation; that ends their other sessions at once. Entra's MFA is never counted as a step-up. An admin can pause two-step sign-in for one engineer for up to 30 days ([ADR-031](10-decision-log.md), [security](35-security.md)).

## 3. Data model (main tables)

Names are from the Flyway migrations in `api/src/main/resources/db/migration`. Not every column or table is shown (also: `user_mfa`, `user_device`, `auth_token`, `supporter_*`, `saved_reply`, `signature_template`, `entra_settings`, `sender_rule`, `mail_block_rule`, `ticket_participant`, `ticket_link`, `api_key`, `sla_automation_rule`).

```mermaid
erDiagram
  organisation ||--o{ app_user : "customers belong to"
  organisation ||--o{ ticket : owns
  organisation ||--o{ organisation_prefix : "reserves"
  organisation ||--o{ organisation_domain : "verified domains"
  organisation ||--o{ sla_policy : has
  organisation ||--o{ organisation_business_hours : "calendar"
  organisation ||--o{ organisation_holiday : "calendar"
  sla_policy ||--o{ sla_policy_target : "per priority"
  request_type ||--o{ ticket : classifies
  request_type ||--o{ sla_policy : "optional match"
  app_user ||--o{ ticket : "reporter, assignee"
  ticket ||--o{ ticket_reference : "primary + aliases"
  organisation_prefix ||--o{ ticket_reference : "prefix"
  ticket ||--o{ ticket_event : history
  ticket ||--o{ public_comment : "client-visible"
  ticket ||--o{ internal_note : "agents only"
  ticket ||--o{ system_entry : "hidden, e.g. triage reason"
  public_comment ||--o{ attachment : may_have
  internal_note ||--o{ attachment : may_have
  ticket ||--o{ attachment : bound_to
  app_user ||--o{ notification : receives
  ticket ||--o{ notification : about
  mailbox ||--o{ inbound_mail : "polled from"
  ticket ||--o{ inbound_mail : "created or joined"
  ticket ||--o{ outbox_message : "mail about"
  app_user ||--o{ audit_event : "actor, target"
  app_user ||--o{ external_identity : "Microsoft (tid, oid)"
  organisation {
    bigint id PK
    varchar name
  }
  app_user {
    bigint id PK
    varchar role "CUSTOMER AGENT ADMIN"
    varchar status
    bigint organisation_id FK
  }
  ticket {
    bigint id PK
    bigint organisation_id FK
    bigint reporter_user_id FK
    bigint assignee_user_id FK
    bigint request_type_id FK
    varchar priority "P1 to P4"
    varchar status
    bigint duplicate_of_id FK
  }
  ticket_reference {
    varchar prefix FK
    bigint ticket_id
    varchar kind "PRIMARY ALIAS"
  }
  inbound_mail {
    bigint mailbox_id FK
    bigint ticket_id FK
    boolean authenticated
  }
  outbox_message {
    varchar kind
    varchar status
    bigint ticket_id
  }
  audit_event {
    varchar type
    bigint actor_user_id
    bigint target_user_id
    jsonb details
  }
```

## 4. Rules that cut across everything

- **AccessScope**: every read and write of client data is scoped to the signed-in user's organisations, in SQL. ArchUnit rules (`api/src/test/java/com/ticketsystem/api/architecture/ArchitectureTest.java`) fail the build if a repository outside `..clientdata..` exists, if JDBC is used outside it (apart from a short named allow-list), or if `AccessScope.system` is called from the wrong package. Internal notes are kept apart from client-visible code by the same rules. Negative fixtures prove each rule fails when broken.
  - **Writes bind the scope in SQL**: a write never trusts an organisation id the caller passed in. The UPDATE, DELETE or INSERT ... SELECT itself matches only rows whose ticket the scope sees (`ScopeSql.ticketVisible`, or `ScopeSql.ticketInOrganisation` where the row stores the organisation next to the ticket id, such as links, notifications and portal submissions). A write that matches nothing because the row is missing or belongs to another organisation answers 404, the same as a missing id; a write that matches nothing for a harmless reason (already confirmed, duplicate CC) re-checks visibility in SQL to tell the two apart. Writes that are admin-only or system-only need no extra condition, since those scopes see every organisation. Test: `ScopeBoundWriteIntegrationTest`.
- **Step-up**: sensitive admin actions need a TOTP or recovery code from the last 10 minutes; a trusted device and Microsoft sign-in never count.
- **Every email becomes a ticket** unless the block list stops it; guards (participant, DMARC) decide whether a mail joins a ticket or goes to triage ([spec 6 and 11b](15-product-spec.md)).
- **Migrations** are append-only: Flyway versions only go up and a merged migration is never edited or undone, so a rollback to older images first checks that the older code tolerates the newer schema ([reliability](30-reliability.md)).
- **Testing**: no merge without a green CI build ([test strategy](20-test-strategy.md)).

Related ADRs: [ADR-004](10-decision-log.md) stack, [ADR-005](10-decision-log.md) IMAP polling, [ADR-006](10-decision-log.md) and [ADR-025](10-decision-log.md) sign-in, [ADR-014](10-decision-log.md) references, [ADR-016](10-decision-log.md) hosting, [ADR-021](10-decision-log.md) Spring Boot 4, [ADR-026](10-decision-log.md) deploys and migration order.
