package com.ticketsystem.api.mail;

import com.ticketsystem.api.audit.AuditDetailKeys;
import com.ticketsystem.api.audit.AuditEvent;

/**
 * Email in and out. Detail keys: the mailbox id, rule kind and normalised rule (an address or
 * domain the admin blocked or routed), the reply mode, a mailbox's SMTP security, same or separate
 * SMTP sign-in and the names of the outgoing settings that changed, the blocked-mail log id, a
 * supporter email reply's DMARC/SPF/DKIM results, and a mailbox's trusted authserv-ids before and
 * after a change (receiving server host names) - never secrets or message content.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
@AuditDetailKeys({
  "mailbox",
  "kind",
  "rule",
  "mode",
  "inbound_mail",
  "dmarc",
  "spf",
  "dkim",
  "smtp_security",
  "smtp_sign_in",
  "trusted_authserv_before",
  "trusted_authserv_after"
})
public enum MailAuditEvent implements AuditEvent {
  MAILBOX_SAVED,
  MAILBOX_DELETED,
  EMAIL_SETTINGS_CHANGED,
  BLOCK_RULE_ADDED,
  BLOCK_RULE_REMOVED,
  BLOCKED_MAIL_RESTORED,
  SENDER_RULE_ADDED,
  SENDER_RULE_REMOVED,
  /** A supporter's emailed reply became a note or public reply, with auth results. */
  SUPPORTER_EMAIL_REPLY,
  /** A mailbox got outgoing (SMTP) settings. Never the host, user name or password. */
  MAILBOX_SMTP_SET,
  /** Outgoing settings changed; {@code fields} names the parts (server, password...). */
  MAILBOX_SMTP_CHANGED,
  /** Outgoing settings removed; ticket mail uses the server's mail settings again. */
  MAILBOX_SMTP_REMOVED,
  /** The server changed a mailbox folder's UIDVALIDITY; the poller restarted at UIDNEXT. */
  MAILBOX_UIDVALIDITY_CHANGED
}
