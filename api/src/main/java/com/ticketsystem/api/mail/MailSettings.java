package com.ticketsystem.api.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validated outbound mail settings. Built once at startup by {@link #from}; any invalid value stops
 * the application with a message naming the environment variable (never its value).
 */
public record MailSettings(
    boolean enabled,
    String supportName,
    String fromAddress,
    String messageIdDomain,
    String host,
    int port,
    Security security,
    String username,
    String password,
    Duration connectTimeout,
    Duration readTimeout,
    boolean autoStart,
    Duration pollInterval,
    int batchSize,
    List<Duration> retryDelays,
    Duration staleAfter) {

  public enum Security {
    STARTTLS,
    TLS,
    NONE
  }

  static final String DEFAULT_SUPPORT_NAME = "Ticket System";
  static final String FALLBACK_MESSAGE_ID_DOMAIN = "ticket-system.invalid";
  static final List<Duration> DEFAULT_RETRY_DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(2),
          Duration.ofMinutes(4),
          Duration.ofMinutes(8),
          Duration.ofMinutes(16),
          Duration.ofMinutes(32));

  /**
   * Hosts {@code SMTP_SECURITY=none} may send to - this machine and the compose Mailpit catcher
   * (dev, demo, QA, CI). Anything else would carry client mail unencrypted on the network.
   */
  static final java.util.Set<String> PLAIN_TEXT_HOSTS =
      java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]", "mailpit");

  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]_-]{1,253}");
  private static final Pattern DOMAIN = Pattern.compile("[A-Za-z0-9.-]{1,253}");

  /** Never prints the password. */
  @Override
  public String toString() {
    return "MailSettings[enabled="
        + enabled
        + ", host="
        + host
        + ", port="
        + port
        + ", security="
        + security
        + ", username="
        + (username == null ? "" : "(set)")
        + ", password="
        + (password == null ? "" : "(set)")
        + "]";
  }

  public static MailSettings from(MailProperties p) {
    MailProperties.Smtp s = p.smtp();
    MailProperties.Outbox o = p.outbox();

    String supportName = blankToNull(p.supportName());
    supportName = supportName == null ? DEFAULT_SUPPORT_NAME : supportName;
    requireNoLineBreaks(supportName, "MAIL_SUPPORT_NAME");
    if (supportName.length() > 100) {
      throw invalid("MAIL_SUPPORT_NAME", "is longer than 100 characters");
    }

    String fromAddress = blankToNull(p.fromAddress());
    if (fromAddress != null) {
      fromAddress = validAddress(fromAddress, "MAIL_FROM_ADDRESS");
    }

    String messageIdDomain = blankToNull(p.messageIdDomain());
    if (messageIdDomain != null) {
      if (!DOMAIN.matcher(messageIdDomain).matches()) {
        throw invalid("MAIL_MESSAGE_ID_DOMAIN", "must be a plain domain name");
      }
    } else if (fromAddress != null) {
      messageIdDomain = fromAddress.substring(fromAddress.indexOf('@') + 1);
    } else {
      messageIdDomain = FALLBACK_MESSAGE_ID_DOMAIN;
    }

    boolean autoStart = o.autoStart() == null || o.autoStart();
    Duration pollInterval = positive(o.pollInterval(), Duration.ofSeconds(15), "poll-interval");
    int batchSize = o.batchSize() == null ? 20 : o.batchSize();
    if (batchSize < 1 || batchSize > 500) {
      throw invalid("app.mail.outbox.batch-size", "must be between 1 and 500");
    }
    List<Duration> retryDelays =
        o.retryDelays() == null || o.retryDelays().isEmpty()
            ? DEFAULT_RETRY_DELAYS
            : List.copyOf(o.retryDelays());
    for (Duration d : retryDelays) {
      if (d.isNegative()) {
        throw invalid("app.mail.outbox.retry-delays", "must not be negative");
      }
    }
    Duration staleAfter = positive(o.staleAfter(), Duration.ofMinutes(10), "stale-after");

    String host = blankToNull(s.host());
    if (host == null) {
      // Not configured: the app starts, emails are queued and wait for a sender.
      return new MailSettings(
          false,
          supportName,
          fromAddress,
          messageIdDomain,
          null,
          0,
          Security.STARTTLS,
          null,
          null,
          Duration.ofSeconds(10),
          Duration.ofSeconds(30),
          autoStart,
          pollInterval,
          batchSize,
          retryDelays,
          staleAfter);
    }

    List<String> problems = new ArrayList<>();
    if (!HOST.matcher(host).matches()) {
      problems.add("SMTP_HOST must be a host name or IP address");
    }
    if (fromAddress == null) {
      problems.add("MAIL_FROM_ADDRESS must be set when SMTP_HOST is set");
    }
    Security security;
    String sec = blankToNull(s.security());
    try {
      security =
          sec == null ? Security.STARTTLS : Security.valueOf(sec.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      security = Security.STARTTLS;
      problems.add("SMTP_SECURITY must be one of starttls, tls, none");
    }
    int port = s.port() == null ? defaultPort(security) : s.port();
    if (port < 1 || port > 65535) {
      problems.add("SMTP_PORT must be between 1 and 65535");
    }
    String username = blankToNull(s.username());
    String password = s.password() == null || s.password().isEmpty() ? null : s.password();
    if ((username == null) != (password == null)) {
      problems.add("SMTP_USERNAME and SMTP_PASSWORD must be set together");
    }
    if (username != null && security == Security.NONE) {
      problems.add(
          "SMTP_SECURITY=none cannot be used with SMTP_USERNAME/SMTP_PASSWORD"
              + " (credentials would cross the network unencrypted)");
    }
    if (security == Security.NONE && !PLAIN_TEXT_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
      problems.add(
          "SMTP_SECURITY=none is only allowed to a local mail catcher (localhost, 127.0.0.1 or"
              + " mailpit); use starttls or tls for SMTP_HOST");
    }
    if (password != null && (password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0)) {
      problems.add("SMTP_PASSWORD must not contain line breaks");
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Invalid outbound mail settings: " + String.join("; ", problems));
    }

    return new MailSettings(
        true,
        supportName,
        fromAddress,
        messageIdDomain,
        host,
        port,
        security,
        username,
        password,
        positive(s.connectTimeout(), Duration.ofSeconds(10), "connect-timeout"),
        positive(s.readTimeout(), Duration.ofSeconds(30), "read-timeout"),
        autoStart,
        pollInterval,
        batchSize,
        retryDelays,
        staleAfter);
  }

  static int defaultPort(Security security) {
    return switch (security) {
      case STARTTLS -> 587;
      case TLS -> 465;
      case NONE -> 25;
    };
  }

  static String validAddress(String value, String name) {
    try {
      InternetAddress address = new InternetAddress(value.trim(), true);
      String bare = address.getAddress();
      if (address.getPersonal() != null || bare.indexOf('@') < 1) {
        throw invalid(name, "must be a plain email address like support@example.com");
      }
      return bare;
    } catch (AddressException e) {
      throw invalid(name, "must be a plain email address like support@example.com");
    }
  }

  private static Duration positive(Duration value, Duration fallback, String name) {
    if (value == null) {
      return fallback;
    }
    if (value.isZero() || value.isNegative()) {
      throw invalid("app.mail." + name, "must be positive");
    }
    return value;
  }

  private static void requireNoLineBreaks(String value, String name) {
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw invalid(name, "must not contain line breaks");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static IllegalStateException invalid(String name, String problem) {
    return new IllegalStateException("Invalid outbound mail settings: " + name + " " + problem);
  }
}
