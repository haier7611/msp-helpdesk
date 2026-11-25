package com.ticketsystem.api.auth;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AuthTokenRepository.TokenRow;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitations (C7). Supporters (agents, admins): link valid 48 hours; customers: link valid 7 days.
 * No code for anyone (see docs/10-decision-log.md ADR-046; this replaces the C7 6-digit code):
 * opening the single-use link and setting a password is enough. Invitations issued before codes
 * were removed may still carry a stored code hash; it is ignored, so those links work with a
 * password only too. Re-inviting a still-invited person voids the older link. Accepting sets the
 * password and activates the account; the person then signs in normally.
 */
@Service
public class InvitationService {

  /** The result shown to the inviting admin. */
  public record Created(long userId, Instant expiresAt) {}

  /** What the accept page needs to know before the person types anything. */
  public record Pending(String email, String name) {}

  private final UserRepository users;
  private final TokenService tokens;
  private final SystemMailer mailer;
  private final PasswordPolicy policy;
  private final PasswordEncoder encoder;
  private final RateLimiter rateLimiter;
  private final AuditService audit;
  private final AuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public InvitationService(
      UserRepository users,
      TokenService tokens,
      SystemMailer mailer,
      PasswordPolicy policy,
      PasswordEncoder encoder,
      RateLimiter rateLimiter,
      AuditService audit,
      AuthProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.mailer = mailer;
    this.policy = policy;
    this.encoder = encoder;
    this.rateLimiter = rateLimiter;
    this.audit = audit;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Creates (or re-sends) an invitation. Admin only - enforced by the /api/v1/admin tree. A
   * customer is invited into one organisation ({@code organisationId}, required); supporters have
   * none.
   */
  public Created invite(
      long actorId,
      String rawEmail,
      String rawName,
      Role role,
      Long organisationId,
      String clientIp) {
    String email =
        Emails.normalise(rawEmail)
            .orElseThrow(() -> ApiException.invalidRequest("Enter a valid email address."));
    String name = rawName == null ? "" : rawName.strip();
    if (name.isEmpty() || name.length() > 200) {
      throw ApiException.invalidRequest("Enter a name of up to 200 characters.");
    }
    if (role == null) {
      throw ApiException.invalidRequest("Choose a role.");
    }
    if (role == Role.CUSTOMER && organisationId == null) {
      throw ApiException.invalidRequest("Choose the customer's organisation.");
    }
    if (role.isSupporter() && organisationId != null) {
      throw ApiException.invalidRequest("Agents do not belong to a client organisation.");
    }
    try {
      return tx.execute(
          status -> {
            Instant now = clock.instant();
            Optional<AppUser> existing = users.findByEmail(email);
            long userId;
            if (existing.isPresent()) {
              if (existing.get().status() != UserStatus.INVITED) {
                throw new ApiException(
                    HttpStatus.CONFLICT,
                    "user_exists",
                    "Someone with this email already has an account.");
              }
              userId = existing.get().id();
              users.updateInvited(userId, name, role, organisationId, now);
            } else {
              userId = users.insertInvited(email, name, role, organisationId, now);
            }
            Duration validFor =
                role.isSupporter()
                    ? props.supporterInvitationValidity()
                    : props.customerInvitationValidity();
            TokenService.Issued t = tokens.issue(userId, TokenPurpose.INVITATION, validFor);
            String link = props.link("/accept-invitation", t.token());
            if (role.isSupporter()) {
              mailer.supporterInvitation(email, name, link, t.expiresAt());
            } else {
              mailer.customerInvitation(email, name, link, t.expiresAt());
            }
            audit.record(
                AuthAuditEvent.INVITATION_CREATED,
                actorId,
                userId,
                clientIp,
                Map.of("role", role, "status", existing.isPresent() ? "resent" : "new"));
            return new Created(userId, t.expiresAt());
          });
    } catch (DuplicateKeyException e) {
      // Two admins inviting the same new address at once.
      throw new ApiException(
          HttpStatus.CONFLICT, "user_exists", "Someone with this email already has an account.");
    } catch (DataIntegrityViolationException e) {
      // The organisation id does not exist (foreign key).
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "unknown_organisation", "That organisation does not exist.");
    }
  }

  /** Read-only: is this link still good, and whose invitation is it? */
  public Pending inspect(String rawToken, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.TOKEN_IP, clientIp);
    TokenRow row =
        tokens.findUsable(rawToken, TokenPurpose.INVITATION).orElseThrow(ApiException::invalidLink);
    AppUser user =
        users
            .findById(row.userId())
            .filter(u -> u.status() == UserStatus.INVITED)
            .orElseThrow(ApiException::invalidLink);
    return new Pending(user.email(), user.name());
  }

  /**
   * Accepts an invitation: checks the password, then activates. No code; a code hash stored on an
   * invitation issued before codes were removed is ignored.
   */
  public void accept(String rawToken, String password, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.TOKEN_IP, clientIp);
    tx.executeWithoutResult(
        status -> {
          TokenRow row =
              tokens
                  .findUsableForUpdate(rawToken, TokenPurpose.INVITATION)
                  .orElseThrow(ApiException::invalidLink);
          AppUser user =
              users
                  .findByIdForUpdate(row.userId())
                  .filter(u -> u.status() == UserStatus.INVITED)
                  .orElseThrow(ApiException::invalidLink);
          policy
              .check(password, user.email())
              .ifPresent(
                  v -> {
                    throw ApiException.weakPassword(v);
                  });
          users.activate(user.id(), encoder.encode(password), clock.instant());
          tokens.consume(row);
          audit.record(
              AuthAuditEvent.INVITATION_ACCEPTED,
              user.id(),
              user.id(),
              clientIp,
              Map.of("role", user.role()));
        });
  }
}
