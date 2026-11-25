package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.mail.inbound.AuthenticationResultsParser;
import com.ticketsystem.api.mail.intake.MailboxService.MailboxRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * WP-19: an empty Trusted authserv-ids list on a Gmail or Microsoft 365 mailbox is saved with the
 * recommended value, so mail from those providers is not all sent to triage as "not authenticated".
 * Other servers and typed values are left alone.
 */
class MailboxTrustedDefaultsTest {

  private static MailboxRequest request(String imapHost, List<String> trusted) {
    return new MailboxRequest(
        "Support",
        "support@helpdesk.example",
        true,
        imapHost,
        993,
        "TLS",
        "INBOX",
        "PASSWORD",
        "support@helpdesk.example",
        null,
        null,
        null,
        null,
        null,
        trusted);
  }

  @ParameterizedTest
  @CsvSource({
    "imap.gmail.com, mx.google.com",
    "IMAP.Gmail.com, mx.google.com",
    "imap.googlemail.com, mx.google.com",
    "imap.gmail.com., mx.google.com",
    "outlook.office365.com, (none)",
  })
  void knownProviderGetsTheRecommendedValueWhenEmpty(String host, String expected) {
    assertThat(MailboxService.defaultTrustedAuthservId(host)).isEqualTo(expected);
    assertThat(MailboxService.validate(request(host, List.of())).trustedAuthservIds())
        .isEqualTo(expected);
    assertThat(MailboxService.validate(request(host, null)).trustedAuthservIds())
        .isEqualTo(expected);
    assertThat(MailboxService.validate(request(host, List.of(" ", ""))).trustedAuthservIds())
        .isEqualTo(expected);
  }

  @Test
  void otherServersStayEmpty() {
    assertThat(MailboxService.defaultTrustedAuthservId("imap.helpdesk.example")).isNull();
    assertThat(MailboxService.defaultTrustedAuthservId("mail.gmail.com.evil.example")).isNull();
    assertThat(MailboxService.defaultTrustedAuthservId(null)).isNull();
    assertThat(
            MailboxService.validate(request("imap.helpdesk.example", List.of()))
                .trustedAuthservIds())
        .isEmpty();
  }

  @Test
  void aTypedValueIsNeverReplaced() {
    assertThat(
            MailboxService.validate(request("imap.gmail.com", List.of("mx.helpdesk.example")))
                .trustedAuthservIds())
        .isEqualTo("mx.helpdesk.example");
  }

  /** The Microsoft 365 value really matches an Exchange Online header (no authserv-id). */
  @Test
  void microsoft365DefaultMatchesExchangeOnlineHeaders() {
    var parser =
        new AuthenticationResultsParser(
            Set.of(MailboxService.defaultTrustedAuthservId("outlook.office365.com")));
    var r =
        parser.parse(
            List.of(
                "spf=pass (sender IP is 192.0.2.10) smtp.mailfrom=acmedental.example;"
                    + " dkim=pass (signature was verified) header.d=acmedental.example;"
                    + " dmarc=pass action=none header.from=acmedental.example;compauth=pass"),
            "acmedental.example");
    assertThat(r.dmarcPass()).isTrue();
  }

  @Test
  void gmailDefaultMatchesGmailHeaders() {
    var parser =
        new AuthenticationResultsParser(
            Set.of(MailboxService.defaultTrustedAuthservId("imap.gmail.com")));
    var r =
        parser.parse(
            List.of(
                "mx.google.com; dkim=pass header.i=@acmedental.example;"
                    + " spf=pass smtp.mailfrom=user@acmedental.example;"
                    + " dmarc=pass (p=NONE sp=NONE dis=NONE) header.from=acmedental.example"),
            "acmedental.example");
    assertThat(r.dmarcPass()).isTrue();
  }
}
