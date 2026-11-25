package com.ticketsystem.api.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.signature.SignatureRenderer.LogoMode;
import com.ticketsystem.api.signature.SignatureRenderer.Rendered;
import com.ticketsystem.api.signature.SignatureTemplate.EmailMode;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Spec section 5 layout, escaping (C5), dropped lines and the E: line switch (ADR-015). */
class SignatureRendererTest {

  private static final SignatureTemplate TEMPLATE =
      new SignatureTemplate(
          "Example MSP",
          "1 Example Street, Example City",
          "#2B5797",
          EmailMode.SUPPORTER,
          "support@helpdesk.example",
          "Example MSP logo");

  private final SignatureLogo logo = new SignatureLogo();
  private final SignatureRenderer renderer = new SignatureRenderer(logo);

  private static SignatureProfile full() {
    return new SignatureProfile(
        "Alex Tran",
        "Service Desk Engineer",
        "+00 000 000 000",
        "+00 111 111 111",
        "Microsoft 365 specialist",
        "alex.tran@helpdesk.example");
  }

  @Test
  void fullSignatureFollowsTheOwnersLayout() {
    Rendered r = renderer.render(full(), TEMPLATE, LogoMode.EMAIL);
    String h = r.fullHtml();
    assertThat(h)
        .startsWith("<table role=\"presentation\"")
        .contains("font-size:20px")
        .contains("font-weight:bold;color:#2B5797;\">Alex Tran</td>")
        .contains("font-size:16px")
        .contains(">Service Desk Engineer</td>")
        .contains("<strong style=\"color:#2B5797;\">A:</strong> 1 Example Street, Example City")
        .contains(
            "<strong style=\"color:#2B5797;\">E:</strong> <a"
                + " href=\"mailto:alex.tran@helpdesk.example\"")
        .contains("<strong style=\"color:#2B5797;\">P:</strong> +00 000 000 000")
        .contains("<strong style=\"color:#2B5797;\">M:</strong> +00 111 111 111")
        .contains(">Microsoft 365 specialist</td>")
        .contains("border-top:1px dashed #A6A6A6")
        .contains("src=\"cid:signature-logo\" alt=\"Example MSP logo\" width=\"150\"")
        .doesNotContain("<script")
        .doesNotContain("class=");
    // Order: name, title, A, E, P, line, divider, logo.
    assertThat(h.indexOf("Alex Tran")).isLessThan(h.indexOf("Service Desk Engineer"));
    assertThat(h.indexOf("A:</strong>")).isLessThan(h.indexOf("E:</strong>"));
    assertThat(h.indexOf("E:</strong>")).isLessThan(h.indexOf("P:</strong>"));
    assertThat(h.indexOf("Microsoft 365")).isLessThan(h.indexOf("dashed"));
    assertThat(h.indexOf("dashed")).isLessThan(h.indexOf("<img"));

    assertThat(r.fullText())
        .isEqualTo(
            "Alex Tran\nService Desk Engineer\nA: 1 Example Street, Example City\n"
                + "E: alex.tran@helpdesk.example\nP: +00 000 000 000\nM: +00 111 111 111\n"
                + "Microsoft 365 specialist\n--\nExample MSP");
  }

  /** ADR-015 change 2026-11-20: one rendering only - the full signature, never a short one. */
  @Test
  void thereIsOnlyTheFullSignature() {
    assertThat(Rendered.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("fullHtml", "fullText");
  }

  @Test
  void emptyMobileFreeLineAndPhoneDropTheirLinesAndLabels() {
    SignatureProfile p =
        new SignatureProfile("Alex Tran", "", "", "", "", "alex.tran@helpdesk.example");
    Rendered r = renderer.render(p, TEMPLATE, LogoMode.EMAIL);
    assertThat(r.fullHtml()).doesNotContain("M:</strong>").doesNotContain("P:</strong>");
    assertThat(r.fullText()).doesNotContain("M:").doesNotContain("P:");
    assertThat(r.fullText())
        .isEqualTo(
            "Alex Tran\nA: 1 Example Street, Example City\nE: alex.tran@helpdesk.example\n--\n"
                + "Example MSP");
  }

  @Test
  void mobileWithoutPhoneShowsOnlyTheMobileLabel() {
    SignatureProfile p =
        new SignatureProfile("Alex Tran", "", "", "+00 111", "", "alex.tran@helpdesk.example");
    String h = renderer.render(p, TEMPLATE, LogoMode.EMAIL).fullHtml();
    assertThat(h).contains("M:</strong> +00 111").doesNotContain("P:</strong>");
  }

  @Test
  void fieldValuesAreEscapedAndPlaceholdersInValuesStayLiteral() {
    SignatureProfile p =
        new SignatureProfile(
            "<b>x</b>",
            "{name} & \"co\"",
            "",
            "",
            "<img src=x onerror=alert(1)>",
            "alex\"onmouseover=\"x@helpdesk.example");
    Rendered r = renderer.render(p, TEMPLATE, LogoMode.EMAIL);
    assertThat(r.fullHtml())
        .contains("&lt;b&gt;x&lt;/b&gt;")
        .doesNotContain("<b>x</b>")
        .contains("{name} &amp; &quot;co&quot;")
        .contains("&lt;img src=x onerror=alert(1)&gt;")
        .doesNotContain("<img src=x")
        .doesNotContain("\"onmouseover=\"")
        .contains("mailto:alex&quot;onmouseover=&quot;x@helpdesk.example");
    // Plain text keeps the characters as typed (it is never parsed as HTML).
    assertThat(r.fullText()).startsWith("<b>x</b>\n{name} & \"co\"");
  }

  @Test
  void templateValuesAreEscapedToo() {
    SignatureTemplate t =
        new SignatureTemplate(
            "A & B <Ltd>", "<script>x</script>", "#2B5797", EmailMode.SUPPORTER, "", "\"logo\"");
    Rendered r = renderer.render(full(), t, LogoMode.EMAIL);
    assertThat(r.fullHtml())
        .doesNotContain("<script>")
        .contains("&lt;script&gt;x&lt;/script&gt;")
        .contains("alt=\"&quot;logo&quot;\"");
  }

  @Test
  void eLineShowsTheSupportersOwnAddressByDefaultAndTheMailboxWhenSwitched() {
    assertThat(renderer.render(full(), TEMPLATE, LogoMode.EMAIL).fullText())
        .contains("E: alex.tran@helpdesk.example");
    SignatureTemplate mailbox =
        new SignatureTemplate(
            "Example MSP",
            "1 Example Street, Example City",
            "#2B5797",
            EmailMode.SUPPORT_MAILBOX,
            "support@helpdesk.example",
            "Example MSP logo");
    Rendered r = renderer.render(full(), mailbox, LogoMode.EMAIL);
    assertThat(r.fullText()).contains("E: support@helpdesk.example").doesNotContain("alex.tran@");
    assertThat(r.fullHtml()).contains("mailto:support@helpdesk.example");
  }

  @Test
  void previewInlinesTheLogoAsAPngDataUriAndOtherwiseMatchesTheEmail() throws Exception {
    Rendered email = renderer.render(full(), TEMPLATE, LogoMode.EMAIL);
    Rendered preview = renderer.render(full(), TEMPLATE, LogoMode.PREVIEW);
    assertThat(preview.fullHtml()).contains("src=\"data:image/png;base64,");
    String dataUri = logo.dataUri("#2B5797");
    assertThat(preview.fullHtml().replace(dataUri, "cid:signature-logo"))
        .isEqualTo(email.fullHtml());
    assertThat(preview.fullText()).isEqualTo(email.fullText());
    byte[] png = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
    var image = ImageIO.read(new ByteArrayInputStream(png));
    assertThat(image.getWidth()).isEqualTo(300);
    assertThat(image.getHeight()).isEqualTo(80);
    assertThat(png.length).isLessThan(30 * 1024);
  }

  @Test
  void fieldValidation() {
    assertThat(ProfileFields.displayName("  Alex Tran  ")).isEqualTo("Alex Tran");
    assertThat(ProfileFields.phone(" +00 (0) 123-456 ", "Phone")).isEqualTo("+00 (0) 123-456");
    assertThat(ProfileFields.jobTitle(null)).isEmpty();
    assertThatThrownBy(() -> ProfileFields.displayName(" ")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ProfileFields.displayName("x".repeat(81)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ProfileFields.displayName("Alex\r\nBcc: x@example.test"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("single line");
    assertThatThrownBy(() -> ProfileFields.phone("call me", "Phone"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ProfileFields.phone("1".repeat(31), "Phone"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ProfileFields.freeLine("x".repeat(101)))
        .isInstanceOf(ApiException.class);
    assertThat(ProfileFields.freeLine("x".repeat(100))).hasSize(100);
  }
}
