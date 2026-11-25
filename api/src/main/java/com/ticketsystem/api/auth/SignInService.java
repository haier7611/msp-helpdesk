package com.ticketsystem.api.auth;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.auth.throttle.SignInBackoff;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password sign-in. Unknown email, wrong password and an account that is not active all get the
 * same 401 after the same Argon2 work (a dummy hash is checked when there is no real one), so the
 * answer and its timing do not reveal which accounts exist. Every attempt is audit-logged.
 */
@Service
public class SignInService {

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final SignInBackoff backoff;
  private final RateLimiter rateLimiter;
  private final AuditService audit;
  private final DeviceService devices;
  private final String dummyHash;

  public SignInService(
      UserRepository users,
      PasswordEncoder encoder,
      SignInBackoff backoff,
      RateLimiter rateLimiter,
      AuditService audit,
      DeviceService devices) {
    this.users = users;
    this.encoder = encoder;
    this.backoff = backoff;
    this.rateLimiter = rateLimiter;
    this.audit = audit;
    this.devices = devices;
    this.dummyHash = encoder.encode(SecureTokens.newToken());
  }

  /**
   * The user whose password matched, or {@link ApiException#invalidCredentials()}; may throw 429.
   * {@code deviceToken} is the browser's device cookie: a known device of the account is exempt
   * from the account-wide back-off, so the real user is not slowed by an attack from elsewhere. The
   * caller decides the auth level (MFA) afterwards.
   */
  public AppUser signIn(String rawEmail, String password, String clientIp, String deviceToken) {
    rateLimiter.consume(RateLimiter.Scope.SIGN_IN_IP, clientIp);
    Optional<String> email = Emails.normalise(rawEmail);
    String account = email.orElse("invalid");
    Optional<AppUser> user = email.flatMap(users::findByEmail);
    boolean knownDevice = user.isPresent() && devices.isKnown(user.get().id(), deviceToken);
    backoff.check(account, clientIp, knownDevice);

    String hash = user.map(AppUser::passwordHash).orElse(null);
    boolean passwordOk =
        password != null
            && password.length() <= PasswordPolicy.MAX_LENGTH
            && encoder.matches(password, hash != null ? hash : dummyHash)
            && hash != null;

    if (!passwordOk || !user.get().isActive()) {
      String reason =
          user.isEmpty() ? "unknown_account" : !passwordOk ? "wrong_password" : "not_active";
      int failures = backoff.recordFailure(account, clientIp, knownDevice);
      Long target = user.map(AppUser::id).orElse(null);
      Map<String, Object> details = new HashMap<>();
      details.put("reason", reason);
      details.put("method", "password");
      details.put("email", email.orElse(null));
      audit.record(AuthAuditEvent.SIGN_IN_FAILED, null, target, clientIp, details);
      if (failures > 2) {
        audit.record(
            AuthAuditEvent.SIGN_IN_THROTTLED,
            null,
            target,
            clientIp,
            Map.of("attempts", failures, "method", "password"));
      }
      throw ApiException.invalidCredentials();
    }

    AppUser u = user.get();
    backoff.recordSuccess(account, clientIp);
    if (encoder.upgradeEncoding(u.passwordHash())) {
      users.upgradePasswordHash(u.id(), encoder.encode(password));
    }
    audit.record(
        AuthAuditEvent.SIGN_IN_SUCCEEDED,
        u.id(),
        u.id(),
        clientIp,
        Map.of("method", "password", "role", u.role()));
    return u;
  }
}
