package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailSettingsTest {

  private static final String SECRET = "s3cret-smtp-password-value";

  private static MailProperties props(
      String from, String host, Integer port, String security, String user, String password) {
    return new MailProperties(
        "Example MSP Support",
        from,
        null,
        new MailProperties.Smtp(host, port, security, user, password, null, null),
        null);
  }

  @Test
  void emptyHostMeansNotConfiguredAndStillStarts() {
    MailSettings s = MailSettings.from(props(null, "", null, null, null, null));

    assertThat(s.enabled()).isFalse();
    assertThat(s.messageIdDomain()).isEqualTo(MailSettings.FALLBACK_MESSAGE_ID_DOMAIN);
    assertThat(s.retryDelays()).isEqualTo(MailSettings.DEFAULT_RETRY_DELAYS);
    assertThat(s.pollInterval()).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void validStartTlsSettingsWithDefaultsDerivedFromTheFromAddress() {
    MailSettings s =
        MailSettings.from(
            props("support@helpdesk.example", "smtp.example.test", null, null, "u", SECRET));

    assertThat(s.enabled()).isTrue();
    assertThat(s.security()).isEqualTo(MailSettings.Security.STARTTLS);
    assertThat(s.port()).isEqualTo(587);
    assertThat(s.messageIdDomain()).isEqualTo("helpdesk.example");
  }

  @Test
  void implicitTlsDefaultsToPort465() {
    MailSettings s =
        MailSettings.from(props("a@helpdesk.example", "smtp.example.test", null, "TLS", "u", "p"));
    assertThat(s.port()).isEqualTo(465);
  }

  @Test
  void localCatcherWithoutTlsOrCredentialsIsAllowed() {
    MailSettings s =
        MailSettings.from(props("a@helpdesk.example", "mailpit", 1025, "none", null, null));
    assertThat(s.enabled()).isTrue();
    assertThat(s.security()).isEqualTo(MailSettings.Security.NONE);
  }

  @Test
  void plainTextToANonLocalHostIsRefusedEvenWithoutCredentials() {
    assertThatThrownBy(
            () ->
                MailSettings.from(
                    props("a@helpdesk.example", "smtp.example.test", 25, "none", null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP_SECURITY=none is only allowed to a local mail catcher");
    assertThatThrownBy(
            () ->
                MailSettings.from(props("a@helpdesk.example", "10.0.0.5", 25, "NONE", null, null)))
        .hasMessageContaining("SMTP_SECURITY=none");
  }

  @Test
  void plainTextToLocalHostsIsAllowed() {
    for (String host : java.util.List.of("localhost", "127.0.0.1", "::1", "LOCALHOST", "mailpit")) {
      MailSettings s =
          MailSettings.from(props("a@helpdesk.example", host, 1025, "none", null, null));
      assertThat(s.security()).as(host).isEqualTo(MailSettings.Security.NONE);
    }
    // TLS to any host is unaffected.
    assertThat(
            MailSettings.from(
                    props("a@helpdesk.example", "smtp.example.test", 25, "tls", null, null))
                .enabled())
        .isTrue();
  }

  @Test
  void credentialsOverPlainTextAreRefused() {
    assertThatThrownBy(
            () ->
                MailSettings.from(
                    props("a@helpdesk.example", "smtp.example.test", 25, "none", "u", SECRET)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP_SECURITY=none")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void everyProblemIsNamedWithoutValues() {
    assertThatThrownBy(
            () -> MailSettings.from(props(null, "smtp.example.test", 70000, "ssl", "u", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MAIL_FROM_ADDRESS")
        .hasMessageContaining("SMTP_SECURITY")
        .hasMessageContaining("SMTP_PORT")
        .hasMessageContaining("SMTP_USERNAME and SMTP_PASSWORD");
  }

  @Test
  void fromAddressMustBeABareAddress() {
    assertThatThrownBy(
            () -> MailSettings.from(props("Support <a@b.example>", "h", null, null, null, null)))
        .hasMessageContaining("MAIL_FROM_ADDRESS");
    assertThatThrownBy(
            () -> MailSettings.from(props("not-an-address", "h", null, null, null, null)))
        .hasMessageContaining("MAIL_FROM_ADDRESS");
  }

  @Test
  void hostWithLineBreaksOrSpacesIsRefused() {
    assertThatThrownBy(
            () -> MailSettings.from(props("a@b.example", "evil\r\nhost", null, null, null, null)))
        .hasMessageContaining("SMTP_HOST");
  }

  @Test
  void supportNameWithLineBreakIsRefused() {
    var p =
        new MailProperties(
            "Evil\r\nBcc: x@y.example",
            null,
            null,
            new MailProperties.Smtp(null, null, null, null, null, null, null),
            null);
    assertThatThrownBy(() -> MailSettings.from(p)).hasMessageContaining("MAIL_SUPPORT_NAME");
  }

  @Test
  void customRetryDelaysAreKept() {
    var p =
        new MailProperties(
            null,
            null,
            null,
            null,
            new MailProperties.Outbox(false, null, 5, List.of(Duration.ofSeconds(1)), null));
    MailSettings s = MailSettings.from(p);
    assertThat(s.autoStart()).isFalse();
    assertThat(s.batchSize()).isEqualTo(5);
    assertThat(s.retryDelays()).containsExactly(Duration.ofSeconds(1));
  }

  @Test
  void toStringNeverShowsThePassword() {
    MailSettings s =
        MailSettings.from(
            props("a@helpdesk.example", "smtp.example.test", null, null, "u", SECRET));
    MailProperties p = props("a@helpdesk.example", "smtp.example.test", null, null, "u", SECRET);

    assertThat(s.toString()).doesNotContain(SECRET);
    assertThat(p.toString()).doesNotContain(SECRET);
    assertThat(p.smtp().toString()).doesNotContain(SECRET);
  }
}
