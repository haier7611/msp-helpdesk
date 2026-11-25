# 04 - Data model

What this shows: the main tables from the Flyway migrations in `api/src/main/resources/db/migration`, split by area so each diagram stays readable. Columns shown are the ones that matter for relationships and rules, not every column. The overview ER diagram on one page is in [docs/40-architecture.md](../40-architecture.md) section 3.

Reflects: [ADR-004](../10-decision-log.md), [ADR-009](../10-decision-log.md) C13 and C14, [ADR-014](../10-decision-log.md) references, [ADR-017](../10-decision-log.md) SLA, [ADR-022](../10-decision-log.md) email matching. Spec: [section 8](../15-product-spec.md), [section 10](../15-product-spec.md). Rules: every client-data query is scoped by organisation; migrations are append-only; references are made only by the counter service.

## A. Identity and sessions

```mermaid
erDiagram
  app_user ||--o| user_mfa : "TOTP secret, encrypted"
  app_user ||--o{ mfa_recovery_code : "10 per enrolment"
  app_user ||--o{ user_device : "trusted browsers"
  app_user ||--o{ auth_token : "magic link, reset, invite"
  app_user ||--o{ external_identity : "Microsoft tid + oid"
  app_user ||--o{ SPRING_SESSION : "principal name = user id"
  SPRING_SESSION ||--o{ SPRING_SESSION_ATTRIBUTES : "session data"
  app_user {
    bigint id PK
    varchar email
    varchar role "CUSTOMER AGENT ADMIN"
    varchar status "INVITED ACTIVE DEACTIVATED"
    varchar password_hash "Argon2id"
    int security_version
    bigint organisation_id FK "customers"
  }
  user_mfa {
    bigint user_id PK
    bytea secret_ciphertext
    bigint last_used_step
  }
  auth_token {
    varchar purpose
    char token_hash
    timestamptz expires_at
    timestamptz used_at
  }
  external_identity {
    uuid tenant_id
    uuid object_id
  }
```

## B. Organisations and access

```mermaid
erDiagram
  organisation ||--o{ app_user : "customers belong to"
  organisation ||--o{ supporter_organisation : "which agents may work it"
  app_user ||--o{ supporter_organisation : "agent access list"
  organisation ||--o{ organisation_prefix : "reserved prefixes"
  organisation ||--|| ticket_counter : "next number, row lock"
  organisation ||--o{ organisation_domain : "verified email domains"
  organisation ||--o{ ticket : owns
  organisation ||--o{ api_key : "monitoring keys"
  organisation {
    bigint id PK
    varchar name
    varchar timezone
    uuid entra_tenant_id "allowed tenant"
    int retention_months
  }
  organisation_prefix {
    varchar prefix PK
    varchar status
  }
  ticket_counter {
    bigint organisation_id
    bigint next_number
  }
  supporter_organisation {
    bigint user_id
    bigint organisation_id
  }
  api_key {
    varchar key_id "visible, unique"
    char secret_hash "SHA-256"
    timestamptz expires_at
    timestamptz revoked_at
  }
```

## C. Tickets, comments, attachments, references

```mermaid
erDiagram
  ticket ||--o{ ticket_reference : "primary + aliases"
  organisation_prefix ||--o{ ticket_reference : "prefix"
  request_type ||--o{ ticket : classifies
  ticket ||--o{ ticket_event : history
  ticket ||--o{ public_comment : "client-visible"
  ticket ||--o{ internal_note : "agents only"
  ticket ||--o{ system_entry : "hidden, e.g. triage reason"
  ticket ||--o{ ticket_participant : "CC and extra contacts"
  ticket ||--o{ ticket_link : "related or duplicate"
  ticket ||--o{ attachment : "bound to"
  public_comment ||--o{ attachment : "may have"
  internal_note ||--o{ attachment : "may have"
  ticket {
    bigint id PK
    bigint organisation_id FK "null in triage"
    bigint reporter_user_id FK
    bigint assignee_user_id FK
    varchar priority "P1 to P4"
    varchar status
    varchar source "PORTAL EMAIL API AGENT"
    bigint duplicate_of_id FK
    bigint follow_up_of_id FK
    varchar external_id "alert dedupe"
  }
  ticket_reference {
    varchar prefix FK
    bigint number
    bigint ticket_id
    varchar kind "PRIMARY ALIAS"
  }
  attachment {
    uuid storage_key
    varchar status "PENDING BOUND"
    bigint ticket_id FK
    bigint public_comment_id FK
    bigint internal_note_id FK
  }
```

`portal_submission (user_id, idempotency_key) -> ticket_id` makes a portal submit safe to repeat.

## D. SLA and notifications

```mermaid
erDiagram
  organisation ||--o{ sla_policy : has
  request_type ||--o{ sla_policy : "optional match"
  sla_policy ||--o{ sla_policy_target : "per priority"
  organisation ||--|| organisation_sla_settings : "timers, default assignee"
  organisation ||--o{ organisation_business_hours : "weekly hours"
  organisation ||--o{ organisation_holiday : "holidays"
  ticket ||--o{ notification : "about"
  app_user ||--o{ notification : receives
  app_user ||--o{ supporter_notification : "per-event preferences"
  app_user ||--o{ notification_digest_log : "digest sent per day"
  sla_policy_target {
    varchar priority
    int first_response_amount
    int resolution_amount
    varchar clock "business or 24x7"
  }
  organisation_sla_settings {
    bool pending_reminder_enabled
    int pending_reminder_business_days
    bool auto_resolve_enabled
    int auto_resolve_business_days
    bool auto_close_enabled
    int auto_close_calendar_days
    bool api_alert_client_emails
  }
  sla_policy {
    bool pending_on_vendor_pauses
  }
```

The SLA due times, paused minutes and met flags are columns on `ticket`. `scheduled_job_lock (name, locked_until)` serialises the jobs.

## E. Mail in and out

```mermaid
erDiagram
  mailbox ||--o{ inbound_mail : "polled from"
  mailbox ||--o{ mailbox_probe : "plus-address test"
  ticket ||--o{ inbound_mail : "created or joined"
  ticket ||--o{ outbox_message : "mail about"
  outbox_message ||--o{ outbox_part : "attachments, inline images"
  ticket ||--o| ticket_mail_suppression : "no automatic mail"
  organisation ||--o{ sender_rule : "route sender"
  mailbox {
    bigint id PK
    varchar address
    bool enabled
    varchar auth_type "PASSWORD OAUTH2"
    bytea secret_ciphertext
    bigint last_uid
  }
  inbound_mail {
    varchar message_id
    varchar outcome "CREATED TRIAGE BLOCKED DUPLICATE ..."
    bool authenticated "DMARC pass"
    bytea raw_eml "90 days"
  }
  outbox_message {
    varchar message_id "threading"
    varchar kind
    varchar status "queued sending sent failed"
    bool sensitive
  }
```

## F. Admin configuration

```mermaid
erDiagram
  email_settings {
    varchar supporter_reply_mode "OFF INTERNAL_ONLY NOTES_AND_PUBLIC"
  }
  entra_settings {
    bool enabled
    uuid msp_tenant_id
    bytea secret_ciphertext
    date secret_expires_on
  }
  signature_template {
    varchar company_name
    varchar email_mode
  }
  request_type {
    varchar name
    varchar default_priority
    bool portal_visible
  }
  mail_block_rule {
    varchar kind "ADDRESS DOMAIN"
    varchar value
  }
  sender_rule {
    varchar kind
    bigint organisation_id FK
    bigint request_type_id FK
  }
  saved_reply {
    bigint owner_user_id "null = shared"
    varchar title
  }
  supporter_profile ||--|| app_user : "name line, phone"
  supporter_preferences ||--|| app_user : "theme, out of office"
  user_photo ||--|| app_user : "profile picture"
```

Single-row settings tables (`email_settings`, `entra_settings`, `signature_template`) hold one row each. Secrets are AES-GCM ciphertext with a key id (`TS_DATA_KEY`).

## G. Audit and erasure

```mermaid
erDiagram
  app_user ||--o{ audit_event : "actor"
  app_user ||--o{ audit_event : "target"
  app_user ||--o{ user_erasure : "erased by an admin"
  user_erasure ||--o{ user_erasure_address : "addresses to scrub"
  audit_event {
    varchar type
    jsonb details "field allow list"
    timestamptz created_at
  }
  user_erasure {
    bigint user_id
    bigint admin_id
    timestamptz erased_at
  }
```

The application database role can insert and read `audit_event` but cannot update or delete it; retention deletes only through the `purge_audit_events` function (spec section 10, docs/30-reliability.md).

## Table glossary

| Table | What it holds |
| --- | --- |
| `app_user` | Every account: customer, agent or admin; status; Argon2id hash; `security_version` |
| `user_mfa`, `mfa_recovery_code`, `user_device` | TOTP secret, one-time recovery codes, trusted browsers |
| `auth_token` | Magic links, password resets, invitations (hashed) |
| `external_identity` | Link from a user to a Microsoft (tid, oid) |
| `SPRING_SESSION*` | Server-side sessions (Spring Session JDBC) |
| `organisation`, `organisation_prefix`, `ticket_counter`, `organisation_domain` | Clients, their reference prefixes, per-organisation number counter, verified email domains |
| `api_key` | Monitoring-tool keys, one organisation each (hash only) |
| `supporter_organisation` | Which organisations an agent may work |
| `ticket`, `ticket_event`, `ticket_link`, `ticket_participant` | Tickets, history, related and duplicate links, CC contacts |
| `ticket_reference` | Primary reference and aliases per ticket |
| `public_comment`, `internal_note`, `system_entry` | Client-visible replies, agent-only notes, hidden system lines |
| `attachment` | File metadata; bytes live on the attachment volume |
| `request_type` | Ticket categories and starting priority |
| `sla_policy`, `sla_policy_target`, `organisation_sla_settings`, `organisation_business_hours`, `organisation_holiday` | SLA targets, calendar and Pending timers |
| `notification`, `notification_digest_log`, `supporter_notification`, `scheduled_job_lock` | In-app alerts, digest log, per-event preferences, job locks |
| `mailbox`, `mailbox_probe`, `mail_poller_lock` | IMAP accounts, plus-address test, poller lock |
| `inbound_mail`, `mail_block_rule`, `sender_rule` | Mail log and Blocked mail log, block list, routing rules |
| `outbox_message`, `outbox_part` | Outgoing mail queue and its attachments |
| `email_settings`, `entra_settings`, `signature_template`, `saved_reply` | Admin settings and canned replies |
| `sla_automation_rule`, `sla_automation_firing` | Admin SLA automation rules and their once-only firing records |
| `mfa_exemption` | Time-limited two-step exemption per engineer |
| `supporter_profile`, `supporter_preferences`, `user_photo` | My settings |
| `portal_submission` | Idempotency for the portal New ticket form |
| `audit_event`, `user_erasure`, `user_erasure_address` | Audit log and the personal-data erasure log |
