package com.ticketsystem.api.auth;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth settings ({@code app.auth.*}). Holds no secrets: the setup token is read separately by
 * {@code SetupService} so it can never appear in a dump of these properties.
 *
 * @param publicBaseUrl the public https URL links in emails point to (no trailing slash)
 * @param secureCookies true everywhere except plain-http local development: cookies get the {@code
 *     __Host-} prefix and the Secure flag
 * @param sessionAbsoluteTimeout a session ends this long after sign-in even when active (24 h)
 * @param supporterInvitationValidity link lifetime for agent/admin invitations (48 h)
 * @param customerInvitationValidity link lifetime for customer invitations (7 days)
 * @param passwordResetValidity password reset link lifetime (15 min)
 * @param magicLinkValidity customer magic link lifetime (15 min)
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
    URI publicBaseUrl,
    boolean secureCookies,
    Duration sessionAbsoluteTimeout,
    Duration supporterInvitationValidity,
    Duration customerInvitationValidity,
    Duration passwordResetValidity,
    Duration magicLinkValidity) {

  public AuthProperties {
    if (publicBaseUrl == null
        || !("https".equals(publicBaseUrl.getScheme()) || "http".equals(publicBaseUrl.getScheme()))
        || publicBaseUrl.getHost() == null) {
      throw new IllegalArgumentException("app.auth.public-base-url must be an http(s) URL");
    }
    sessionAbsoluteTimeout = orDefault(sessionAbsoluteTimeout, Duration.ofHours(24));
    supporterInvitationValidity = orDefault(supporterInvitationValidity, Duration.ofHours(48));
    customerInvitationValidity = orDefault(customerInvitationValidity, Duration.ofDays(7));
    passwordResetValidity = orDefault(passwordResetValidity, Duration.ofMinutes(15));
    magicLinkValidity = orDefault(magicLinkValidity, Duration.ofMinutes(15));
  }

  private static Duration orDefault(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }

  /**
   * A link to a web page with the token in the URL fragment: browsers never send the fragment to a
   * server, so the token stays out of proxy and access logs and out of Referer headers.
   */
  public String link(String path, String token) {
    return base() + path + "#token=" + token;
  }

  public String page(String path) {
    return base() + path;
  }

  private String base() {
    String s = publicBaseUrl.toString();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /** "48 hours", "7 days", "15 minutes" - for email copy. */
  public static String describe(Duration d) {
    if (d.toHours() > 48 && d.toHours() % 24 == 0) {
      return plural(d.toDays(), "day");
    }
    if (d.toHours() >= 1 && d.toMinutes() % 60 == 0) {
      return plural(d.toHours(), "hour");
    }
    return plural(Math.max(1, d.toMinutes()), "minute");
  }

  private static String plural(long n, String unit) {
    return n + " " + unit + (n == 1 ? "" : "s");
  }
}
