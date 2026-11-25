package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboundEmailTest {

  private static OutboundEmail.Builder base(MailKind kind) {
    return OutboundEmail.builder(kind)
        .to("sam@client.example")
        .subject("Outlook keeps asking for a password")
        .body("<p>Hi</p>", "Hi");
  }

  @Test
  void subjectCarriesTheReferenceInBrackets() {
    assertThat(base(MailKind.CLIENT_FACING).reference("NWD-001").build().fullSubject())
        .isEqualTo("[NWD-001] Outlook keeps asking for a password");
  }

  @Test
  void internalMailIsMarkedAfterTheReference() {
    assertThat(base(MailKind.INTERNAL).reference("NWD-001").build().fullSubject())
        .isEqualTo("[NWD-001] (Internal) Outlook keeps asking for a password");
  }

  @Test
  void subjectWithoutReferenceIsLeftAlone() {
    assertThat(base(MailKind.SYSTEM).build().fullSubject())
        .isEqualTo("Outlook keeps asking for a password");
  }

  @Test
  void aReferenceAlreadyInTheSubjectIsNotRepeated() {
    OutboundEmail e =
        base(MailKind.CLIENT_FACING).reference("NWD-001").subject("[nwd-001] Printer").build();
    assertThat(e.fullSubject()).isEqualTo("[NWD-001] Printer");
  }

  @Test
  void lineBreaksInTheSubjectAreFolded() {
    OutboundEmail e = base(MailKind.SYSTEM).subject("Line one\r\nBcc: x@evil.example").build();
    assertThat(e.subject()).isEqualTo("Line one Bcc: x@evil.example").doesNotContain("\n");
  }

  @Test
  void headerInjectionIsRefused() {
    assertThatThrownBy(
            () ->
                base(MailKind.SYSTEM).header("In-Reply-To", "<a@b>\r\nBcc: x@evil.example").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> base(MailKind.SYSTEM).fromName("Alex\nBcc: x@evil.example").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> base(MailKind.SYSTEM).header("Bad Name", "v").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void senderHeadersCannotBeOverridden() {
    for (String reserved : new String[] {"Message-ID", "auto-submitted", "From", "Bcc"}) {
      assertThatThrownBy(() -> base(MailKind.SYSTEM).header(reserved, "x").build())
          .as(reserved)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void addressesAreValidatedAndStoredBare() {
    OutboundEmail e = base(MailKind.SYSTEM).to("Sam Lee <sam@client.example>").build();
    assertThat(e.to()).containsExactly("sam@client.example");

    assertThatThrownBy(() -> base(MailKind.SYSTEM).to("not an address").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> base(MailKind.SYSTEM).to("a@b.example, c@d.example").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bothBodiesAreRequired() {
    assertThatThrownBy(() -> base(MailKind.SYSTEM).body("<p>x</p>", " ").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void referenceFormatIsChecked() {
    assertThatThrownBy(() -> base(MailKind.SYSTEM).reference("NWD-001] [x").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expiryOnlyForSensitiveMail() {
    OutboundEmail e =
        base(MailKind.SYSTEM).sensitive(Instant.parse("2030-01-01T00:00:00Z")).build();
    assertThat(e.sensitive()).isTrue();
  }

  @Test
  void toStringLeavesOutBodiesSubjectAndAddresses() {
    OutboundEmail e =
        base(MailKind.SYSTEM)
            .body("<p>token-abc123</p>", "token-abc123")
            .reference("NWD-002")
            .build();
    assertThat(e.toString())
        .doesNotContain("token-abc123")
        .doesNotContain("sam@client.example")
        .doesNotContain("Outlook");
  }
}
