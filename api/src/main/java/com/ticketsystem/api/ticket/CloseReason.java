package com.ticketsystem.api.ticket;

import com.ticketsystem.api.auth.ApiException;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Why a ticket was closed. An agent closing by hand must choose one of the manual reasons ({@link
 * #manual()}); automatic closes record a system reason. Stored by name in {@code
 * ticket.close_reason} and {@code ticket_event.close_reason}: append new values, never rename.
 * Agent read model, export and reports only - never a portal response or a client email.
 */
public enum CloseReason {
  /** Closed as a duplicate (only through "mark as duplicate", never the status endpoint). */
  DUPLICATE("Duplicate", false),
  SPAM("Spam / junk", true),
  RESOLVED_NO_FOLLOW_UP("Resolved - no follow-up needed", true),
  CLIENT_CANCELLED("Client cancelled / no longer needed", true),
  NO_RESPONSE("No response from client", true),
  /** Needs a note of {@link #MIN_NOTE}-{@link #MAX_NOTE} characters. */
  OTHER("Other", true),
  /** System: the Resolved -> Closed job after the client did not reply. */
  AUTO_CLOSED_NO_REPLY("No client reply after it was resolved", false);

  public static final int MIN_NOTE = 3;
  public static final int MAX_NOTE = 500;

  private final String label;
  private final boolean manual;

  CloseReason(String label, boolean manual) {
    this.label = label;
    this.manual = manual;
  }

  /** As shown in the timeline, the dialog and the CSV export. */
  public String label() {
    return label;
  }

  /** May an agent choose it in the close dialog (through the status endpoint)? */
  public boolean manual() {
    return manual;
  }

  /** The label, with the note for {@link #OTHER} ("Other - waiting on a new contract"). */
  public static String describe(CloseReason reason, String note) {
    if (reason == null) {
      return "";
    }
    return note == null || note.isBlank() ? reason.label() : reason.label() + " - " + note;
  }

  /** The stored name as a value, or null when absent or unknown. */
  public static CloseReason parseOrNull(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** An agent's close: a manual reason and, for Other, its note. Built by {@link #request}. */
  public record Close(CloseReason reason, String note) {}

  /**
   * Checks an agent's close request (status endpoint): a manual reason is required; Duplicate is
   * refused with a pointer to "mark as duplicate"; Other needs a note of 3-500 characters (control
   * characters become spaces). A note given with another reason is ignored.
   *
   * @throws ApiException 400 {@code close_reason_required}, {@code use_mark_duplicate} or {@code
   *     close_note_required}
   */
  public static Close request(String rawReason, String rawNote) {
    if (rawReason == null || rawReason.isBlank()) {
      throw reasonRequired();
    }
    CloseReason reason = parseOrNull(rawReason.strip().toUpperCase(Locale.ROOT));
    if (reason == DUPLICATE) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "use_mark_duplicate",
          "To close a ticket as a duplicate, use \"Mark as duplicate of...\" so it is linked to the"
              + " other ticket.");
    }
    if (reason == null || !reason.manual()) {
      throw reasonRequired();
    }
    if (reason != OTHER) {
      return new Close(reason, null);
    }
    return new Close(OTHER, note(rawNote, "close_note_required", "Add a note saying why"));
  }

  /**
   * A free-text reason of 3-500 characters (close note, admin reopen reason): stripped, control
   * characters replaced by spaces.
   */
  public static String note(String raw, String code, String what) {
    String n = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
    if (n.length() < MIN_NOTE || n.length() > MAX_NOTE) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          code,
          what + " (" + MIN_NOTE + " to " + MAX_NOTE + " characters).");
    }
    return n;
  }

  private static ApiException reasonRequired() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "close_reason_required", "Choose a reason for closing the ticket.");
  }
}
