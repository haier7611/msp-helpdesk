package com.ticketsystem.api.mail;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw outbound mail settings from the server environment (application.yml maps the environment
 * variables; the bootstrap sender until an admin mailbox is configured). {@link MailSettings#from}
 * validates them at startup.
 *
 * @param supportName support name shown as the From name and in templates (Admin - Branding later)
 * @param fromAddress the sender address (MAIL_FROM_ADDRESS)
 * @param messageIdDomain right-hand side of generated Message-IDs; defaults to the From domain
 */
@ConfigurationProperties("app.mail")
public record MailProperties(
    String supportName, String fromAddress, String messageIdDomain, Smtp smtp, Outbox outbox) {

  public MailProperties {
    smtp = smtp == null ? new Smtp(null, null, null, null, null, null, null) : smtp;
    outbox = outbox == null ? new Outbox(null, null, null, null, null) : outbox;
  }

  /**
   * SMTP connection. An empty host means outbound mail is not configured: rows stay queued.
   *
   * @param security {@code starttls} (default), {@code tls} (implicit TLS, e.g. port 465) or {@code
   *     none} (local mail catcher only; refused together with credentials)
   */
  public record Smtp(
      String host,
      Integer port,
      String security,
      String username,
      String password,
      Duration connectTimeout,
      Duration readTimeout) {

    /** Never prints the password (records include every field in toString by default). */
    @Override
    public String toString() {
      return "Smtp[host="
          + host
          + ", port="
          + port
          + ", security="
          + security
          + ", username="
          + (username == null || username.isEmpty() ? "" : "(set)")
          + ", password="
          + (password == null || password.isEmpty() ? "" : "(set)")
          + "]";
    }
  }

  /**
   * Outbox worker tuning.
   *
   * @param autoStart start the worker (poll + after-commit nudge) with the application
   * @param pollInterval safety-net poll for rows the nudge missed
   * @param batchSize rows claimed per round
   * @param retryDelays wait after attempt 1, 2, ...; one more attempt than delays, then failed
   * @param staleAfter a row left in 'sending' longer than this (crash mid-send) is retried
   */
  public record Outbox(
      Boolean autoStart,
      Duration pollInterval,
      Integer batchSize,
      List<Duration> retryDelays,
      Duration staleAfter) {}
}
