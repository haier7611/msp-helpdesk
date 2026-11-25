package com.ticketsystem.api.auth;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.mail.SecurityNotice;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import com.ticketsystem.api.auth.throttle.SignInBackoff;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A signed-in user changes their own password. The current password is required (and its guesses go
 * through the same back-off as sign-in); the new one must pass the policy. A security notice is
 * emailed. security_version is bumped and the user's other sessions end at once. Every outstanding
 * reset and magic link for the user is voided in the same transaction, as on a completed reset, so
 * a link taken from a compromised mailbox dies with the old password.
 */
@Service
public class PasswordChangeService {

  private final UserRepository users;
  private final AuthTokenRepository tokenRows;
  private final PasswordEncoder encoder;
  private final PasswordPolicy policy;
  private final SignInBackoff backoff;
  private final SystemMailer mailer;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final SecurityVersionService securityVersion;

  public PasswordChangeService(
      UserRepository users,
      AuthTokenRepository tokenRows,
      PasswordEncoder encoder,
      PasswordPolicy policy,
      SignInBackoff backoff,
      SystemMailer mailer,
      AuditService audit,
      TransactionTemplate tx,
      Clock clock,
      SecurityVersionService securityVersion) {
    this.users = users;
    this.tokenRows = tokenRows;
    this.encoder = encoder;
    this.policy = policy;
    this.backoff = backoff;
    this.mailer = mailer;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
    this.securityVersion = securityVersion;
  }

  /**
   * Returns the new security_version. Every session of the user except {@code keepSessionId} (the
   * one making the change) is ended and all trusted devices are revoked.
   */
  public int change(
      long userId,
      String currentPassword,
      String newPassword,
      String clientIp,
      String keepSessionId) {
    String account = "user:" + userId;
    backoff.check(account, clientIp);
    AppUser user =
        users
            .findById(userId)
            .filter(AppUser::isActive)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.UNAUTHORIZED, "unauthenticated", "Please sign in."));
    if (currentPassword == null
        || currentPassword.length() > PasswordPolicy.MAX_LENGTH
        || !encoder.matches(currentPassword, user.passwordHash())) {
      backoff.recordFailure(account, clientIp);
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "wrong_password", "Your current password is not right.");
    }
    backoff.recordSuccess(account, clientIp);
    policy
        .check(newPassword, user.email())
        .ifPresent(
            v -> {
              throw ApiException.weakPassword(v);
            });
    return tx.execute(
        status -> {
          users.changePassword(user.id(), encoder.encode(newPassword), clock.instant());
          tokenRows.deleteUnusedSignInLinks(user.id());
          securityVersion.afterVersionChanged(
              user.id(),
              SecurityVersionService.Reason.PASSWORD,
              keepSessionId,
              user.id(),
              clientIp);
          audit.record(AuthAuditEvent.PASSWORD_CHANGED, user.id(), user.id(), clientIp, Map.of());
          mailer.securityNotice(user.email(), user.name(), SecurityNotice.PASSWORD_CHANGED);
          return users.findById(user.id()).orElseThrow().securityVersion();
        });
  }
}
