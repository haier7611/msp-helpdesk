package com.ticketsystem.api.integration;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.integration.clientdata.ApiAlertRepository;
import com.ticketsystem.api.integration.clientdata.ApiAlertRepository.AlertTicket;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.TicketService.AlertUpdate;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.TicketSource;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alert tickets from monitoring tools. Everything happens in the key's organisation (the API-key
 * scope); the body never chooses one.
 *
 * <ul>
 *   <li>No {@code externalId}: every alert is a new ticket (request type "Alert", source API).
 *   <li>{@code externalId} with no API ticket yet: a new ticket carrying it.
 *   <li>The newest API ticket with that id is not Closed: the alert becomes a comment on it; a
 *       Resolved ticket reopens to Open; a "resolved" alert resolves it.
 *   <li>That ticket is Closed: a linked follow-up ticket (F4) carries the id from now on.
 *   <li>A "resolved" alert with no open ticket changes nothing ({@link Result#IGNORED}).
 * </ul>
 *
 * Alerts with the same external id are serialised with an advisory lock, so two at once make one
 * ticket. Keys of one organisation share one external-id space, so rotating a key keeps dedup
 * working. No client email is queued here: client mail for API tickets is sent elsewhere, and only
 * when the organisation opted in (default off).
 */
@Service
public class IntegrationTicketService {

  static final int MAX_EXTERNAL_ID = 200;
  static final int MAX_TITLE = 250;

  /** One alert as the monitoring tool sent it. */
  public record Alert(String externalId, String title, String description, boolean resolved) {}

  public enum Result {
    CREATED,
    UPDATED,
    REOPENED,
    RESOLVED,
    FOLLOW_UP_CREATED,
    IGNORED
  }

  /** The whole answer: no ticket content (API keys never read tickets). */
  public record Outcome(String reference, Long ticketId, Result result) {}

  private final TicketService tickets;
  private final ApiAlertRepository alerts;
  private final RequestTypeRepository requestTypes;
  private final ReferenceService references;

  public IntegrationTicketService(
      TicketService tickets,
      ApiAlertRepository alerts,
      RequestTypeRepository requestTypes,
      ReferenceService references) {
    this.tickets = tickets;
    this.alerts = alerts;
    this.requestTypes = requestTypes;
    this.references = references;
  }

  @Transactional
  public Outcome receive(AccessScope scope, Alert alert) {
    if (scope.kind() != AccessScope.Kind.API_KEY) {
      throw AccessScope.notFound();
    }
    String externalId = clean(alert.externalId());
    if (externalId != null && externalId.length() > MAX_EXTERNAL_ID) {
      throw ApiException.invalidRequest("externalId can be at most 200 characters.");
    }
    String title = alert.title() == null ? "" : alert.title().strip();
    if (title.isEmpty()) {
      throw ApiException.invalidRequest("Give the alert a title.");
    }
    if (title.length() > MAX_TITLE) {
      throw ApiException.invalidRequest("The title can be at most 250 characters.");
    }
    String body = commentHtml(title, alert.description());

    Optional<AlertTicket> existing = Optional.empty();
    if (externalId != null) {
      alerts.lockExternalId(scope, externalId);
      existing = alerts.newest(scope, externalId);
    }
    if (existing.isEmpty()) {
      if (alert.resolved()) {
        return new Outcome(null, null, Result.IGNORED);
      }
      return create(scope, externalId, title, alert.description());
    }
    AlertTicket t = existing.get();
    if (t.status() == TicketStatus.CLOSED) {
      if (alert.resolved()) {
        return new Outcome(reference(scope, t.ticketId()), t.ticketId(), Result.IGNORED);
      }
      Created f = tickets.createAlertFollowUp(scope, t.ticketId(), body);
      alerts.stampKey(scope, f.ticketId(), externalId);
      return new Outcome(f.reference().toString(), f.ticketId(), Result.FOLLOW_UP_CREATED);
    }
    AlertUpdate u = tickets.addAlertComment(scope, t.ticketId(), body, alert.resolved());
    alerts.stampComments(scope, t.ticketId());
    Result result;
    if (alert.resolved()) {
      result = u.from() == TicketStatus.RESOLVED ? Result.UPDATED : Result.RESOLVED;
    } else {
      result = u.from() == TicketStatus.RESOLVED ? Result.REOPENED : Result.UPDATED;
    }
    return new Outcome(reference(scope, t.ticketId()), t.ticketId(), result);
  }

  private Outcome create(AccessScope scope, String externalId, String title, String description) {
    long org = scope.organisationIdsOrAll().orElseThrow(AccessScope::notFound).iterator().next();
    long alertType =
        requestTypes
            .findByName(scope, "Alert")
            .orElseThrow(() -> new IllegalStateException("the Alert request type is missing"))
            .id();
    Created c =
        tickets.create(
            scope,
            new NewTicket(
                org,
                null,
                null,
                alertType,
                null,
                null,
                null,
                TicketSource.API,
                title,
                description == null ? "" : description,
                List.of(),
                externalId,
                null,
                null,
                null));
    alerts.stampKey(scope, c.ticketId(), externalId);
    return new Outcome(c.reference().toString(), c.ticketId(), Result.CREATED);
  }

  private String reference(AccessScope scope, long ticketId) {
    return references.referencesOf(scope, ticketId).stream()
        .findFirst()
        .map(Object::toString)
        .orElse(null);
  }

  /** A repeat's comment: the title as an escaped paragraph, then the description (sanitised). */
  static String commentHtml(String title, String description) {
    return "<p><strong>"
        + escape(title)
        + "</strong></p>"
        + (description == null ? "" : description);
  }

  static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String clean(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
