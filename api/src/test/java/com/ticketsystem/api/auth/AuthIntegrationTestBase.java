package com.ticketsystem.api.auth;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.auth.mail.OutboxSystemMailer;
import com.ticketsystem.api.auth.mfa.MfaRepository;
import com.ticketsystem.api.auth.mfa.Totp;
import com.ticketsystem.api.auth.throttle.AgentMessageLimiter;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.auth.throttle.SignInBackoff;
import com.ticketsystem.api.crypto.DataCipher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared Spring context for the auth integration tests: real Postgres (two roles), the real
 * security filter chain and Spring Session JDBC through MockMvc, a controllable clock, and a
 * recording mailer in front of the real outbox mailer. Every test starts with fresh rate limits and
 * a reset clock; users get unique synthetic emails so tests do not interfere.
 */
@SpringBootTest(
    properties = {
      "TS_SETUP_TOKEN=" + AuthIntegrationTestBase.SETUP_TOKEN,
      "app.auth.public-base-url=https://support.example.test",
      "app.auth.secure-cookies=true",
      "app.mail.support-name=Example MSP Support",
      "app.mail.outbox.auto-start=false",
      "app.attachments-dir=${java.io.tmpdir}/ticketsys-test-attachments"
    })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuthIntegrationTestBase.AuthTestBeans.class})
public abstract class AuthIntegrationTestBase {

  /** Synthetic, test-only setup token (not a secret). */
  static final String SETUP_TOKEN = "setup-SYNTHETIC-0123456789abcdef0123456789";

  /** A strong synthetic password that passes the policy. */
  protected static final String GOOD_PASSWORD = "Violet-Tractor-Sundial-42";

  private static final AtomicInteger IPS = new AtomicInteger(1);

  @Autowired protected MockMvc mvc;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected UserRepository users;
  @Autowired protected PasswordEncoder encoder;
  @Autowired protected MutableClock clock;
  @Autowired protected RecordingSystemMailer mailer;
  @Autowired protected RateLimiter rateLimiter;
  @Autowired protected AgentMessageLimiter agentMessageLimiter;
  @Autowired protected SignInBackoff backoff;
  @Autowired protected MfaRepository mfaRepository;
  @Autowired protected DataCipher dataCipher;

  @TestConfiguration(proxyBeanMethods = false)
  static class AuthTestBeans {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    RecordingSystemMailer recordingSystemMailer(OutboxSystemMailer real) {
      return new RecordingSystemMailer(real);
    }

    /** WP-04: records ticket events after commit, as WP-07/WP-09 listeners will see them. */
    @Bean
    com.ticketsystem.api.ticket.TicketEventRecorder ticketEventRecorder() {
      return new com.ticketsystem.api.ticket.TicketEventRecorder();
    }
  }

  @BeforeEach
  void resetShared() {
    clock.reset();
    rateLimiter.reset();
    agentMessageLimiter.reset();
    backoff.reset();
    mailer.clear();
  }

  /**
   * Argon2id hash of {@link #GOOD_PASSWORD}, computed once per test JVM and reused for every test
   * user (WP-13: hashing is deliberately slow, and the suite creates thousands of users). Sign-in
   * still verifies it with the real encoder; tests about hashing itself call the encoder directly.
   */
  private static volatile String goodPasswordHash;

  protected String goodPasswordHash() {
    String hash = goodPasswordHash;
    if (hash == null) {
      hash = encoder.encode(GOOD_PASSWORD);
      goodPasswordHash = hash;
    }
    return hash;
  }

  /** A browser with its own documentation-range IP address. */
  protected Browser browser() {
    int n = IPS.getAndIncrement();
    return new Browser(mvc, "198.51.100." + (n % 250 + 1));
  }

  protected static String uniqueEmail(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
  }

  /** An active user with {@link #GOOD_PASSWORD}; a customer gets a fresh organisation (WP-03). */
  protected AppUser activeUser(Role role) {
    return activeUser(role, role == Role.CUSTOMER ? newOrganisation("Example Clinic") : null);
  }

  /** An active user in {@code organisationId} (customers) or none (supporters). */
  protected AppUser activeUser(Role role, Long organisationId) {
    String email = uniqueEmail(role.name().toLowerCase());
    long id =
        users.insertActive(
            email, "Sam Example", role, organisationId, goodPasswordHash(), Instant.now());
    return users.findById(id).orElseThrow();
  }

  /**
   * A synthetic organisation straight in the database (prefix, counter and all), with a unique name
   * and a unique prefix {@code Q<n>...}; returns its id. For tests that need an organisation but
   * are not about creating one.
   */
  protected long newOrganisation(String name) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long id =
        jdbc.queryForObject(
            "INSERT INTO organisation (name, timezone, created_at, updated_at)"
                + " VALUES (?, 'Asia/Ho_Chi_Minh', now(), now()) RETURNING id",
            Long.class,
            name + " " + suffix);
    String prefix = uniquePrefix();
    jdbc.update(
        "INSERT INTO organisation_prefix (prefix, organisation_id, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', now())",
        prefix,
        id);
    jdbc.update(
        "INSERT INTO ticket_counter (counter_key, organisation_id, next_number, updated_at)"
            + " VALUES (?, ?, 1, now())",
        "ORG-" + id,
        id);
    return id;
  }

  /**
   * A prefix that never repeats within a test run: Q plus 5 base-36 characters from a counter that
   * starts at a random offset. Random-only prefixes (5 hex characters) collided now and then once a
   * run created a few thousand organisations, failing unrelated tests with DuplicateKeyException.
   */
  private static final java.util.concurrent.atomic.AtomicLong PREFIX_COUNTER =
      new java.util.concurrent.atomic.AtomicLong(
          java.util.concurrent.ThreadLocalRandom.current()
              .nextLong(0, 36L * 36 * 36 * 36 * 36 / 2));

  protected static String uniquePrefix() {
    long n = PREFIX_COUNTER.getAndIncrement() % (36L * 36 * 36 * 36 * 36);
    String body = Long.toString(n, 36).toUpperCase(Locale.ROOT);
    return "Q" + "0".repeat(5 - body.length()) + body;
  }

  /**
   * A browser fully signed in as {@code user}. Supporters are enrolled in TOTP first (directly in
   * the database, see {@link #enrol}) and then pass the real TOTP step with a valid code.
   */
  protected Browser signedIn(AppUser user) throws Exception {
    return signedIn(user, browser());
  }

  protected Browser signedIn(AppUser user, Browser b) throws Exception {
    if (user.role().isSupporter() && !secrets.containsKey(user.id())) {
      enrol(user);
    }
    var response = b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD));
    if (response.getStatus() != 200) {
      throw new AssertionError("sign-in failed: " + response.getStatus());
    }
    if (response.getContentAsString().contains("\"authLevel\":\"MFA_REQUIRED\"")) {
      var verified = b.post("/api/v1/auth/mfa/verify", "{\"code\":" + Browser.q(totp(user)) + "}");
      if (verified.getStatus() != 200) {
        throw new AssertionError("MFA verify failed: " + verified.getStatus());
      }
    }
    return b;
  }

  /** TOTP secrets of users enrolled by {@link #enrol}, so tests can make valid codes. */
  protected final Map<Long, byte[]> secrets = new ConcurrentHashMap<>();

  /** Enrols a supporter in TOTP directly (encrypted like the real flow); returns the secret. */
  protected byte[] enrol(AppUser user) {
    byte[] secret = Totp.newSecret();
    mfaRepository.savePending(
        user.id(), dataCipher.encrypt(secret, "user_mfa:" + user.id()), clock.instant());
    mfaRepository.confirmPending(user.id(), 0, clock.instant());
    secrets.put(user.id(), secret);
    return secret;
  }

  /**
   * A valid, not yet used TOTP code for an enrolled user. Codes are accepted once per 30-second
   * step, so the clock moves on to the next unused step when needed.
   */
  protected String totp(AppUser user) {
    byte[] secret = secrets.get(user.id());
    if (secret == null) {
      throw new AssertionError("user " + user.id() + " was not enrolled by the test");
    }
    Long last =
        jdbc.queryForObject(
            "SELECT last_used_step FROM user_mfa WHERE user_id = ?", Long.class, user.id());
    long step = Totp.stepAt(clock.instant());
    if (last != null && step <= last) {
      clock.advance(Duration.ofSeconds((last - step + 1) * Totp.STEP_SECONDS));
      step = Totp.stepAt(clock.instant());
    }
    return Totp.codeAt(secret, step);
  }

  protected static String signInJson(String email, String password) {
    return "{\"email\":" + Browser.q(email) + ",\"password\":" + Browser.q(password) + "}";
  }

  protected List<Map<String, Object>> auditEvents(String type, Long targetUserId) {
    return jdbc.queryForList(
        "SELECT type, actor_user_id, target_user_id, client_ip, details::text AS details"
            + " FROM audit_event WHERE type = ? AND target_user_id IS NOT DISTINCT FROM ?"
            + " ORDER BY id",
        type,
        targetUserId);
  }

  protected List<Map<String, Object>> auditEventsForEmail(String type, String email) {
    return jdbc.queryForList(
        "SELECT type, target_user_id, details::text AS details FROM audit_event"
            + " WHERE type = ? AND details->>'email' = ? ORDER BY id",
        type,
        email);
  }
}
