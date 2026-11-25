package com.ticketsystem.api.access;

import com.ticketsystem.api.access.clientdata.UserAccessRepository;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.organisation.OrganisationAuditEvent;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin changes to who sees which organisation: a customer's organisation and an agent's
 * organisations (every organisation by default). Admins always see every organisation. Each change
 * bumps the user's security_version, so their existing sessions end at the next request (401), and
 * is audit-logged. The supporter screen sits on top of this API.
 */
@Service
public class UserAccessService {

  /** Most organisations listed for one agent. */
  static final int MAX_ORGANISATIONS = 500;

  private final UserAccessRepository access;
  private final OrganisationRepository organisations;
  private final AuditService audit;
  private final Clock clock;
  private final TicketParticipantRepository participants;

  public UserAccessService(
      UserAccessRepository access,
      OrganisationRepository organisations,
      AuditService audit,
      Clock clock,
      TicketParticipantRepository participants) {
    this.access = access;
    this.organisations = organisations;
    this.audit = audit;
    this.clock = clock;
    this.participants = participants;
  }

  @Transactional(readOnly = true)
  public UserAccess get(AccessScope scope, long userId) {
    scope.requireAdmin();
    return access.find(scope, userId).orElseThrow(AccessScope::notFound);
  }

  @Transactional
  public UserAccess setCustomerOrganisation(
      AccessScope scope, long userId, long organisationId, String clientIp) {
    scope.requireAdmin();
    Role role = access.lockRole(scope, userId).orElseThrow(AccessScope::notFound);
    if (role != Role.CUSTOMER) {
      throw ApiException.invalidRequest("Only customer accounts belong to an organisation.");
    }
    requireOrganisation(scope, organisationId);
    access.setCustomerOrganisation(scope, userId, organisationId, clock.instant());
    // The same participant re-check as Admin - Customers' move (the endpoint now needs
    // step-up too), so neither door leaves a moved customer on another organisation's tickets.
    int removed =
        participants.removeMovedContact(
            scope,
            userId,
            access.email(scope, userId).orElse(null),
            organisationId,
            clock.instant());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("organisation", organisationId);
    d.put("tickets", removed);
    audit.record(
        OrganisationAuditEvent.USER_ORGANISATION_CHANGED,
        scope.principalId().orElse(null),
        userId,
        clientIp,
        d);
    return get(scope, userId);
  }

  @Transactional
  public UserAccess setAgentAccess(
      AccessScope scope, long userId, boolean all, Set<Long> organisationIds, String clientIp) {
    scope.requireAdmin();
    Role role = access.lockRole(scope, userId).orElseThrow(AccessScope::notFound);
    if (role == Role.ADMIN) {
      throw ApiException.invalidRequest("Admins always see every organisation.");
    }
    if (role != Role.AGENT) {
      throw ApiException.invalidRequest("Only agents have organisation access to set.");
    }
    Set<Long> ids = organisationIds == null ? Set.of() : new TreeSet<>(organisationIds);
    if (!all && ids.isEmpty()) {
      throw ApiException.invalidRequest("Choose at least one organisation, or all organisations.");
    }
    if (ids.size() > MAX_ORGANISATIONS) {
      throw ApiException.invalidRequest("Too many organisations in one request.");
    }
    if (!all) {
      ids.forEach(id -> requireOrganisation(scope, id));
    }
    access.setAgentAccess(scope, userId, all, all ? Set.of() : ids, clock.instant());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("all_organisations", all);
    if (!all) {
      d.put("organisations", ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }
    audit.record(
        OrganisationAuditEvent.SUPPORTER_ACCESS_CHANGED,
        scope.principalId().orElse(null),
        userId,
        clientIp,
        d);
    return get(scope, userId);
  }

  private void requireOrganisation(AccessScope scope, long organisationId) {
    if (organisations.find(scope, organisationId).isEmpty()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "unknown_organisation", "That organisation does not exist.");
    }
  }
}
