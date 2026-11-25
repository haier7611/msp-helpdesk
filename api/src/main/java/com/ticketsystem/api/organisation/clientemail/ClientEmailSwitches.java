package com.ticketsystem.api.organisation.clientemail;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.admin.AdminConfigAuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.organisation.clientdata.ClientEmailSettingsRepository;
import com.ticketsystem.api.organisation.clientdata.ClientEmailSettingsRepository.SwitchesRow;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ActorKind;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.Extra;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * per-organisation on/off switches for the automatic client emails (Ticket received, Resolved
 * notice, Closed notice, Pending reminder). Default on: an organisation without a settings row, and
 * every row from before V99, reads as all on. Triage tickets (no organisation) are never switched
 * off. The places that queue these emails ask {@link #isOn} in their own transaction, at the moment
 * they would queue the email, and call {@link #recordSkipped} instead of queueing when the switch
 * is off; that writes one agent-only timeline line ({@code CLIENT_EMAIL_SKIPPED}).
 *
 * <p>Admin API: admins only (the {@code /api/v1/admin} tree, checked again here), organisation
 * scoped, audited as {@link AdminConfigAuditEvent#CLIENT_EMAILS_CHANGED}; no step-up (not a
 * security setting).
 */
@Service
public class ClientEmailSwitches {

  /** What the admin screen shows and saves. */
  public record Settings(
      boolean ticketReceived,
      boolean resolvedNotice,
      boolean closedNotice,
      boolean pendingReminder) {

    /** Every switch on (the default). */
    public static Settings allOn() {
      return new Settings(true, true, true, true);
    }

    public boolean isOn(ClientEmail email) {
      return switch (email) {
        case TICKET_RECEIVED -> ticketReceived;
        case RESOLVED_NOTICE -> resolvedNotice;
        case CLOSED_NOTICE -> closedNotice;
        case PENDING_REMINDER -> pendingReminder;
      };
    }
  }

  private final ClientEmailSettingsRepository repository;
  private final OrganisationRepository organisations;
  private final TicketHistoryRepository history;
  private final AuditService audit;
  private final Clock clock;

  public ClientEmailSwitches(
      ClientEmailSettingsRepository repository,
      OrganisationRepository organisations,
      TicketHistoryRepository history,
      AuditService audit,
      Clock clock) {
    this.repository = repository;
    this.organisations = organisations;
    this.history = history;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Is {@code email} on for the organisation? Always true for a triage ticket ({@code
   * organisationId} null). Read in the caller's transaction, at queue time.
   */
  @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
  public boolean isOn(AccessScope scope, Long organisationId, ClientEmail email) {
    if (organisationId == null) {
      return true;
    }
    return read(scope, organisationId).isOn(email);
  }

  /**
   * The agent-only timeline line "<Email> not sent - turned off for <organisation>", written in the
   * caller's transaction (so it exists only if the change that would have sent the email commits).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordSkipped(
      AccessScope scope, long organisationId, long ticketId, ClientEmail email) {
    history.record(
        scope,
        organisationId,
        ticketId,
        HistoryType.CLIENT_EMAIL_SKIPPED,
        null,
        null,
        email.name(),
        clock.instant(),
        Extra.actor(ActorKind.SYSTEM).withRef(organisationId));
  }

  // ---------------------------------------------------------------------------------------------
  // Admin API

  @Transactional(readOnly = true)
  public Settings get(AccessScope scope, long organisationId) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    return read(scope, organisationId);
  }

  @Transactional
  public Settings save(AccessScope scope, long organisationId, Settings next, String clientIp) {
    scope.requireAdmin();
    requireOrganisation(scope, organisationId);
    Settings before = read(scope, organisationId);
    List<String> changed = new ArrayList<>();
    for (ClientEmail e : ClientEmail.values()) {
      if (before.isOn(e) != next.isOn(e)) {
        changed.add(e.name().toLowerCase(Locale.ROOT) + (next.isOn(e) ? ":on" : ":off"));
      }
    }
    if (!changed.isEmpty()) {
      repository.save(
          scope,
          organisationId,
          new SwitchesRow(
              next.ticketReceived(),
              next.resolvedNotice(),
              next.closedNotice(),
              next.pendingReminder()),
          clock.instant());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("organisation", organisationId);
      details.put("changed", String.join(",", changed));
      audit.record(
          AdminConfigAuditEvent.CLIENT_EMAILS_CHANGED,
          scope.principalId().orElse(null),
          null,
          clientIp,
          details);
    }
    return read(scope, organisationId);
  }

  private Settings read(AccessScope scope, long organisationId) {
    return repository
        .find(scope, organisationId)
        .map(
            r ->
                new Settings(
                    r.ticketReceived(), r.resolvedNotice(), r.closedNotice(), r.pendingReminder()))
        .orElseGet(Settings::allOn);
  }

  private void requireOrganisation(AccessScope scope, long organisationId) {
    organisations.find(scope, organisationId).orElseThrow(AccessScope::notFound);
  }
}
