package com.ticketsystem.api.customer;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AuthAuditEvent;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.auth.InvitationService;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.TokenPurpose;
import com.ticketsystem.api.auth.TokenService;
import com.ticketsystem.api.auth.mail.SecurityNotice;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import com.ticketsystem.api.customer.clientdata.AdminCustomerRepository;
import com.ticketsystem.api.customer.clientdata.AdminCustomerRepository.CustomerFacts;
import com.ticketsystem.api.customer.clientdata.AdminCustomerRepository.CustomerRow;
import com.ticketsystem.api.customer.clientdata.AdminCustomerRepository.LockedCustomer;
import com.ticketsystem.api.customer.clientdata.AdminCustomerRepository.Sort;
import com.ticketsystem.api.customer.clientdata.AdminCustomerRepository.StatusFilter;
import com.ticketsystem.api.ticket.clientdata.TicketParticipantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin - Customers: customers of every organisation, with admin actions. Admin only: the {@code
 * /api/v1/admin} role gate answers 403 to agents and customers, and every method here checks the
 * admin scope again. Ids of agents and admins answer 404 (they are managed in Admin - Agents).
 * Erasure stays {@code POST /api/v1/admin/users/{id}/erase}.
 *
 * <p>Step-up (on the endpoints): force sign-out (as for agents) and moving to another organisation.
 * Every action is audit-logged without names or addresses.
 */
@Service
public class AdminCustomerService {

  public static final int MAX_PAGE = 100;

  /** One customer in the list. {@code status} is ACTIVE, INVITED, DEACTIVATED or ERASED. */
  public record CustomerSummary(
      long id,
      String name,
      String email,
      long organisationId,
      String organisationName,
      String status,
      Instant lastSignInAt,
      Instant createdAt) {}

  public record CustomerPage(List<CustomerSummary> items, long total) {}

  /** The detail panel. {@code invitationExpiresAt} only while an invitation link is unused. */
  public record CustomerDetail(
      long id,
      String name,
      String email,
      long organisationId,
      String organisationName,
      String status,
      Instant lastSignInAt,
      Instant createdAt,
      boolean passwordSet,
      boolean microsoftLinked,
      long ticketsRaised,
      Instant invitationExpiresAt) {}

  public record Invited(Instant expiresAt) {}

  public record Moved(CustomerDetail customer, int participantTicketsRemoved) {}

  static final String ERASED = "ERASED";

  private final AdminCustomerRepository repo;
  private final TicketParticipantRepository participants;
  private final InvitationService invitations;
  private final TokenService tokens;
  private final SystemMailer mailer;
  private final AuthProperties props;
  private final SecurityVersionService securityVersion;
  private final AuditService audit;
  private final Clock clock;

  public AdminCustomerService(
      AdminCustomerRepository repo,
      TicketParticipantRepository participants,
      InvitationService invitations,
      TokenService tokens,
      SystemMailer mailer,
      AuthProperties props,
      SecurityVersionService securityVersion,
      AuditService audit,
      Clock clock) {
    this.repo = repo;
    this.participants = participants;
    this.invitations = invitations;
    this.tokens = tokens;
    this.mailer = mailer;
    this.props = props;
    this.securityVersion = securityVersion;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CustomerPage search(
      AccessScope scope,
      String search,
      Long organisationId,
      String status,
      String sort,
      int page,
      int limit) {
    scope.requireAdmin();
    if (search != null && search.length() > 200) {
      throw ApiException.invalidRequest("Search text can be up to 200 characters.");
    }
    if (page < 0) {
      throw ApiException.invalidRequest("page starts at 0.");
    }
    StatusFilter statusFilter = parse(StatusFilter.class, status, "status");
    Sort order = parse(Sort.class, sort, "sort");
    int size = Math.max(1, Math.min(limit, MAX_PAGE));
    List<CustomerSummary> items =
        repo
            .search(
                scope,
                search,
                organisationId,
                statusFilter,
                order,
                size,
                Math.multiplyExact(page, size))
            .stream()
            .map(AdminCustomerService::summary)
            .toList();
    return new CustomerPage(items, repo.count(scope, search, organisationId, statusFilter));
  }

  @Transactional(readOnly = true)
  public CustomerDetail get(AccessScope scope, long id) {
    CustomerRow row = repo.find(scope, id).orElseThrow(AccessScope::notFound);
    CustomerFacts f = repo.facts(scope, id);
    CustomerSummary s = summary(row);
    boolean invited = "INVITED".equals(s.status());
    return new CustomerDetail(
        s.id(),
        s.name(),
        s.email(),
        s.organisationId(),
        s.organisationName(),
        s.status(),
        s.lastSignInAt(),
        s.createdAt(),
        f.passwordSet(),
        f.microsoftLinked(),
        f.ticketsRaised(),
        invited ? f.invitationExpiresAt() : null);
  }

  /**
   * (Re)sends the portal invitation (link 7 days, no code) to a customer who has not accepted one
   * yet; an older unused link stops working. Logged as INVITATION_CREATED by the invitation flow.
   */
  @Transactional
  public Invited resendInvitation(AccessScope scope, long id, String clientIp) {
    LockedCustomer c = lockLive(scope, id);
    if (!"INVITED".equals(c.status())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "not_invited",
          "DEACTIVATED".equals(c.status())
              ? "This customer is deactivated - reactivate them first."
              : "This customer has already accepted their invitation.");
    }
    InvitationService.Created created =
        invitations.invite(
            actor(scope), c.email(), c.name(), Role.CUSTOMER, c.organisationId(), clientIp);
    return new Invited(created.expiresAt());
  }

  /** Emails an active customer a password reset link (15 minutes); the admin never sees it. */
  @Transactional
  public void sendPasswordReset(AccessScope scope, long id, String clientIp) {
    LockedCustomer c = lockLive(scope, id);
    if (!"ACTIVE".equals(c.status())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "not_active",
          "Only active customers can reset their password. Resend the invitation instead.");
    }
    TokenService.Issued t =
        tokens.issue(id, TokenPurpose.PASSWORD_RESET, props.passwordResetValidity());
    mailer.passwordReset(
        c.email(), c.name(), props.link("/reset-password", t.token()), t.expiresAt());
    audit.record(
        AuthAuditEvent.PASSWORD_RESET_REQUESTED,
        actor(scope),
        id,
        clientIp,
        Map.of("reason", "admin", "status", "sent"));
  }

  /**
   * Ends every session of the customer at once (security_version bump; trusted devices are an agent
   * feature). The endpoint needs a fresh step-up, as force sign-out does for agents.
   */
  @Transactional
  public void signOutEverywhere(AccessScope scope, long id, String clientIp) {
    lockLive(scope, id);
    long actor = actor(scope);
    securityVersion.bump(id, SecurityVersionService.Reason.ADMIN_SIGN_OUT, null, actor, clientIp);
    audit.record(CustomerAuditEvent.CUSTOMER_SIGNED_OUT_EVERYWHERE, actor, id, clientIp, Map.of());
  }

  /**
   * Deactivates an active or invited customer: they cannot sign in or use any link, every session
   * ends, and ticket email stops (the mail composer sends only to live contacts). Their tickets
   * stay as they are. An active customer gets the "account deactivated" notice.
   */
  @Transactional
  public CustomerDetail deactivate(AccessScope scope, long id, String clientIp) {
    LockedCustomer c = lockLive(scope, id);
    if ("DEACTIVATED".equals(c.status())) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_active", "This customer is already deactivated.");
    }
    long actor = actor(scope);
    Instant now = clock.instant();
    repo.setStatus(scope, id, c.status(), "DEACTIVATED", now);
    repo.revokeUnusedTokens(scope, id, now);
    securityVersion.bump(id, SecurityVersionService.Reason.DEACTIVATION, null, actor, clientIp);
    audit.record(
        CustomerAuditEvent.CUSTOMER_DEACTIVATED, actor, id, clientIp, Map.of("status", c.status()));
    if ("ACTIVE".equals(c.status())) {
      mailer.securityNotice(c.email(), c.name(), SecurityNotice.ACCOUNT_DEACTIVATED);
    }
    return get(scope, id);
  }

  /**
   * Lets a deactivated customer back: ACTIVE when they had set a password, otherwise INVITED (they
   * then need an invitation, as before). An erased customer can never come back.
   */
  @Transactional
  public CustomerDetail reactivate(AccessScope scope, long id, String clientIp) {
    LockedCustomer c = lockLive(scope, id);
    if (!"DEACTIVATED".equals(c.status())) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_deactivated", "This customer is not deactivated.");
    }
    String to = c.passwordSet() ? "ACTIVE" : "INVITED";
    repo.setStatus(scope, id, "DEACTIVATED", to, clock.instant());
    audit.record(
        CustomerAuditEvent.CUSTOMER_REACTIVATED, actor(scope), id, clientIp, Map.of("status", to));
    return get(scope, id);
  }

  /**
   * Moves a customer to another organisation (step-up on the endpoint). Their sessions end
   * (security_version), and the organisation-move participant rule is applied: they stop being a
   * participant of tickets of any other organisation. Tickets they raised stay with the old
   * organisation, as ticket history; the mail composer stops sending them mail for those tickets
   * because they are no longer a contact there. An agent can change a ticket's reporter.
   */
  @Transactional
  public Moved move(AccessScope scope, long id, Long organisationId, String clientIp) {
    if (organisationId == null) {
      throw ApiException.invalidRequest("Choose the organisation to move them to.");
    }
    LockedCustomer c = lockLive(scope, id);
    if (!repo.organisationExists(scope, organisationId)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "unknown_organisation", "That organisation does not exist.");
    }
    if (c.organisationId() == organisationId) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "same_organisation",
          "This customer is already in that organisation.");
    }
    long actor = actor(scope);
    Instant now = clock.instant();
    repo.setOrganisation(scope, id, organisationId, now);
    securityVersion.bump(id, SecurityVersionService.Reason.ORGANISATION, null, actor, clientIp);
    int removed = participants.removeMovedContact(scope, id, c.email(), organisationId, now);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("organisation", organisationId);
    d.put("old_organisation", c.organisationId());
    d.put("tickets", removed);
    audit.record(CustomerAuditEvent.CUSTOMER_MOVED, actor, id, clientIp, d);
    return new Moved(get(scope, id), removed);
  }

  /** Locks a customer that has not been erased (an erased one can no longer be acted on). */
  private LockedCustomer lockLive(AccessScope scope, long id) {
    scope.requireAdmin();
    LockedCustomer c = repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    if (c.erased()) {
      throw new ApiException(
          HttpStatus.CONFLICT, "erased", "This customer's personal data was erased.");
    }
    return c;
  }

  private static long actor(AccessScope scope) {
    scope.requireAdmin();
    return scope.principalId().orElseThrow(AccessScope::notFound);
  }

  private static CustomerSummary summary(CustomerRow r) {
    return new CustomerSummary(
        r.id(),
        r.name(),
        r.email(),
        r.organisationId(),
        r.organisationName(),
        r.erased() ? ERASED : r.status(),
        r.lastSignInAt(),
        r.createdAt());
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String raw, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String key =
        raw.strip()
            .replaceAll("([a-z])([A-Z])", "$1_$2")
            .toUpperCase(Locale.ROOT)
            .replace('-', '_');
    try {
      return Enum.valueOf(type, key);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("Unknown " + name + ".");
    }
  }
}
