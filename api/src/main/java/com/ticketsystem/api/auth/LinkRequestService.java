package com.ticketsystem.api.auth;

import com.ticketsystem.api.audit.AuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AuthTokenRepository.TokenRow;
import com.ticketsystem.api.auth.mail.SecurityNotice;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password reset and customer magic link (C7). A request always gets the same 202 answer; a mail
 * goes out only for an active account (password reset) or an active customer (magic link), at most
 * 3 per address per 15 minutes. Supporters and unknown emails get nothing, so the answer reveals
 * nothing about which accounts exist or what role they have.
 */
@Service
public class LinkRequestService {

  private final UserRepository users;
  private final TokenService tokens;
  private final AuthTokenRepository tokenRows;
  private final SystemMailer mailer;
  private final PasswordPolicy policy;
  private final PasswordEncoder encoder;
  private final RateLimiter rateLimiter;
  private final AuditService audit;
  private final AuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final SecurityVersionService securityVersion;

  public LinkRequestService(
      UserRepository users,
      TokenService tokens,
      AuthTokenRepository tokenRows,
      SystemMailer mailer,
      PasswordPolicy policy,
      PasswordEncoder encoder,
      RateLimiter rateLimiter,
      AuditService audit,
      AuthProperties props,
      TransactionTemplate tx,
      Clock clock,
      SecurityVersionService securityVersion) {
    this.users = users;
    this.tokens = tokens;
    this.tokenRows = tokenRows;
    this.mailer = mailer;
    this.policy = policy;
    this.encoder = encoder;
    this.rateLimiter = rateLimiter;
    this.audit = audit;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.securityVersion = securityVersion;
  }

  /** "Forgot password": any active user. Always returns normally (202). */
  public void requestPasswordReset(String rawEmail, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.LINK_REQUEST_IP, clientIp);
    Optional<String> email = Emails.normalise(rawEmail);
    Optional<AppUser> user = email.flatMap(users::findByEmail).filter(AppUser::isActive);
    tx.executeWithoutResult(
        status -> {
          boolean send =
              user.isPresent()
                  && rateLimiter.tryConsume(RateLimiter.Scope.LINK_REQUEST_EMAIL, email.get());
          auditRequest(AuthAuditEvent.PASSWORD_RESET_REQUESTED, email, user, send, clientIp);
          if (send) {
            AppUser u = user.get();
            TokenService.Issued t =
                tokens.issue(u.id(), TokenPurpose.PASSWORD_RESET, props.passwordResetValidity());
            mailer.passwordReset(
                u.email(), u.name(), props.link("/reset-password", t.token()), t.expiresAt());
          }
        });
  }

  /** Sets a new password from a reset link; the link is spent only when the password is valid. */
  public void confirmPasswordReset(String rawToken, String newPassword, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.TOKEN_IP, clientIp);
    tx.executeWithoutResult(
        status -> {
          TokenRow row =
              tokens
                  .findUsableForUpdate(rawToken, TokenPurpose.PASSWORD_RESET)
                  .orElseThrow(ApiException::invalidLink);
          AppUser user =
              users
                  .findByIdForUpdate(row.userId())
                  .filter(AppUser::isActive)
                  .orElseThrow(ApiException::invalidLink);
          policy
              .check(newPassword, user.email())
              .ifPresent(
                  v -> {
                    throw ApiException.weakPassword(v);
                  });
          users.changePassword(user.id(), encoder.encode(newPassword), clock.instant());
          tokens.consume(row);
          tokenRows.deleteUnusedSignInLinks(user.id());
          // Every session of the user ends and trusted devices are revoked.
          securityVersion.afterVersionChanged(
              user.id(), SecurityVersionService.Reason.PASSWORD, null, user.id(), clientIp);
          audit.record(
              AuthAuditEvent.PASSWORD_RESET_COMPLETED, user.id(), user.id(), clientIp, Map.of());
          mailer.securityNotice(user.email(), user.name(), SecurityNotice.PASSWORD_CHANGED);
        });
  }

  /** "Email me a sign-in link": active customers only. Always returns normally (202). */
  public void requestMagicLink(String rawEmail, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.LINK_REQUEST_IP, clientIp);
    Optional<String> email = Emails.normalise(rawEmail);
    Optional<AppUser> user =
        email
            .flatMap(users::findByEmail)
            .filter(AppUser::isActive)
            .filter(u -> u.role() == Role.CUSTOMER);
    tx.executeWithoutResult(
        status -> {
          boolean send =
              user.isPresent()
                  && rateLimiter.tryConsume(RateLimiter.Scope.LINK_REQUEST_EMAIL, email.get());
          auditRequest(AuthAuditEvent.MAGIC_LINK_REQUESTED, email, user, send, clientIp);
          if (send) {
            AppUser u = user.get();
            TokenService.Issued t =
                tokens.issue(u.id(), TokenPurpose.MAGIC_LINK, props.magicLinkValidity());
            mailer.customerMagicLink(
                u.email(), u.name(), props.link("/magic-link", t.token()), t.expiresAt());
          }
        });
  }

  /** Spends a magic link and returns the customer to sign in, or throws invalid_link. */
  public AppUser consumeMagicLink(String rawToken, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.TOKEN_IP, clientIp);
    return tx.execute(
        status -> {
          TokenRow row =
              tokens
                  .findUsableForUpdate(rawToken, TokenPurpose.MAGIC_LINK)
                  .orElseThrow(ApiException::invalidLink);
          AppUser user =
              users
                  .findByIdForUpdate(row.userId())
                  .filter(AppUser::isActive)
                  .filter(u -> u.role() == Role.CUSTOMER)
                  .orElseThrow(ApiException::invalidLink);
          tokens.consume(row);
          audit.record(
              AuthAuditEvent.SIGN_IN_SUCCEEDED,
              user.id(),
              user.id(),
              clientIp,
              Map.of("method", "magic_link", "role", user.role()));
          return user;
        });
  }

  private void auditRequest(
      AuditEvent type,
      Optional<String> email,
      Optional<AppUser> user,
      boolean sent,
      String clientIp) {
    Map<String, Object> details = new HashMap<>();
    details.put("email", email.orElse(null));
    details.put("status", sent ? "sent" : user.isPresent() ? "rate_capped" : "not_sent");
    audit.record(type, null, user.map(AppUser::id).orElse(null), clientIp, details);
  }
}
