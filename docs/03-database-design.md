# Database design

PostgreSQL 16, schema `public`, managed only by Flyway (`api/src/main/resources/db/migration`). The picture of the main tables is in [40-architecture.md](40-architecture.md) section 3 and the data-model diagram is listed in [diagrams/00-index.md](diagrams/00-index.md). This page is the table-by-table guide. Long column lists are cut to the key columns; the migration file is the exact truth.

## Database roles and grants

| Role | Used by | Can do |
|---|---|---|
| Owner (`POSTGRES_USER`) | Flyway; the host scripts (backup, restore drill, export) | Everything. Owns the schema |
| `ticketsys_app` (V5) | The api at run time (password from `APP_DB_PASSWORD`) | `SELECT, INSERT, UPDATE, DELETE` on every application table and sequence (default privileges cover tables added later). **`audit_event`: only `SELECT, INSERT`** (UPDATE, DELETE, TRUNCATE revoked). Nothing on `flyway_schema_history`. Its password is set after every migrate by `ApplicationRolePasswordCallback` (client-side SCRAM, never in SQL text) |
| `ticketsys_audit_scrub` (V83, NOLOGIN) | Owner of the `SECURITY DEFINER` scrub functions only | `SELECT (id, email, erased_at)` on `app_user`; `SELECT, INSERT` and `UPDATE (details)` on `audit_event`; `SELECT, INSERT, DELETE` on `user_erasure_address`. Nobody signs in as it |

Functions the app role may call (V83): `scrub_audit_email(user_id)` (removes an erased person's address from audit details and writes `AUDIT_EMAIL_SCRUBBED`), `purge_audit_events(before, limit)` (deletes audit rows older than the cutoff, never younger ones) and `ts_erase_user(user_id, at)` (the erase itself, shared with the restore drill). The `capture_erased_address()` trigger function feeds `user_erasure_address`. After a restore with `--no-owner`, the two scrub functions must be handed back to `ticketsys_audit_scrub` (command in [30-reliability.md](30-reliability.md), "Full restore").

Rules for new tables: they inherit the app role's full CRUD through default privileges, so a table that must be append-only has to `REVOKE UPDATE, DELETE` explicitly. Client-owned tables are read and written through classes in a `..clientdata..` package that take an `AccessScope` (ArchUnit enforces it).

## Migration history

Versions are not contiguous on purpose: features were built in parallel and a migration is renamed to the next free number just before it merges (ADR-026). Highest today: **V100**.

| Version | File | Added |
|---|---|---|
| V1 | baseline | Empty marker so Flyway owns the database from the first deploy |
| V2 | outbox_message | `outbox_message` (every outbound email, retries, Message-ID) |
| V3 | auth_users_tokens_audit | `app_user`, `auth_token`, `audit_event` |
| V4 | spring_session | `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` (server-side sessions) |
| V5 | application_db_role | `ticketsys_app` role and grants, audit log append-only |
| V6 | organisations_access | `organisation`, `organisation_prefix`, `ticket_counter` (with the seeded `TRI` row), `ticket_reference`, `organisation_domain`, `supporter_organisation`, `app_user.organisation_id` |
| V20 | mfa_devices | `user_mfa`, `mfa_recovery_code`, `user_device` |
| V21 | tickets_core | `request_type`, `ticket`, `ticket_link`, `ticket_participant`, `ticket_event`, `public_comment`, `internal_note`, `system_entry`, `attachment`; `ticket_reference.issued_ticket_id` |
| V40 | supporter_settings | `supporter_profile`, `user_photo`, `supporter_preferences`, `supporter_notification`, `signature_template` |
| V60 | entra_sign_in | `entra_settings`, `external_identity` |
| V61 | sla_policies_calendars | `organisation_sla_settings`, `organisation_business_hours`, `organisation_holiday`, `sla_policy`, `sla_policy_target`; SLA and assignment columns and indexes on `ticket` and `request_type` |
| V62 | notifications_jobs | `notification`, `notification_digest_log`, `scheduled_job_lock` |
| V63 | email_in_out | `mailbox`, `email_settings`, `inbound_mail`, `mail_block_rule`, `sender_rule`, `ticket_mail_suppression`, `outbox_part`, `mail_poller_lock`, `mailbox_probe` |
| V70 | saved_replies | `saved_reply` |
| V80 | portal_submissions | `portal_submission` (double-submit guard) |
| V81 | default_request_types | `request_type.description`, `sort_order`; the 8 default types plus the hidden Alert (ADR-027) |
| V82 | admin_config | `request_type.archived`; signature template logo, confidentiality line and support name; audit viewer indexes |
| V83 | hardening_go_live | `organisation.retention_months`; `user_erasure`, `user_erasure_address`; `app_user.erased_at` and nullable email; the `ticketsys_audit_scrub` role and three functions; foreign-key changes on `inbound_mail` and `ticket_mail_suppression` |
| V84 | email_follow_ups | `mailbox.smtp_host`, `smtp_port`, `smtp_security`, `smtp_last_error` (outbound mail through the admin-configured mailbox); `attachment.uploader_user_id` nullable (files from a client's email have no uploader) |
| V85 | api_keys | `api_key` (one organisation per key, SHA-256 of the key only, visible `key_id`); `ticket.created_by_api_key_id`, `public_comment.author_api_key_id` |
| V86 | qa_seed_marker | `qa_seed` - ids and short synthetic keys of rows made by a test-data seed command, so the matching wipe removes only those (ADR-030) |
| V87 | assignment_timeline | `ticket_event.reason`, `reason_ref_id` - why an assignee changed (default rule, deactivation, move), for the agent-only timeline lines (ADR-032) |
| V88 | two_step_exemption | `mfa_exemption` (one row per exempt engineer, 30-day check), the `ts_clear_mfa_exemption` trigger on `app_user` role/status, `user_device.trust_token_hash`; clears earlier device trust (ADR-031) |
| V90 | ticket_timeline | `ticket_event.actor_kind`, `old_reference`, `new_reference`; `reason` now also holds the status trigger and participant/link/reporter reasons; new row types PARTICIPANT_ADDED, PARTICIPANT_REMOVED, REPORTER, LINK_REMOVED; `ts_erase_user` also clears an erased address from participant rows (ADR-032) |
| V92 | reply_cc | `public_comment.cc_chosen`, `public_comment_cc` (the exact CC list a reply was sent with, `one_off` for "this reply only"); `ts_erase_user` also clears an erased address from reply CC lists (ADR-035) |
| V93 | mailbox_smtp_sign_in | `mailbox.smtp_same_sign_in` (default true), `smtp_username`, `smtp_secret_ciphertext`, `smtp_secret_key_id`; checks `mailbox_smtp_secret_complete` and `mailbox_smtp_sign_in_complete` - ticket email through the support mailbox (ADR-034) |
| V94 | ticket_event_participant_value_index | partial index `ticket_event_participant_value_ix` on `lower(new_value)` for PARTICIPANT_ADDED/REMOVED rows, so `ts_erase_user` no longer scans `ticket_event` |
| V95 | inbound_attachment_job | `inbound_attachment_job` - an email's files still to be processed after its ticket or comment was committed (comment or note, owner scope, status, attempts, lease); finished rows are deleted, FAILED ones kept (ADR-039) |
| V97 | ticket_close_reason | `ticket.close_reason`, `close_note` (the current close); `ticket_event.close_reason`, `note` (every close and an admin's reopen reason) (ADR-041) |
| V98 | ticket_sla_work_list_index | partial index `ticket_sla_work_ix` matching the SLA minute job's work list word for word (also covers triage tickets); drops `ticket_sla_stale_ix` (ADR-047) |
| V99 | organisation_client_email_switches | `organisation_sla_settings.ticket_received_email_enabled`, `resolved_email_enabled`, `closed_email_enabled` (default true) (ADR-048) |
| V100 | sla_automation | `sla_automation_rule`, `sla_automation_firing` (ADR-049) |

### The renumber-at-merge rule (ADR-026)

Flyway out-of-order stays **off**, so applied versions must only ever go up on every database. Changes merge in the order they are ready. Just before merging, if a change's migration version is not above the highest on main, the file is renamed to the highest plus 1 (rename only, no content change) and CI must be green on the renamed commit. Contributors therefore pick a number above main when they start, never edit a merged migration, and expect that the file may need renaming.

## Tables by area

"Reads / writes" names the code area, not a single class. All writes go through the app role.

### Auth and people

| Table | Purpose | Key columns | Reads / writes | Retention |
|---|---|---|---|---|
| `app_user` | Every account: customer, agent, admin | `email` (unique lower-case, nullable after erase), `role`, `status` (invited, active, deactivated), `organisation_id` (customers), `security_version`, `erased_at` | Auth, access, agent admin, erase function | Kept; an erased account stays as "Former user" |
| `auth_token` | Invitation, password reset and magic-link tokens | `token_hash` (SHA-256 only), `purpose`, `expires_at`, `used_at` | Auth services | Deleted 30 days after expiry (retention job) |
| `user_mfa`, `mfa_recovery_code` | TOTP secret (encrypted) and hashed one-time recovery codes | `user_id`, `code_hash`, `used_at` | MFA service | With the user; removed on erase |
| `user_device` | Browsers the user has signed in from, with the trusted flag | `token_hash` (known-device cookie), `trust_token_hash` (30-day trust cookie), `trusted_until`, `last_used_at` | Sign-in, security settings | Idle 400 days, swept every 6 hours; removed on erase |
| `mfa_exemption` | Time-limited two-step exemption for one engineer (ADR-031) | `user_id` (PK), `reason` (1-200), `ends_at` (at most 30 days after `granted_at`), `granted_by_user_id` | `MfaExemptionService` (admin grant/revoke, sign-in and per-request checks) | Deleted when it ends (first sign-in or request after the end date), on revoke, promotion to admin or deactivation (trigger as a safety net) |
| `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | Server-side sessions, principal name = user id | `SESSION_ID`, `EXPIRY_TIME`, `PRINCIPAL_NAME` | Spring Session | 8 h idle, 24 h absolute; cleaned every minute |
| `supporter_organisation` | Which organisations an agent may work (none listed = all) | `user_id`, `organisation_id` | Access, admin | With the user |
| `supporter_profile`, `user_photo`, `supporter_preferences`, `supporter_notification` | My settings: job title, phone, photo, theme, out of office, per-event notification choices | `user_id` | My settings API; auto-assignment reads out of office | Removed on erase |
| `entra_settings` (one row), `external_identity` | Microsoft sign-in configuration (secret encrypted) and the linked account (tenant id and object id, no email) | `enabled`, encrypted client secret; `tid`, `oid` | Entra services | Link removed on erase |
| `user_erasure`, `user_erasure_address` | Erase log (ids and time only) and a trigger-captured copy of the address used to scrub audit details | `user_id`, `erased_at` | Erase function, restore drill; the scrub role | `user_erasure` kept (replayed after a restore); the address copy is deleted after the scrub |

### Organisations and references

| Table | Purpose | Key columns | Reads / writes | Retention |
|---|---|---|---|---|
| `organisation` | A client | `name` (unique lower-case), timezone, default assignee, Pending timers, `retention_months` (default 36, allowed 12-120) | Admin writes; everything reads | Deleted only with no customers and no references |
| `organisation_prefix` | Ticket prefixes, active or retired; `TRI` is reserved | `prefix` (unique upper-case), one active per organisation | Admin, reference module | Kept (retired prefixes stay reserved) |
| `ticket_counter` | Next number per organisation plus one row for `TRI` (seeded by V6 - a data reset must keep it), locked when a ticket is created | `counter_key`, `organisation_id`, `next_number` | Reference module only | Kept |
| `ticket_reference` | Every reference ever issued, primary or alias, with the reason | `reference` (unique), `ticket_id`, `organisation_id`, `issued_ticket_id` (nullable since V83) | Reference module, mail matching | Removed with the closed ticket |
| `organisation_domain` | Verified email domains, unique across all organisations | `domain`, `organisation_id` | Admin writes, mail matching reads | Kept |

### Tickets

| Table | Purpose | Key columns | Reads / writes | Retention |
|---|---|---|---|---|
| `request_type` | Types offered on forms; sets the starting priority and an optional default assignee | `name` (unique lower-case), `default_priority`, `portal_visible`, `description`, `sort_order`, `archived` | Admin writes; portal and agent read | Archived, never deleted while in use |
| `ticket` | The ticket | `organisation_id` (null only in triage), reporter, `assignee_user_id`, `status`, priority, source, SLA due and pause columns, `external_id`, `duplicate_of_id`, `status_changed_at`, `close_reason`, `close_note` | Agent, portal create, mail, SLA jobs | Closed tickets, after the organisation's retention period |
| `public_comment`, `internal_note`, `system_entry` | Three separate tables so a note cannot leak through a shared field | `ticket_id`, author, `body_html` (sanitised), `message_id` (unique) | Agent writes all three; client replies and the first portal description go to `public_comment`; client-facing reads use only public | With the ticket |
| `public_comment_cc` | The exact CC list a public reply was sent with, when the agent chose one | comment, address (lower case), `user_id` for a contact of the ticket's organisation, `one_off` | Agent replies, mail out | With the ticket |
| `attachment` | Files and inline images, bound once to a comment | `storage_key` (UUID), content type, `status` (PENDING for 24 h), `public_comment_id` or `internal_note_id` | Attachment service; file on disk behind `BlobStore` | With the ticket; an orphan-file sweep is an open follow-up |
| `ticket_participant` | Client CC participants, confirmed or not | `ticket_id`, address or user | Mail, agent | With the ticket |
| `ticket_link` | Related, duplicate and follow-up links (same organisation only) | `low_ticket_id`, `high_ticket_id`, `kind` | Agent | With the ticket |
| `ticket_event` | Ticket history - every change, shown as agent-only timeline lines | `ticket_id`, type, actor user and kind, old/new value (ids and codes), reason, references before/after a move, close reason and note | Services append; agent reads | With the ticket |
| `saved_reply` | Canned responses; `owner_user_id` NULL = shared by all agents | `title`, `body_html` | Agent, admin | Kept |
| `portal_submission` | One row per portal form key so a double click returns the first reference | `organisation_id`, `user_id`, `ticket_id` | Portal | Not purged yet |

### SLA and notifications

| Table | Purpose | Reads / writes | Retention |
|---|---|---|---|
| `organisation_sla_settings`, `organisation_business_hours`, `organisation_holiday` | Per-organisation calendar, Pending timers and client email switches | Admin writes, SLA engine and mail read | Kept |
| `sla_policy`, `sla_policy_target` | Targets per organisation and optional request type | Admin writes, SLA engine reads | Kept |
| `sla_automation_rule`, `sla_automation_firing` | Admin-defined SLA rules (trigger, conditions and actions as validated JSON) and the unique record that a rule fired for a ticket, target and trigger | Admin writes rules; the SLA evaluation reads rules and writes firings | Rules kept; firings deleted with their rule or ticket |
| `notification` | Bell entries per recipient; no ticket content is stored; `dedupe_key` makes repeats a no-op | Notifier writes, agent reads | Not purged yet |
| `notification_digest_log` | One daily digest per agent per local date | Digest job | Not purged yet |
| `scheduled_job_lock` | Single-runner lease for scheduled jobs, on the database clock | All scheduled jobs | Kept |

### Email

| Table | Purpose | Reads / writes | Retention |
|---|---|---|---|
| `mailbox`, `email_settings` | Configured IMAP mailboxes with optional SMTP (secrets encrypted) and global mail settings | Admin writes, poller and sender read | Kept |
| `inbound_mail` | Log of every mail seen: UIDVALIDITY + UID, unique Message-ID, outcome and reason, raw `.eml` | Poller writes; the admin blocked-mail view reads | Raw `.eml` 90 days and blocked log 30 days (hourly job); rows go with a closed ticket; `ticket_id` is `ON DELETE SET NULL` |
| `mail_block_rule`, `sender_rule` | Block list and "always create for organisation X" rules | Admin | Kept |
| `ticket_mail_suppression` | Per-ticket "do not send to this address" entries | Mail out | `ON DELETE CASCADE` with the ticket |
| `outbox_message`, `outbox_part` | Every outbound email and its parts; sensitive mail has its body blanked after sending | Business code queues, sender worker sends | Sent or failed rows 90 days (retention job) |
| `mail_poller_lock`, `mailbox_probe` | One poller at a time; mailbox test-connection probes | Poller, admin test | Kept |
| `inbound_attachment_job` | Files of a received email still to be checked, stored and bound to its comment (re-read from `inbound_mail.raw_eml`) | Poller writes; the attachment worker claims, finishes (deletes) or fails; the agent ticket view reads the open ones | Deleted when done; FAILED rows kept; cascades with the ticket, comment and mail |

### Integrations

| Table | Purpose | Reads / writes | Retention |
|---|---|---|---|
| `api_key` | Monitoring-tool keys, each bound to one organisation; only SHA-256 of the key is stored, `key_id` is the visible part used to find the row | Admin creates and revokes; the API key filter reads | Kept (revoked keys stay so their tickets keep an author) |

### Audit

| Table | Purpose | Reads / writes | Retention |
|---|---|---|---|
| `audit_event` | Append-only security and admin log with a field allow list (no secrets) | App role: `INSERT` and `SELECT` only; the audit viewer and CSV export read | 1 year, only through `purge_audit_events` |
