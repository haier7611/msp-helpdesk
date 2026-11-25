package com.ticketsystem.api.notification;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.SupporterPreferences.Channel;
import com.ticketsystem.api.notification.clientdata.NotificationRepository;
import com.ticketsystem.api.notification.clientdata.NotificationRepository.NotificationRow;
import com.ticketsystem.api.notification.clientdata.SupporterDirectoryRepository;
import com.ticketsystem.api.notification.clientdata.SupporterDirectoryRepository.SupporterRow;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifications to supporters (SLA warnings, My settings and the Notification centre): an in-app
 * row (the bell) and/or an internal email per the recipient's preferences; P1 alerts are always
 * both. A recipient must be an active supporter who can work the ticket's organisation - nobody is
 * ever told about an organisation they cannot access. Written in the caller's transaction, so a
 * notification exists exactly when the change that caused it commits.
 */
@Service
public class NotificationService {

  /**
   * One thing to tell people. {@code dedupeKey} (optional) makes a repeat a no-op per user. {@code
   * organisationId} is null for a triage ticket (its assignee still hears that the client replied;
   * every active supporter may see triage).
   */
  public record Notice(
      NotificationKind kind, String detail, long ticketId, Long organisationId, String dedupeKey) {}

  /** A notification as the bell lists it. */
  public record NotificationView(
      long id,
      NotificationKind kind,
      String detail,
      String text,
      Long ticketId,
      String reference,
      String ticketSubject,
      Instant createdAt,
      boolean read) {}

  public static final int MAX_PAGE = 100;

  private final NotificationRepository notifications;
  private final SupporterDirectoryRepository directory;
  private final SupporterPreferences preferences;
  private final SupporterMailer mailer;
  private final TicketRepository tickets;
  private final ReferenceService references;
  private final Clock clock;

  public NotificationService(
      NotificationRepository notifications,
      SupporterDirectoryRepository directory,
      SupporterPreferences preferences,
      SupporterMailer mailer,
      TicketRepository tickets,
      ReferenceService references,
      Clock clock) {
    this.notifications = notifications;
    this.directory = directory;
    this.preferences = preferences;
    this.mailer = mailer;
    this.tickets = tickets;
    this.references = references;
    this.clock = clock;
  }

  /** Tells the given users (filtered to active supporters of the organisation). */
  @Transactional
  public int notifyUsers(AccessScope scope, Collection<Long> userIds, Notice notice) {
    return deliver(scope, directory.activeAmong(scope, notice.organisationId(), userIds), notice);
  }

  /** Tells every active supporter who can work the organisation. */
  @Transactional
  public int notifyOrganisation(AccessScope scope, Notice notice) {
    if (notice.organisationId() == null) {
      return 0;
    }
    return deliver(scope, directory.activeFor(scope, notice.organisationId().longValue()), notice);
  }

  private int deliver(AccessScope scope, List<SupporterRow> recipients, Notice notice) {
    if (recipients.isEmpty()) {
      return 0;
    }
    Instant now = clock.instant();
    String subject = null;
    String reference = null;
    boolean loaded = false;
    int sent = 0;
    for (SupporterRow r : recipients) {
      boolean mandatory = notice.kind().mandatory();
      boolean inApp =
          mandatory || preferences.wants(scope, r.userId(), notice.kind(), Channel.IN_APP);
      boolean email =
          mandatory || preferences.wants(scope, r.userId(), notice.kind(), Channel.EMAIL);
      boolean fresh = true;
      if (inApp) {
        fresh =
            notifications.insert(
                scope,
                r.userId(),
                notice.organisationId(),
                notice.ticketId(),
                notice.kind(),
                notice.detail(),
                notice.dedupeKey(),
                now);
      }
      if (email && fresh) {
        if (!loaded) {
          subject = tickets.find(scope, notice.ticketId()).map(t -> t.subject()).orElse(null);
          TicketReference ref =
              references
                  .displayReferences(scope, List.of(notice.ticketId()))
                  .get(notice.ticketId());
          reference = ref == null ? null : ref.toString();
          loaded = true;
        }
        mailer.sendNotification(
            scope, r.email(), r.name(), reference, notice.ticketId(), text(notice), subject);
      }
      if (inApp || email) {
        sent++;
      }
    }
    return sent;
  }

  /** The plain headline of a notice (also the email subject after the reference). */
  public static String text(Notice notice) {
    return text(notice.kind(), notice.detail());
  }

  static String text(NotificationKind kind, String detail) {
    String target = "RESOLUTION".equals(detail) ? "resolution" : "first response";
    return switch (kind) {
      case TICKET_ASSIGNED -> "Ticket assigned to you";
      case CLIENT_REPLIED -> "The client replied on your ticket";
      case MENTIONED -> "You were mentioned in an internal note";
      case INTERNAL_NOTE_ADDED -> "Internal note added on your ticket";
      case SLA_AT_RISK -> "SLA about to breach: " + target;
      case SLA_BREACHED -> "SLA breached: " + target;
      case P1_CREATED -> "New P1 ticket";
      case P1_AT_RISK -> "P1 SLA about to breach: " + target;
      case SLA_AUTOMATION -> "SLA automation: " + automationText(detail);
    };
  }

  /** The trigger of an automation notice in words ("first response breached"). */
  private static String automationText(String trigger) {
    if (trigger == null) {
      return "a rule ran on this ticket";
    }
    return switch (trigger) {
      case "FIRST_RESPONSE_AT_RISK" -> "first response at risk";
      case "FIRST_RESPONSE_BREACHED" -> "first response breached";
      case "FIRST_RESPONSE_MET" -> "first response met";
      case "RESOLUTION_AT_RISK" -> "resolution at risk";
      case "RESOLUTION_BREACHED" -> "resolution breached";
      case "RESOLUTION_MET" -> "resolution met";
      default -> "a rule ran on this ticket";
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The bell (agent API)

  @Transactional(readOnly = true)
  public List<NotificationView> list(AccessScope scope, Long beforeId, int limit) {
    List<NotificationRow> rows =
        notifications.listMine(scope, beforeId, Math.max(1, Math.min(limit, MAX_PAGE)));
    Set<Long> ids = new HashSet<>();
    rows.stream().map(NotificationRow::ticketId).filter(id -> id != null).forEach(ids::add);
    Map<Long, TicketReference> refs = references.displayReferences(scope, ids);
    return rows.stream()
        .map(
            n -> {
              TicketReference ref = n.ticketId() == null ? null : refs.get(n.ticketId());
              return new NotificationView(
                  n.id(),
                  n.kind(),
                  n.detail(),
                  text(n.kind(), n.detail()),
                  n.ticketId(),
                  ref == null ? null : ref.toString(),
                  n.ticketSubject(),
                  n.createdAt(),
                  n.readAt() != null);
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public long unreadCount(AccessScope scope) {
    return notifications.unreadCount(scope);
  }

  @Transactional
  public void markRead(AccessScope scope, long id) {
    if (!notifications.markRead(scope, id, clock.instant())) {
      throw AccessScope.notFound();
    }
  }

  @Transactional
  public int markAllRead(AccessScope scope) {
    return notifications.markAllRead(scope, clock.instant());
  }
}
