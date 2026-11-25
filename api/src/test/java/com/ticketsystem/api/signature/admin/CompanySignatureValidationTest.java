package com.ticketsystem.api.signature.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ticketsystem.api.admin.PlainText;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.signature.SignatureLogo;
import com.ticketsystem.api.signature.SignatureRenderer;
import com.ticketsystem.api.signature.SignatureRenderer.LogoMode;
import com.ticketsystem.api.signature.SignatureTemplate;
import com.ticketsystem.api.signature.SignatureTemplate.EmailMode;
import org.junit.jupiter.api.Test;

/** Template validation, brand-colour contrast, plain-text rules and the confidentiality line. */
class CompanySignatureValidationTest {

  private static CompanySignatureService.TemplateInput input(
      String company, String colour, String mode, String supportEmail) {
    return new CompanySignatureService.TemplateInput(
        company,
        "1 Example Street, Example City",
        colour,
        mode,
        supportEmail,
        "Example MSP logo",
        "This email is confidential.");
  }

  @Test
  void aValidTemplateIsNormalised() {
    SignatureTemplate t =
        CompanySignatureService.validate(
            input("  Example MSP ", "#2b5797", "support_mailbox", " Support@Helpdesk.Example "),
            null);
    assertThat(t.companyName()).isEqualTo("Example MSP");
    assertThat(t.brandColour()).isEqualTo("#2B5797");
    assertThat(t.emailMode()).isEqualTo(EmailMode.SUPPORT_MAILBOX);
    assertThat(t.supportEmail()).isEqualTo("support@helpdesk.example");
  }

  @Test
  void badValuesAreRefusedWithAMessage() {
    assertThatThrownBy(
            () -> CompanySignatureService.validate(input("", "#2B5797", "SUPPORTER", ""), null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("company name");
    assertThatThrownBy(
            () ->
                CompanySignatureService.validate(
                    input("Example MSP", "blue", "SUPPORTER", ""), null))
        .hasMessageContaining("#RRGGBB");
    assertThatThrownBy(
            () ->
                CompanySignatureService.validate(
                    input("Example MSP", "#FFEE88", "SUPPORTER", ""), null))
        .hasMessageContaining("too light");
    assertThatThrownBy(
            () ->
                CompanySignatureService.validate(
                    input("Example MSP", "#2B5797", "SUPPORT_MAILBOX", ""), null))
        .hasMessageContaining("support mailbox address");
    assertThatThrownBy(
            () ->
                CompanySignatureService.validate(input("Example MSP", "#2B5797", "NOPE", ""), null))
        .hasMessageContaining("E: line");
    assertThatThrownBy(
            () ->
                CompanySignatureService.validate(
                    input("Example\r\nBcc: x@example.test", "#2B5797", "SUPPORTER", ""), null))
        .hasMessageContaining("single line");
  }

  @Test
  void contrastMatchesWcag() {
    assertThat(CompanySignatureService.contrastWithWhite("#000000")).isCloseTo(21.0, within(0.01));
    assertThat(CompanySignatureService.contrastWithWhite("#FFFFFF")).isCloseTo(1.0, within(0.01));
    assertThat(CompanySignatureService.contrastWithWhite("#2B5797")).isGreaterThan(4.5);
  }

  @Test
  void plainTextRefusesBidiOverrides() {
    assertThatThrownBy(() -> PlainText.required("abc‮txt", "a name", 10))
        .hasMessageContaining("single line");
    assertThat(PlainText.optional(null, "the address", 10)).isEmpty();
    assertThatThrownBy(() -> PlainText.optional("x".repeat(11), "the address", 10))
        .hasMessage("The address can be at most 10 characters.");
  }

  @Test
  void theConfidentialityLineIsEscapedUnderTheLogoAndInPlainText() {
    SignatureRenderer renderer = new SignatureRenderer(new SignatureLogo());
    SignatureTemplate t =
        CompanySignatureService.validate(
            new CompanySignatureService.TemplateInput(
                "Example MSP",
                "",
                "#2B5797",
                "SUPPORTER",
                "",
                "Example MSP logo",
                "<b>Confidential</b> & private"),
            null);
    var r = renderer.render(CompanySignatureService.SAMPLE, t, LogoMode.EMAIL);
    assertThat(r.fullHtml())
        .contains("&lt;b&gt;Confidential&lt;/b&gt; &amp; private")
        .doesNotContain("<b>Confidential");
    assertThat(r.fullHtml().indexOf("<img")).isLessThan(r.fullHtml().indexOf("Confidential"));
    assertThat(r.fullText()).endsWith("--\nExample MSP\n<b>Confidential</b> & private");
  }

  @Test
  void anUploadedLogoReplacesThePlaceholderAtItsDisplaySize() {
    SignatureRenderer renderer = new SignatureRenderer(new SignatureLogo());
    byte[] png = new SignatureLogo().png("#000000");
    SignatureTemplate t =
        new SignatureTemplate(
            "Example MSP",
            "",
            "#2B5797",
            EmailMode.SUPPORTER,
            "",
            "Example MSP logo",
            "",
            new SignatureTemplate.Logo(png, 120, 60));
    String email = renderer.render(CompanySignatureService.SAMPLE, t, LogoMode.EMAIL).fullHtml();
    assertThat(email)
        .contains("src=\"cid:signature-logo\"")
        .contains("width=\"120\" height=\"60\"");
    String preview =
        renderer.render(CompanySignatureService.SAMPLE, t, LogoMode.PREVIEW).fullHtml();
    assertThat(preview).contains(new SignatureLogo().dataUri(t));
  }
}
