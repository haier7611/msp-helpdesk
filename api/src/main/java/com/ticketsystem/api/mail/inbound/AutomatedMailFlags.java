package com.ticketsystem.api.mail.inbound;

import java.util.EnumSet;
import java.util.Set;

/**
 * Loop-safety flags for one inbound message.
 *
 * @param signals what was seen; empty for a normal person-written email
 * @param autoReply an auto-reply, out-of-office or bounce: stored at most as a hidden entry, never
 *     reopens a ticket
 */
public record AutomatedMailFlags(Set<Signal> signals, boolean autoReply) {

  public AutomatedMailFlags {
    signals = signals.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(signals));
  }

  /** True when any signal was seen. */
  public boolean automated() {
    return !signals.isEmpty();
  }

  /**
   * The system must not send this sender any automatic email (confirmation, reminder, ...): loop
   * safety. The message still becomes a ticket.
   */
  public boolean suppressAutomaticReplies() {
    return automated();
  }

  /** Newsletter or list mail. */
  public boolean bulk() {
    return signals.contains(Signal.PRECEDENCE_BULK) || signals.contains(Signal.LIST_HEADERS);
  }

  /** Individual signals. */
  public enum Signal {
    /** {@code Auto-Submitted} with any value other than {@code no}. */
    AUTO_SUBMITTED,
    /**
     * {@code X-Autoreply}, {@code X-Autorespond} and similar, or {@code Precedence: auto_reply}.
     */
    AUTO_REPLY_HEADER,
    /** {@code Precedence: bulk}, {@code list} or {@code junk}. */
    PRECEDENCE_BULK,
    /** {@code List-Unsubscribe} or {@code List-Id}. */
    LIST_HEADERS,
    /** {@code X-Auto-Response-Suppress} with a value other than {@code None}. */
    AUTO_RESPONSE_SUPPRESS,
    /** Sender local part such as {@code no-reply}, {@code donotreply}, {@code mailer-daemon}. */
    NO_REPLY_SENDER,
    /** {@code Return-Path: <>} (bounces and other null-sender mail). */
    NULL_RETURN_PATH,
    /** {@code multipart/report} (delivery status notification). */
    DELIVERY_REPORT
  }
}
