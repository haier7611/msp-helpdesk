package com.ticketsystem.api.auth;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * First-admin setup (C8). There is no default password: while no admin exists and {@code
 * TS_SETUP_TOKEN} is set (32+ characters), the token creates the first admin once. The token is
 * compared in constant time, never logged, never echoed; once any admin exists setup is refused for
 * good, whatever the token. Concurrent attempts are serialised by a transaction-level advisory
 * lock, so two requests cannot both create an admin.
 */
@Service
public class SetupService {

  static final int MIN_TOKEN_LENGTH = 32;
  private static final long SETUP_LOCK_KEY = 0x7473_7365_7475_70L; // "tssetup"
  private static final Logger log = LoggerFactory.getLogger(SetupService.class);

  private final String setupTokenHash;
  private final UserRepository users;
  private final PasswordPolicy policy;
  private final PasswordEncoder encoder;
  private final SystemMailer mailer;
  private final RateLimiter rateLimiter;
  private final AuditService audit;
  private final AuthProperties props;
  private final TransactionTemplate tx;
  private final JdbcClient jdbc;
  private final Clock clock;

  public SetupService(
      @Value("${TS_SETUP_TOKEN:}") String setupToken,
      UserRepository users,
      PasswordPolicy policy,
      PasswordEncoder encoder,
      SystemMailer mailer,
      RateLimiter rateLimiter,
      AuditService audit,
      AuthProperties props,
      TransactionTemplate tx,
      JdbcClient jdbc,
      Clock clock) {
    // Keep only a hash in memory; comparisons hash the candidate the same way (equal lengths).
    if (setupToken == null || setupToken.isBlank()) {
      this.setupTokenHash = null;
    } else if (setupToken.strip().length() < MIN_TOKEN_LENGTH) {
      log.warn(
          "TS_SETUP_TOKEN is set but shorter than {} characters - first-admin setup is disabled"
              + " (value not logged)",
          MIN_TOKEN_LENGTH);
      this.setupTokenHash = null;
    } else {
      this.setupTokenHash = SecureTokens.sha256Hex(setupToken.strip());
    }
    this.users = users;
    this.policy = policy;
    this.encoder = encoder;
    this.mailer = mailer;
    this.rateLimiter = rateLimiter;
    this.audit = audit;
    this.props = props;
    this.tx = tx;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Setup is offered only while a valid token is configured and no admin exists. */
  public boolean available() {
    return setupTokenHash != null && !users.anyAdmin();
  }

  public AppUser createFirstAdmin(
      String token, String rawEmail, String rawName, String password, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.SETUP_IP, clientIp);
    if (setupTokenHash == null) {
      throw unavailable();
    }
    String email =
        Emails.normalise(rawEmail)
            .orElseThrow(() -> ApiException.invalidRequest("Enter a valid email address."));
    String name = rawName == null ? "" : rawName.strip();
    if (name.isEmpty() || name.length() > 200) {
      throw ApiException.invalidRequest("Enter a name of up to 200 characters.");
    }
    return tx.execute(
        status -> {
          jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(SETUP_LOCK_KEY).query().singleRow();
          // Failures are audited in a fresh transaction: the throw rolls this one back.
          if (users.anyAdmin()) {
            recordFailure(clientIp, "admin_exists");
            throw unavailable();
          }
          String candidate = token == null ? "" : token.strip();
          if (!SecureTokens.constantTimeEquals(setupTokenHash, SecureTokens.sha256Hex(candidate))) {
            recordFailure(clientIp, "wrong_token");
            throw new ApiException(
                HttpStatus.FORBIDDEN, "invalid_setup_token", "The setup token is not right.");
          }
          policy
              .check(password, email)
              .ifPresent(
                  v -> {
                    throw ApiException.weakPassword(v);
                  });
          long id =
              users.insertActive(
                  email, name, Role.ADMIN, encoder.encode(password), clock.instant());
          audit.record(
              AuthAuditEvent.SETUP_COMPLETED, id, id, clientIp, Map.of("role", Role.ADMIN));
          mailer.firstAdminSetup(email, name, props.page("/sign-in"));
          return users.findById(id).orElseThrow();
        });
  }

  private void recordFailure(String clientIp, String reason) {
    TransactionTemplate fresh = new TransactionTemplate(tx.getTransactionManager());
    fresh.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    fresh.executeWithoutResult(
        s ->
            audit.record(
                AuthAuditEvent.SETUP_FAILED, null, null, clientIp, Map.of("reason", reason)));
  }

  private static ApiException unavailable() {
    return new ApiException(
        HttpStatus.NOT_FOUND, "setup_unavailable", "Setup is not available on this system.");
  }
}
