package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SystemEmailRendererTest {

  private final SystemEmailRenderer renderer = new SystemEmailRenderer("Example MSP Support");

  /** Synthetic values only. */
  static Map<String, String> synthetic(SystemEmail email) {
    Map<String, String> vars = new HashMap<>();
    for (String name : email.requiredVariables()) {
      vars.put(
          name,
          switch (name) {
            case "recipientName" -> "Alex Tran";
            case "organisationName" -> "Example Client Ltd";
            case "link" -> "https://support.example.test/accept?token=tok_SYNTHETIC_123";
            case "signInLink" -> "https://support.example.test/sign-in";
            case "validFor" -> "48 hours";
            case "device" -> "Firefox on Windows";
            case "headline" -> "SLA breached: first response";
            case "ticketSubject" -> "Printer offline in reception";
            case "summary" -> "2 open tickets assigned to you";
            case "items" -> "ACD-001 - P2 - Open - Printer offline in reception";
            case "endsAt" -> "4 December 2026, 09:30 UTC";
            default -> throw new IllegalStateException("no synthetic value for " + name);
          });
    }
    return vars;
  }

  @ParameterizedTest
  @EnumSource(SystemEmail.class)
  void everyTemplateRendersBothPartsWithNoUnresolvedPlaceholders(SystemEmail email) {
    Map<String, String> vars = synthetic(email);
    SystemEmailRenderer.Rendered r = renderer.render(email, vars);

    assertThat(r.subject()).contains("Example MSP Support");
    for (String part : new String[] {r.html(), r.text()}) {
      if (email.requiredVariables().contains("recipientName")) {
        assertThat(part).contains("Alex Tran");
      }
      assertThat(part)
          .contains("Example MSP Support")
          .doesNotContain("${")
          .doesNotContain("[(")
          .doesNotContain("[[")
          .doesNotContainPattern("\\sth:[a-z]+=")
          .doesNotContain("null");
      for (String value : vars.values()) {
        assertThat(part).as("%s variable value", email).contains(value);
      }
    }
    assertThat(r.html()).startsWith("<!DOCTYPE html>").contains("lang=\"en-GB\"");
    assertThat(r.text()).doesNotContain("<p").contains("This is an automatic message");
  }

  @Test
  void htmlEscapesVariablesButTheTextPartKeepsThemVerbatim() {
    Map<String, String> vars = synthetic(SystemEmail.CUSTOMER_INVITATION);
    vars.put("recipientName", "<script>alert('x')</script>");
    vars.put("organisationName", "A & B \"Ltd\"");

    SystemEmailRenderer.Rendered r = renderer.render(SystemEmail.CUSTOMER_INVITATION, vars);

    assertThat(r.html())
        .doesNotContain("<script>")
        .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
        .contains("A &amp; B &quot;Ltd&quot;");
    assertThat(r.text()).contains("<script>alert('x')</script>");
  }

  @Test
  void supportNameIsEscapedToo() {
    SystemEmailRenderer evil = new SystemEmailRenderer("<b>Evil</b>");
    SystemEmailRenderer.Rendered r =
        evil.render(SystemEmail.PASSWORD_RESET, synthetic(SystemEmail.PASSWORD_RESET));
    assertThat(r.html()).doesNotContain("<b>Evil</b>").contains("&lt;b&gt;Evil&lt;/b&gt;");
  }

  @Test
  void aQuoteInTheLinkCannotBreakOutOfTheAttribute() {
    Map<String, String> vars = synthetic(SystemEmail.PASSWORD_RESET);
    vars.put("link", "https://support.example.test/r?t=a\"onclick=\"alert(1)");
    String html = renderer.render(SystemEmail.PASSWORD_RESET, vars).html();
    assertThat(html).doesNotContain("\"onclick=\"").contains("&quot;onclick=&quot;");
  }

  @ParameterizedTest
  @EnumSource(SystemEmail.class)
  void aMissingVariableIsRefused(SystemEmail email) {
    for (String name : email.requiredVariables()) {
      Map<String, String> vars = synthetic(email);
      vars.remove(name);
      assertThatThrownBy(() -> renderer.render(email, vars))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(name);
    }
  }

  @Test
  void onlyHttpLinksAreAccepted() {
    Map<String, String> vars = synthetic(SystemEmail.CUSTOMER_MAGIC_LINK);
    vars.put("link", "javascript:alert(1)");
    assertThatThrownBy(() -> renderer.render(SystemEmail.CUSTOMER_MAGIC_LINK, vars))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("javascript");
  }

  @Test
  void tokenTemplatesAreSensitiveAndTheConfirmationIsNot() {
    assertThat(SystemEmail.SUPPORTER_INVITATION.sensitive()).isTrue();
    assertThat(SystemEmail.CUSTOMER_INVITATION.sensitive()).isTrue();
    assertThat(SystemEmail.PASSWORD_RESET.sensitive()).isTrue();
    assertThat(SystemEmail.CUSTOMER_MAGIC_LINK.sensitive()).isTrue();
    assertThat(SystemEmail.FIRST_ADMIN_SETUP.sensitive()).isFalse();
  }

  @Test
  void supporterInvitationMentionsNoCode() {
    String text =
        renderer
            .render(SystemEmail.SUPPORTER_INVITATION, synthetic(SystemEmail.SUPPORTER_INVITATION))
            .text();
    // ADR-046: the link plus a password is enough; no code to ask the administrator for.
    assertThat(text).doesNotContain("code").contains("Do not forward this email");
  }
}
