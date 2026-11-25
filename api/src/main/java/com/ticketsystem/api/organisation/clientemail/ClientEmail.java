package com.ticketsystem.api.organisation.clientemail;

/**
 * The client emails an admin can turn off per organisation. Agent public replies and the
 * agent-opened ticket email are the conversation and are never switchable, so they have no value
 * here. Stored by name in {@code ticket_event.new_value} of a {@code CLIENT_EMAIL_SKIPPED} row:
 * append new values, never rename.
 */
public enum ClientEmail {
  /** The "Thank you, we have received your request" confirmation with the reference. */
  TICKET_RECEIVED("Ticket received"),
  /** The Resolved notice (an agent's Resolve or the auto-resolve). */
  RESOLVED_NOTICE("Resolved notice"),
  /** The Closed notice after the auto-close. */
  CLOSED_NOTICE("Closed notice"),
  /** The Pending-on-client reminder. */
  PENDING_REMINDER("Pending reminder");

  private final String label;

  ClientEmail(String label) {
    this.label = label;
  }

  /** The name agents and admins see ("Resolved notice"). */
  public String label() {
    return label;
  }
}
