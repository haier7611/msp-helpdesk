package com.ticketsystem.api.signature;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders a supporter's email signature from their fixed fields and the company template. The one
 * renderer for the My settings preview and for outbound mail (it must be called for every outbound
 * signature), so the preview is exactly what clients receive.
 *
 * <p>Layout: name large and bold in the brand colour; title bold; A:/E:/P: (and M:) lines with bold
 * brand-colour labels; the free line; a grey dashed divider; the logo. Simple tables and inline
 * styles only, so Outlook, Gmail and phone apps show the same thing. Empty fields drop their line
 * and label. Every value is HTML-escaped (C5) and inserted once, so a field holding {@code
 * <b>x</b>} or {@code {name}} appears literally. The admin's uploaded logo (at its stored display
 * size) replaces the placeholder, and an optional confidentiality line is set in small grey type
 * under the logo.
 */
@Component
public class SignatureRenderer {

  /** Content-ID of the inline logo part in outbound mail (attached under this id). */
  public static final String LOGO_CID = "signature-logo";

  /** Where the logo comes from: an inline MIME part in mail, the bytes inline in the preview. */
  public enum LogoMode {
    EMAIL,
    PREVIEW
  }

  /**
   * The signature as HTML and plain text. Every agent reply carries this full signature (there is
   * no one-line short signature on later replies).
   */
  public record Rendered(String fullHtml, String fullText) {}

  private static final String FONT = "font-family:Arial,Helvetica,sans-serif;";
  private static final String BODY = FONT + "font-size:14px;line-height:20px;color:#000000;";

  private final SignatureLogo logo;

  public SignatureRenderer(SignatureLogo logo) {
    this.logo = logo;
  }

  public Rendered render(SignatureProfile p, SignatureTemplate t, LogoMode mode) {
    String brand = t.brandColour();
    String email =
        t.emailMode() == SignatureTemplate.EmailMode.SUPPORT_MAILBOX && !t.supportEmail().isEmpty()
            ? t.supportEmail()
            : p.email();
    String logoSrc = mode == LogoMode.EMAIL ? "cid:" + LOGO_CID : logo.dataUri(t);
    int logoWidth = t.logo() == null ? SignatureLogo.DISPLAY_WIDTH : t.logo().width();
    int logoHeight = t.logo() == null ? SignatureLogo.DISPLAY_HEIGHT : t.logo().height();

    StringBuilder html = new StringBuilder();
    html.append(
        "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
            + " style=\"border-collapse:collapse;\">");
    row(
        html,
        FONT + "font-size:20px;line-height:26px;font-weight:bold;color:" + e(brand) + ";",
        e(p.displayName()));
    if (!p.jobTitle().isEmpty()) {
      row(
          html,
          FONT
              + "font-size:16px;line-height:22px;font-weight:bold;color:#000000;"
              + "padding-bottom:6px;",
          e(p.jobTitle()));
    }
    if (!t.address().isEmpty()) {
      row(html, BODY, label("A:", brand) + " " + e(t.address()));
    }
    if (!email.isEmpty()) {
      row(
          html,
          BODY,
          label("E:", brand)
              + " <a href=\"mailto:"
              + e(email)
              + "\" style=\"color:#000000;text-decoration:none;\">"
              + e(email)
              + "</a>");
    }
    List<String> phones = new ArrayList<>();
    if (!p.phone().isEmpty()) {
      phones.add(label("P:", brand) + " " + e(p.phone()));
    }
    if (!p.mobile().isEmpty()) {
      phones.add(label("M:", brand) + " " + e(p.mobile()));
    }
    if (!phones.isEmpty()) {
      row(html, BODY, String.join("&nbsp;&nbsp;", phones));
    }
    if (!p.freeLine().isEmpty()) {
      row(html, BODY, e(p.freeLine()));
    }
    html.append(
        "<tr><td style=\"padding:10px 0;\"><div style=\"border-top:1px dashed #A6A6A6;"
            + "height:1px;line-height:1px;font-size:1px;\">&nbsp;</div></td></tr>");
    html.append("<tr><td><img src=\"")
        .append(e(logoSrc))
        .append("\" alt=\"")
        .append(e(t.logoAlt()))
        .append("\" width=\"")
        .append(logoWidth)
        .append("\" height=\"")
        .append(logoHeight)
        .append("\" style=\"display:block;border:0;\"></td></tr>");
    if (!t.confidentialityLine().isEmpty()) {
      row(
          html,
          FONT + "font-size:11px;line-height:16px;color:#595959;padding-top:8px;",
          e(t.confidentialityLine()));
    }
    html.append("</table>");

    List<String> text = new ArrayList<>();
    text.add(p.displayName());
    addIf(text, p.jobTitle(), "");
    addIf(text, t.address(), "A: ");
    addIf(text, email, "E: ");
    addIf(text, p.phone(), "P: ");
    addIf(text, p.mobile(), "M: ");
    addIf(text, p.freeLine(), "");
    text.add("--");
    text.add(t.companyName());
    addIf(text, t.confidentialityLine(), "");

    return new Rendered(html.toString(), String.join("\n", text));
  }

  private static void row(StringBuilder html, String style, String content) {
    html.append("<tr><td style=\"")
        .append(style)
        .append("\">")
        .append(content)
        .append("</td></tr>");
  }

  private static String label(String label, String brand) {
    return "<strong style=\"color:" + e(brand) + ";\">" + label + "</strong>";
  }

  private static void addIf(List<String> lines, String value, String prefix) {
    if (!value.isEmpty()) {
      lines.add(prefix + value);
    }
  }

  /** HTML-escapes text and attribute values (quotes included). */
  static String e(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
  }
}
