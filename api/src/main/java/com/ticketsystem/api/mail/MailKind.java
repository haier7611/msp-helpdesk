package com.ticketsystem.api.mail;

import java.util.Locale;

/** What an outbound email is for; stored in {@code outbox_message.kind}. */
public enum MailKind {
  /** Invitations, resets, magic links, security notices: never threaded into a ticket. */
  SYSTEM,
  /** Mail to the client (reporter and participants) about a ticket. */
  CLIENT_FACING,
  /** Agent-only notifications; subject carries "(Internal)" after the reference. */
  INTERNAL;

  String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  static MailKind fromDb(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
