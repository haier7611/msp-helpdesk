package com.ticketsystem.api.supporter;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.UserAccessService;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AuthAuditEvent;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.auth.InvitationService;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.TokenPurpose;
import com.ticketsystem.api.auth.TokenService;
import com.ticketsystem.api.auth.UserStatus;
import com.ticketsystem.api.auth.mail.SecurityNotice;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.mfa.MfaExemptionService;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import com.ticketsystem.api.signature.ProfileFields;
import com.ticketsystem.api.supporter.clientdata.SupporterRepository;
import com.ticketsystem.api.supporter.clientdata.SupporterRepository.ActivityRow;
import com.ticketsystem.api.supporter.clientdata.SupporterRepository.LockedSupporter;
import com.ticketsystem.api.supporter.clientdata.SupporterRepository.SupporterRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin - Manage supporters (C18): list, invite, edit, change role, change organisation access,
 * deactivate (two-step wizard) and reactivate, send a password reset link, and the activity trail.
 * MFA reset and sign-out-everywhere are separate auth endpoints.
 *
 * <p><b>Guards (C18)</b>: an admin cannot change their own role or deactivate themselves, and the
 * last active admin cannot be demoted or deactivated. Both actions run in one transaction that
 * first locks every active admin row in id order ({@link SupporterRepository#lockActiveAdminIds})
 * and then decides; two admins demoting each other at once therefore run one after the other and
 * the second is refused. The acting admin must still be an active admin inside that lock.
 */
@Service
public class SupporterAdminService {

  /** What the Invite modal and Resend return. There is no invitation code. */
  public record InviteResult(long userId, Instant expiresAt) {}

  /** Deactivation wizard step 1. */
  public record DeactivationPlan(
      long supporterId, int openTickets, String blockedReason, List<SupporterView> reassignTo) {}

  /** One line of the Activity tab. Details hold only audit allow-listed fields. */
  public record ActivityView(
      long id,
      Instant occurredAt,
      String type,
      Long actorUserId,
      String actorName,
      Long targetUserId,
      String targetName,
      String clientIp,
      Map<String, Object> details) {}

  static final String SELF_ROLE = "You cannot change your own role.";
  static final String SELF_DEACTIVATE = "You cannot deactivate yourself.";
  static final String LAST_ADMIN_ROLE = "The last active admin cannot be demoted.";
  static final String LAST_ADMIN_DEACTIVATE = "The last active admin cannot be deactivated.";
  static final int ACTIVITY_LIMIT = 100;

  private final SupporterRepository repo;
  private final InvitationService invitations;
  private final UserAccessService access;
  private final SecurityVersionService securityVersion;
  private final TokenService tokens;
  private final SystemMailer mailer;
  private final AuthProperties props;
  private final AuditService audit;
  private final ObjectProvider<TicketReassignment> reassignment;
  private final JsonMapper json;
  private final Clock clock;
  private final MfaExemptionService exemptions;

  public SupporterAdminService(
      SupporterRepository repo,
      InvitationService invitations,
      UserAccessService access,
      SecurityVersionService securityVersion,
      TokenService tokens,
      SystemMailer mailer,
      AuthProperties props,
      AuditService audit,
      ObjectProvider<TicketReassignment> reassignment,
      JsonMapper json,
      Clock clock,
      MfaExemptionService exemptions) {
    this.repo = repo;
    this.invitations = invitations;
    this.access = access;
    this.securityVersion = securityVersion;
    this.tokens = tokens;
    this.mailer = mailer;
    this.props = props;
    this.audit = audit;
    this.reassignment = reassignment;
    this.json = json;
    this.clock = clock;
    this.exemptions = exemptions;
  }

  /** The ticket module's bean when present, else no tickets ({@link TicketReassignment#NONE}). */
  TicketReassignment tickets() {
    return reassignment.getIfAvailable(() -> TicketReassignment.NONE);
  }

  @Transactional(readOnly = true)
  public List<SupporterView> list(AccessScope scope, Role role, UserStatus status) {
    if (role == Role.CUSTOMER) {
      throw ApiException.invalidRequest("Agents are engineers or admins.");
    }
    List<SupporterRow> rows = repo.list(scope, role, status);
    long activeAdmins = activeAdminCount(scope, role, status, rows);
    return rows.stream().map(r -> view(scope, r, activeAdmins)).toList();
  }

  @Transactional(readOnly = true)
  public SupporterView get(AccessScope scope, long id) {
    SupporterRow row = repo.find(scope, id).orElseThrow(AccessScope::notFound);
    long activeAdmins = repo.list(scope, Role.ADMIN, UserStatus.ACTIVE).size();
    return view(scope, row, activeAdmins);
  }

  /**
   * Invites a supporter (single-use link valid 48 h, no code) and sets an agent's organisations in
   * the same transaction.
   */
  @Transactional
  public InviteResult invite(
      AccessScope scope,
      String email,
      String name,
      Role role,
      boolean allOrganisations,
      Set<Long> organisationIds,
      String clientIp) {
    scope.requireAdmin();
    if (role == null || !role.isSupporter()) {
      throw ApiException.invalidRequest("Choose Agent or Admin.");
    }
    String displayName = ProfileFields.displayName(name);
    InvitationService.Created created =
        invitations.invite(actor(scope), email, displayName, role, null, clientIp);
    if (role == Role.AGENT) {
      access.setAgentAccess(scope, created.userId(), allOrganisations, organisationIds, clientIp);
    }
    return new InviteResult(created.userId(), created.expiresAt());
  }

  /** Re-sends a pending invitation: a new link; the old link stops working. */
  @Transactional
  public InviteResult resendInvitation(AccessScope scope, long id, String clientIp) {
    LockedSupporter s = repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    if (s.status() != UserStatus.INVITED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_invited", "This person has already accepted their invitation.");
    }
    InvitationService.Created created =
        invitations.invite(actor(scope), s.email(), s.name(), s.role(), null, clientIp);
    return new InviteResult(created.userId(), created.expiresAt());
  }

  /**
   * Revokes a pending invitation: the link stops working and the never-activated account is
   * removed, so the address can be invited again later.
   */
  @Transactional
  public void revokeInvitation(AccessScope scope, long id, String clientIp) {
    LockedSupporter s = repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    if (s.status() != UserStatus.INVITED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_invited", "This person has already accepted their invitation.");
    }
    repo.revokeUnusedTokens(scope, id, clock.instant());
    repo.deleteInvited(scope, id);
    audit.record(
        AuthAuditEvent.INVITATION_REVOKED,
        actor(scope),
        id,
        clientIp,
        Map.of("reason", "admin", "email", s.email(), "role", s.role()));
  }

  /** Admin edit of name and job title (no step-up, no session change). */
  @Transactional
  public SupporterView update(
      AccessScope scope, long id, String name, String jobTitle, String clientIp) {
    String n = ProfileFields.displayName(name);
    String t = ProfileFields.jobTitle(jobTitle);
    repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    repo.updateNameAndTitle(scope, id, n, t, clock.instant());
    audit.record(SupporterAuditEvent.SUPPORTER_UPDATED, actor(scope), id, clientIp, Map.of());
    return get(scope, id);
  }

  /**
   * Changes a supporter's role (the endpoint requires a fresh step-up). Guards: not yourself, not
   * the last active admin. Ends the supporter's sessions and trusted devices (security_version).
   */
  @Transactional
  public SupporterView changeRole(AccessScope scope, long id, Role role, String clientIp) {
    if (role == null || !role.isSupporter()) {
      throw ApiException.invalidRequest("Choose Agent or Admin.");
    }
    Guarded g = lockForGuard(scope, id, SELF_ROLE);
    LockedSupporter target = g.target();
    if (target.role() == role) {
      return get(scope, id);
    }
    if (target.role() == Role.ADMIN && target.status() == UserStatus.ACTIVE) {
      g.requireNotLastAdmin(LAST_ADMIN_ROLE);
    }
    g.requireActorStillAdmin();
    if (role != Role.AGENT) {
      // Admins always use two-step; the exemption goes (audited) before the role changes.
      exemptions.clear(actor(scope), id, "role_change", clientIp);
    }
    repo.setRole(scope, id, role, clock.instant());
    securityVersion.bump(id, SecurityVersionService.Reason.ROLE, null, actor(scope), clientIp);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("role", role);
    d.put("old_role", target.role());
    audit.record(SupporterAuditEvent.SUPPORTER_ROLE_CHANGED, actor(scope), id, clientIp, d);
    if (target.status() == UserStatus.ACTIVE) {
      mailer.securityNotice(target.email(), target.name(), SecurityNotice.ROLE_CHANGED);
    }
    return get(scope, id);
  }

  /** Wizard step 1: what must move, and whether deactivation is allowed at all. */
  @Transactional(readOnly = true)
  public DeactivationPlan deactivationPlan(AccessScope scope, long id) {
    SupporterView s = get(scope, id);
    String blocked = s.deactivateBlockedReason();
    if (blocked == null && s.status() != UserStatus.ACTIVE) {
      blocked =
          s.status() == UserStatus.INVITED
              ? "This person has not accepted their invitation yet - revoke it instead."
              : "This agent is already deactivated.";
    }
    List<SupporterView> targets =
        list(scope, null, UserStatus.ACTIVE).stream().filter(v -> v.id() != id).toList();
    return new DeactivationPlan(id, s.openTickets(), blocked, targets);
  }

  /**
   * Wizard step 2: moves open tickets ({@link TicketReassignment}) to {@code reassignTo} or to
   * Unassigned (null), deactivates, revokes unused link tokens and ends every session and trusted
   * device at once - all in one transaction. No hard delete.
   */
  @Transactional
  public SupporterView deactivate(AccessScope scope, long id, Long reassignTo, String clientIp) {
    Guarded g = lockForGuard(scope, id, SELF_DEACTIVATE);
    LockedSupporter target = g.target();
    if (target.status() != UserStatus.ACTIVE) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "not_active",
          target.status() == UserStatus.INVITED
              ? "This person has not accepted their invitation yet - revoke it instead."
              : "This agent is already deactivated.");
    }
    if (target.role() == Role.ADMIN) {
      g.requireNotLastAdmin(LAST_ADMIN_DEACTIVATE);
    }
    g.requireActorStillAdmin();
    if (reassignTo != null && (reassignTo == id || !repo.isActiveSupporter(scope, reassignTo))) {
      throw ApiException.invalidRequest("Choose another active agent, or Unassigned.");
    }
    long actor = actor(scope);
    Instant now = clock.instant();
    exemptions.clear(actor, id, "deactivation", clientIp);
    repo.setStatus(scope, id, UserStatus.ACTIVE, UserStatus.DEACTIVATED, now);
    repo.revokeUnusedTokens(scope, id, now);
    securityVersion.bump(id, SecurityVersionService.Reason.DEACTIVATION, null, actor, clientIp);
    // Last, so a failing hook rolls everything above back with it.
    int moved = tickets().reassignOpenTickets(scope, id, reassignTo, actor);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("tickets", moved);
    if (reassignTo != null) {
      d.put("reassigned_to", reassignTo);
    }
    audit.record(SupporterAuditEvent.SUPPORTER_DEACTIVATED, actor, id, clientIp, d);
    mailer.securityNotice(target.email(), target.name(), SecurityNotice.ACCOUNT_DEACTIVATED);
    return get(scope, id);
  }

  /** Lets a deactivated supporter sign in again (password and MFA as before). */
  @Transactional
  public SupporterView reactivate(AccessScope scope, long id, String clientIp) {
    LockedSupporter target = repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    if (target.status() != UserStatus.DEACTIVATED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_deactivated", "This agent is not deactivated.");
    }
    if (target.email() == null) {
      // An erased (pseudonymised) account has no address and can never sign in again.
      throw new ApiException(
          HttpStatus.CONFLICT, "erased", "This person's data was erased; invite them again.");
    }
    repo.setStatus(scope, id, UserStatus.DEACTIVATED, UserStatus.ACTIVE, clock.instant());
    audit.record(SupporterAuditEvent.SUPPORTER_REACTIVATED, actor(scope), id, clientIp, Map.of());
    return get(scope, id);
  }

  /** Emails the supporter a password reset link (the admin never sees or sets a password). */
  @Transactional
  public void sendPasswordReset(AccessScope scope, long id, String clientIp) {
    if (id == actor(scope)) {
      throw new ApiException(
          HttpStatus.CONFLICT, "own_account", "Use My settings - Security for your own account.");
    }
    LockedSupporter target = repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    if (target.status() != UserStatus.ACTIVE) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_active", "Only active agents can reset their password.");
    }
    TokenService.Issued t =
        tokens.issue(id, TokenPurpose.PASSWORD_RESET, props.passwordResetValidity());
    mailer.passwordReset(
        target.email(), target.name(), props.link("/reset-password", t.token()), t.expiresAt());
    audit.record(
        AuthAuditEvent.PASSWORD_RESET_REQUESTED,
        actor(scope),
        id,
        clientIp,
        Map.of("reason", "admin", "status", "sent"));
  }

  @Transactional(readOnly = true)
  public List<ActivityView> activity(AccessScope scope, long id) {
    repo.find(scope, id).orElseThrow(AccessScope::notFound);
    return repo.activity(scope, id, ACTIVITY_LIMIT).stream().map(this::activityView).toList();
  }

  private ActivityView activityView(ActivityRow r) {
    Map<String, Object> details;
    try {
      details = json.readValue(r.detailsJson(), new TypeReference<Map<String, Object>>() {});
    } catch (RuntimeException e) {
      details = Map.of();
    }
    return new ActivityView(
        r.id(),
        r.occurredAt(),
        r.type(),
        r.actorUserId(),
        r.actorName(),
        r.targetUserId(),
        r.targetName(),
        r.clientIp(),
        details);
  }

  /** The locked target plus the active admins the guard lock saw. */
  private record Guarded(LockedSupporter target, List<Long> activeAdmins, long actor) {
    void requireNotLastAdmin(String message) {
      if (activeAdmins.size() <= 1) {
        throw new ApiException(HttpStatus.CONFLICT, "last_admin", message);
      }
    }

    /** The acting admin may have been demoted or deactivated by a request that just committed. */
    void requireActorStillAdmin() {
      if (!activeAdmins.contains(actor)) {
        throw new ApiException(
            HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this.");
      }
    }
  }

  /**
   * Takes the guard lock - every active admin row, then the target - and refuses acting on
   * yourself. Callers then apply the last-admin check where it matters and {@link
   * Guarded#requireActorStillAdmin()}.
   */
  private Guarded lockForGuard(AccessScope scope, long id, String selfMessage) {
    long actor = actor(scope);
    List<Long> admins = repo.lockActiveAdminIds(scope);
    if (id == actor) {
      throw new ApiException(HttpStatus.CONFLICT, "own_account", selfMessage);
    }
    LockedSupporter target = repo.lock(scope, id).orElseThrow(AccessScope::notFound);
    return new Guarded(target, admins, actor);
  }

  private long activeAdminCount(
      AccessScope scope, Role role, UserStatus status, List<SupporterRow> rows) {
    if ((role == null || role == Role.ADMIN) && (status == null || status == UserStatus.ACTIVE)) {
      return rows.stream()
          .filter(r -> r.role() == Role.ADMIN && r.status() == UserStatus.ACTIVE)
          .count();
    }
    return repo.list(scope, Role.ADMIN, UserStatus.ACTIVE).size();
  }

  private SupporterView view(AccessScope scope, SupporterRow r, long activeAdmins) {
    long actor = actor(scope);
    boolean self = r.id() == actor;
    boolean lastAdmin =
        r.role() == Role.ADMIN && r.status() == UserStatus.ACTIVE && activeAdmins <= 1;
    String roleReason = self ? SELF_ROLE : lastAdmin ? LAST_ADMIN_ROLE : null;
    String deactivateReason = self ? SELF_DEACTIVATE : lastAdmin ? LAST_ADMIN_DEACTIVATE : null;
    int open = r.status() == UserStatus.INVITED ? 0 : tickets().openTicketCount(scope, r.id());
    // An exemption whose end has passed is shown as none (it is deleted at the next sign-in).
    boolean exempt =
        r.mfaExemptUntil() != null
            && r.role() == Role.AGENT
            && r.status() == UserStatus.ACTIVE
            && r.mfaExemptUntil().isAfter(clock.instant());
    return new SupporterView(
        r.id(),
        r.email(),
        r.name(),
        r.jobTitle(),
        r.role(),
        r.status(),
        r.allOrganisations(),
        r.organisationIds(),
        r.mfaEnrolled(),
        r.lastSignInAt(),
        open,
        r.outOfOffice(),
        r.outOfOfficeUntil(),
        r.status() == UserStatus.INVITED ? r.invitationExpiresAt() : null,
        r.createdAt(),
        self,
        roleReason,
        deactivateReason,
        exempt ? r.mfaExemptUntil() : null,
        exempt ? r.mfaExemptReason() : null);
  }

  private static long actor(AccessScope scope) {
    scope.requireAdmin();
    return scope.principalId().orElseThrow();
  }
}
