package com.ticketsystem.api.auth.device;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.auth.SecureTokens;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.mfa.MfaAuditEvent;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Known and trusted devices (C3). A browser that completes a full sign-in gets a random 256-bit
 * token in the {@code __Host-TS_DEVICE} cookie (HttpOnly, Secure, SameSite=Lax, 400 days); the
 * {@code user_device} row keeps only its SHA-256.
 *
 * <ul>
 *   <li>Known device: suppresses the "new device" security notice and exempts the account's real
 *       owner from the account-wide sign-in back-off (so an attacker spraying one account from many
 *       addresses cannot slow the owner down on their usual browser).
 *   <li>Trusted device: a supporter ticked "trust this device for 30 days" at the code or
 *       recovery-code step. That browser gets a second, separate random token in the {@code
 *       __Host-TS_TRUST} cookie (same flags, Max-Age exactly 30 days) and the row keeps its SHA-256
 *       with {@code trusted_until} = now + 30 days, so the cookie and the record end together (the
 *       400-day device cookie alone skips nothing). The sign-in code is skipped on that browser
 *       until then, or until trust is removed. Never satisfies step-up. Checked only after a
 *       correct password (or Microsoft sign-in).
 * </ul>
 *
 * One cookie per browser: when a second user signs in on the same browser the cookie is replaced
 * with that user's token (the first user's device row stays but is no longer presented). Accepted.
 */
@Service
public class DeviceService {

  public static final Duration TRUST_PERIOD = Duration.ofDays(30);
  static final Duration COOKIE_LIFETIME = Duration.ofDays(400);

  /** A trusted device as listed in My settings - Security. */
  public record TrustedDevice(
      long id, String label, Instant createdAt, Instant lastUsedAt, Instant trustedUntil) {}

  private static final String TOKEN_ATTRIBUTE = DeviceService.class.getName() + ".token";

  private final JdbcClient jdbc;
  private final AuthProperties props;
  private final SystemMailer mailer;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;

  public DeviceService(
      JdbcClient jdbc,
      AuthProperties props,
      SystemMailer mailer,
      AuditService audit,
      Clock clock,
      TransactionTemplate tx) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.props = props;
    this.mailer = mailer;
    this.audit = audit;
    this.clock = clock;
  }

  public static String cookieName(boolean secure) {
    return secure ? "__Host-TS_DEVICE" : "TS_DEVICE";
  }

  /** The trusted-device cookie: lives exactly {@link #TRUST_PERIOD}. */
  public static String trustCookieName(boolean secure) {
    return secure ? "__Host-TS_TRUST" : "TS_TRUST";
  }

  /** The device token this request presents (set earlier in this request, else the cookie). */
  public String presentedToken(HttpServletRequest request) {
    if (request.getAttribute(TOKEN_ATTRIBUTE) instanceof String t) {
      return t;
    }
    return cookieValue(request, cookieName(props.secureCookies()));
  }

  /** The trusted-device token this request presents (the trust cookie), or null. */
  public String presentedTrustToken(HttpServletRequest request) {
    return cookieValue(request, trustCookieName(props.secureCookies()));
  }

  private static String cookieValue(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (name.equals(c.getName()) && SecureTokens.looksLikeToken(c.getValue())) {
        return c.getValue();
      }
    }
    return null;
  }

  public boolean isKnown(long userId, String token) {
    return deviceId(userId, token).isPresent();
  }

  /**
   * True when {@code trustToken} (the trust cookie) belongs to one of this user's devices and its
   * trust has not expired or been removed.
   */
  public boolean isTrusted(long userId, String trustToken) {
    if (trustToken == null || !SecureTokens.looksLikeToken(trustToken)) {
      return false;
    }
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM user_device WHERE user_id = ? AND trust_token_hash = ?"
                + " AND trusted_until > ?)")
        .params(userId, SecureTokens.sha256Hex(trustToken), ts(clock.instant()))
        .query(Boolean.class)
        .single();
  }

  /**
   * Called on every full sign-in (password for customers, TOTP / trusted device / enrolment for
   * supporters, magic link). Updates the known device, or registers this browser as a new one
   * (setting the cookie) and - when the user already had other devices - queues the "sign-in from a
   * new device" notice. The first device a user ever signs in on gets no notice.
   */
  public void onFullSignIn(
      AppUser user, HttpServletRequest request, HttpServletResponse response, String clientIp) {
    Instant now = clock.instant();
    String token = presentedToken(request);
    Optional<Long> id = deviceId(user.id(), token);
    if (id.isPresent()) {
      jdbc.sql("UPDATE user_device SET last_used_at = ?, last_ip = ? WHERE id = ?")
          .params(ts(now), truncate(clientIp, 64), id.get())
          .update();
      return;
    }
    boolean hadDevices =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_device WHERE user_id = ?)")
            .param(user.id())
            .query(Boolean.class)
            .single();
    String fresh = SecureTokens.newToken();
    String label = truncate(DeviceLabels.fromUserAgent(request.getHeader("User-Agent")), 100);
    // The device row, its audit event and the notice commit together (the outbox needs a
    // transaction).
    tx.executeWithoutResult(
        status -> {
          jdbc.sql(
                  "INSERT INTO user_device (user_id, token_hash, label, created_at, last_used_at,"
                      + " last_ip) VALUES (?, ?, ?, ?, ?, ?)")
              .params(
                  user.id(),
                  SecureTokens.sha256Hex(fresh),
                  label,
                  ts(now),
                  ts(now),
                  truncate(clientIp, 64))
              .update();
          if (hadDevices) {
            audit.record(
                MfaAuditEvent.NEW_DEVICE_SIGN_IN,
                user.id(),
                user.id(),
                clientIp,
                Map.of("device", label));
            mailer.newDeviceSignIn(user.email(), user.name(), label);
          }
        });
    request.setAttribute(TOKEN_ATTRIBUTE, fresh);
    ResponseCookie cookie =
        ResponseCookie.from(cookieName(props.secureCookies()), fresh)
            .httpOnly(true)
            .secure(props.secureCookies())
            .sameSite("Lax")
            .path("/")
            .maxAge(COOKIE_LIFETIME)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  /**
   * Marks the browser of this request (registered by {@link #onFullSignIn}) as trusted for exactly
   * {@link #TRUST_PERIOD}: a new trust token goes into the trust cookie (Max-Age 30 days) and its
   * hash, with {@code trusted_until}, into the device row. Used after the 6-digit code and after a
   * recovery code. {@code method} ("totp" or "recovery_code") goes into the audit event.
   */
  public void trustThisDevice(
      long userId,
      HttpServletRequest request,
      HttpServletResponse response,
      String clientIp,
      String method) {
    Optional<Long> id = deviceId(userId, presentedToken(request));
    if (id.isEmpty()) {
      return;
    }
    String trust = SecureTokens.newToken();
    jdbc.sql("UPDATE user_device SET trusted_until = ?, trust_token_hash = ? WHERE id = ?")
        .params(ts(clock.instant().plus(TRUST_PERIOD)), SecureTokens.sha256Hex(trust), id.get())
        .update();
    ResponseCookie cookie =
        ResponseCookie.from(trustCookieName(props.secureCookies()), trust)
            .httpOnly(true)
            .secure(props.secureCookies())
            .sameSite("Lax")
            .path("/")
            .maxAge(TRUST_PERIOD)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    audit.record(
        MfaAuditEvent.TRUSTED_DEVICE_ADDED, userId, userId, clientIp, Map.of("method", method));
  }

  public List<TrustedDevice> listTrusted(long userId) {
    return jdbc.sql(
            "SELECT id, label, created_at, last_used_at, trusted_until FROM user_device WHERE"
                + " user_id = ? AND trusted_until > ? ORDER BY last_used_at DESC")
        .params(userId, ts(clock.instant()))
        .query(
            (rs, n) ->
                new TrustedDevice(
                    rs.getLong("id"),
                    rs.getString("label"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("last_used_at").toInstant(),
                    rs.getTimestamp("trusted_until").toInstant()))
        .list();
  }

  /**
   * Removes trust from one of the user's own devices; false when it is not theirs or not trusted.
   */
  public boolean untrust(long userId, long deviceId, String clientIp) {
    boolean changed =
        jdbc.sql(
                    "UPDATE user_device SET trusted_until = NULL, trust_token_hash = NULL WHERE id"
                        + " = ? AND user_id = ? AND trusted_until IS NOT NULL")
                .params(deviceId, userId)
                .update()
            == 1;
    if (changed) {
      audit.record(
          MfaAuditEvent.TRUSTED_DEVICE_REMOVED, userId, userId, clientIp, Map.of("reason", "one"));
    }
    return changed;
  }

  /** Revokes every trusted device of a user (security changes, or "remove all"). */
  public int untrustAll(long userId) {
    return jdbc.sql(
            "UPDATE user_device SET trusted_until = NULL, trust_token_hash = NULL WHERE user_id"
                + " = ? AND (trusted_until IS NOT NULL OR trust_token_hash IS NOT NULL)")
        .param(userId)
        .update();
  }

  /** Forgets devices not used for longer than the cookie lives (retention). */
  @Scheduled(fixedDelay = 6, timeUnit = TimeUnit.HOURS)
  void sweep() {
    jdbc.sql("DELETE FROM user_device WHERE last_used_at < ?")
        .param(ts(clock.instant().minus(COOKIE_LIFETIME)))
        .update();
  }

  private Optional<Long> deviceId(long userId, String token) {
    if (token == null) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT id FROM user_device WHERE user_id = ? AND token_hash = ?")
        .params(userId, SecureTokens.sha256Hex(token))
        .query(Long.class)
        .optional();
  }

  private static Timestamp ts(Instant instant) {
    return Timestamp.from(instant);
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
