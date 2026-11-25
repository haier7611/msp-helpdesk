package com.ticketsystem.api.ticket;

import static com.ticketsystem.api.sla.TicketStatus.CLOSED;
import static com.ticketsystem.api.sla.TicketStatus.NEW;
import static com.ticketsystem.api.sla.TicketStatus.OPEN;
import static com.ticketsystem.api.sla.TicketStatus.PENDING_ON_CLIENT;
import static com.ticketsystem.api.sla.TicketStatus.PENDING_ON_VENDOR;
import static com.ticketsystem.api.sla.TicketStatus.RESOLVED;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.sla.TicketStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The ticket status rules in one place. The status type is the SLA engine's {@link TicketStatus},
 * adopted as the domain type.
 *
 * <p>An agent setting a status by hand ({@link Trigger#AGENT_SET}):
 *
 * <table>
 *   <tr><th>From</th><th>To</th></tr>
 *   <tr><td>New</td><td>Open, Pending on client, Pending on vendor, Resolved</td></tr>
 *   <tr><td>Open</td><td>Pending on client, Pending on vendor, Resolved</td></tr>
 *   <tr><td>Pending on client</td><td>Open, Pending on vendor, Resolved</td></tr>
 *   <tr><td>Pending on vendor</td><td>Open, Pending on client, Resolved</td></tr>
 *   <tr><td>Resolved</td><td>Open (reopen), Closed (close now)</td></tr>
 *   <tr><td>Closed</td><td>nothing (read-only; a client reply opens a follow-up)</td></tr>
 * </table>
 *
 * Nothing ever moves back to New. Setting the status a ticket already has changes nothing, except
 * on a Closed ticket, which refuses every change.
 *
 * <p>Event triggers: {@link Trigger#PICK_UP} (a supporter assigns the ticket to themselves) and
 * {@link Trigger#SUPPORTER_PUBLIC_REPLY} move New to Open and leave other statuses alone; {@link
 * Trigger#CLIENT_REPLY} moves either Pending status or Resolved to Open, leaves New and Open alone,
 * and is refused on Closed (the caller opens a follow-up instead); {@link Trigger#MARK_DUPLICATE}
 * closes any open ticket without a client email; {@link Trigger#AUTO_RESOLVE} moves Pending on
 * client to Resolved and {@link Trigger#AUTO_CLOSE} Resolved to Closed. {@link
 * Trigger#ALERT_REPEAT} reopens a Resolved or Pending ticket to Open (the SLA clock resumes) and
 * leaves New and Open alone; {@link Trigger#ALERT_RESOLVED} resolves any open ticket. Both are
 * refused on Closed (the API opens a follow-up instead). {@link Trigger#ADMIN_REOPEN} is the only
 * way out of Closed: an admin moves it to Open.
 */
public final class TicketTransitions {

  /** What caused a status change. */
  public enum Trigger {
    /** An agent chose a status (status menu, or "Send and set ..." with a public reply). */
    AGENT_SET,
    /** A supporter assigned the ticket to themselves ("picks up"). */
    PICK_UP,
    /** A supporter's public reply. */
    SUPPORTER_PUBLIC_REPLY,
    /** A client reply (by email). */
    CLIENT_REPLY,
    /** Marked as a duplicate of another ticket. */
    MARK_DUPLICATE,
    /** Pending-on-client auto-resolve job. */
    AUTO_RESOLVE,
    /** Resolved auto-close job. */
    AUTO_CLOSE,
    /** A repeat of an open API alert: Resolved reopens to Open. */
    ALERT_REPEAT,
    /** An API alert reporting "resolved": any open status to Resolved. */
    ALERT_RESOLVED,
    /** An admin reopened a Closed ticket (with a reason, after step-up): Closed to Open. */
    ADMIN_REOPEN
  }

  private static final Map<TicketStatus, Set<TicketStatus>> AGENT_SET_ALLOWED =
      new EnumMap<>(TicketStatus.class);

  static {
    AGENT_SET_ALLOWED.put(NEW, EnumSet.of(OPEN, PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED));
    AGENT_SET_ALLOWED.put(OPEN, EnumSet.of(PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED));
    AGENT_SET_ALLOWED.put(PENDING_ON_CLIENT, EnumSet.of(OPEN, PENDING_ON_VENDOR, RESOLVED));
    AGENT_SET_ALLOWED.put(PENDING_ON_VENDOR, EnumSet.of(OPEN, PENDING_ON_CLIENT, RESOLVED));
    AGENT_SET_ALLOWED.put(RESOLVED, EnumSet.of(OPEN, CLOSED));
    AGENT_SET_ALLOWED.put(CLOSED, EnumSet.noneOf(TicketStatus.class));
  }

  private TicketTransitions() {}

  /**
   * The status after {@code trigger}.
   *
   * @param requested the chosen status for {@link Trigger#AGENT_SET}; ignored otherwise
   * @return the new status, which may equal {@code from} (no change)
   * @throws ApiException 409 {@code illegal_transition} when the rules refuse it
   */
  public static TicketStatus next(TicketStatus from, Trigger trigger, TicketStatus requested) {
    return switch (trigger) {
      case AGENT_SET -> agentSet(from, requested);
      case PICK_UP, SUPPORTER_PUBLIC_REPLY -> {
        refuseClosed(from);
        yield from == NEW ? OPEN : from;
      }
      case CLIENT_REPLY -> {
        refuseClosed(from);
        yield switch (from) {
          case PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED -> OPEN;
          default -> from;
        };
      }
      case MARK_DUPLICATE -> {
        refuseClosed(from);
        yield CLOSED;
      }
      case AUTO_RESOLVE -> {
        if (from != PENDING_ON_CLIENT) {
          throw illegal(from, RESOLVED);
        }
        yield RESOLVED;
      }
      case AUTO_CLOSE -> {
        if (from != RESOLVED) {
          throw illegal(from, CLOSED);
        }
        yield CLOSED;
      }
      case ALERT_REPEAT -> {
        refuseClosed(from);
        yield switch (from) {
          case RESOLVED, PENDING_ON_CLIENT, PENDING_ON_VENDOR -> OPEN;
          default -> from;
        };
      }
      case ALERT_RESOLVED -> {
        refuseClosed(from);
        yield RESOLVED;
      }
      case ADMIN_REOPEN -> {
        if (from != CLOSED) {
          throw new ApiException(
              HttpStatus.CONFLICT, "not_closed", "Only a closed ticket can be reopened.");
        }
        yield OPEN;
      }
    };
  }

  /** May an agent set {@code to} by hand on a ticket in {@code from}? (Same status counts.) */
  public static boolean agentMaySet(TicketStatus from, TicketStatus to) {
    if (from == CLOSED) {
      return false;
    }
    return from == to || AGENT_SET_ALLOWED.get(from).contains(to);
  }

  private static TicketStatus agentSet(TicketStatus from, TicketStatus requested) {
    if (requested == null) {
      throw ApiException.invalidRequest("Choose a status.");
    }
    if (!agentMaySet(from, requested)) {
      throw illegal(from, requested);
    }
    return requested;
  }

  private static void refuseClosed(TicketStatus from) {
    if (from == CLOSED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "ticket_closed", "This ticket is closed and cannot be changed.");
    }
  }

  private static ApiException illegal(TicketStatus from, TicketStatus to) {
    return new ApiException(
        HttpStatus.CONFLICT,
        "illegal_transition",
        "A ticket cannot move from " + label(from) + " to " + label(to) + ".");
  }

  /** The status as shown in the UI, e.g. "Pending on client". */
  public static String label(TicketStatus status) {
    return switch (status) {
      case NEW -> "New";
      case OPEN -> "Open";
      case PENDING_ON_CLIENT -> "Pending on client";
      case PENDING_ON_VENDOR -> "Pending on vendor";
      case RESOLVED -> "Resolved";
      case CLOSED -> "Closed";
    };
  }
}
