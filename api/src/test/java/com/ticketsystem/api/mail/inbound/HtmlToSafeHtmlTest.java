package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.security.SecurityGoLive;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec section 12 security test 3 (inbound part): the sanitiser corpus. */
@SecurityGoLive({3})
class HtmlToSafeHtmlTest {

  private static final List<String> ALLOWED_ELEMENTS =
      List.of(
          "p",
          "br",
          "strong",
          "em",
          "u",
          "s",
          "ul",
          "ol",
          "li",
          "blockquote",
          "pre",
          "code",
          "h2",
          "h3",
          "a",
          "img",
          "table",
          "thead",
          "tbody",
          "tr",
          "th",
          "td");
  private static final Pattern TAG = Pattern.compile("<\\s*/?\\s*([a-zA-Z0-9:-]+)([^>]*)>");
  private static final Pattern ATTR = Pattern.compile("([a-zA-Z-:]+)\\s*=\\s*\"([^\"]*)\"");

  /** Fails if anything outside the allow list, or any dangerous URL, survived. */
  static void assertNothingDangerous(String html) {
    String lower = html.toLowerCase(Locale.ROOT);
    assertThat(lower)
        .doesNotContain("<script")
        .doesNotContain("javascript:")
        .doesNotContain("vbscript:")
        .doesNotContain("data:")
        .doesNotContain("<svg")
        .doesNotContain("<math")
        .doesNotContain("<style")
        .doesNotContain("<iframe")
        .doesNotContain("<form")
        .doesNotContain("<input")
        .doesNotContain("<object")
        .doesNotContain("<embed")
        .doesNotContain("<meta")
        .doesNotContain("<base")
        .doesNotContain("<link")
        .doesNotContain("url(")
        .doesNotContainPattern("\\son[a-z]+\\s*=");
    Matcher tag = TAG.matcher(html);
    while (tag.find()) {
      String name = tag.group(1).toLowerCase(Locale.ROOT);
      assertThat(ALLOWED_ELEMENTS).as("element <%s> in %s", name, html).contains(name);
      Matcher attr = ATTR.matcher(tag.group(2));
      while (attr.find()) {
        String attrName = attr.group(1).toLowerCase(Locale.ROOT);
        String value = attr.group(2);
        switch (name + "@" + attrName) {
          case "a@href" -> assertThat(value).matches("(?i)(https?://|mailto:).+");
          case "a@rel" -> assertThat(value).isEqualTo("noopener noreferrer nofollow");
          case "img@src" -> assertThat(value).matches("attachment:\\d+");
          case "img@alt" -> {
            // plain text, escaped by the renderer
          }
          default -> throw new AssertionError("attribute " + attrName + " on " + name);
        }
      }
    }
  }

  private static String safe(String html) {
    return HtmlToSafeHtml.sanitise(html, List.of()).html();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>alert(1)</script>",
        "<SCRIPT SRC=https://evil.example/x.js></SCRIPT>",
        "<scr<script>ipt>alert(1)</scr</script>ipt>",
        "<img src=x onerror=alert(1)>",
        "<img src=\"x\" onerror=\"alert(1)\"/>",
        "<body onload=alert(1)>",
        "<p onclick=\"alert(1)\" onmouseover='alert(2)'>hi</p>",
        "<a href=\"javascript:alert(1)\">x</a>",
        "<a href=\"  javascript:alert(1)\">x</a>",
        "<a href=\"java&#x09;script:alert(1)\">x</a>",
        "<a href=\"&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;alert(1)\">x</a>",
        "<a href=\"JAVASCRIPT:alert(1)\">x</a>",
        "<a href=\"vbscript:msgbox(1)\">x</a>",
        "<a href=\"data:text/html,<script>alert(1)</script>\">x</a>",
        "<img src=\"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=\">",
        "<svg><script>alert(1)</script></svg>",
        "<svg onload=alert(1)>",
        "<svg><a xlink:href=\"javascript:alert(1)\">x</a></svg>",
        "<math><maction actiontype=statusline xlink:href=javascript:alert(1)>x</maction></math>",
        "<p style=\"background:url(https://evil.example/a.png)\">x</p>",
        "<div style=\"width:expression(alert(1))\">x</div>",
        "<style>@import url(https://evil.example/a.css);</style>",
        "<link rel=stylesheet href=https://evil.example/a.css>",
        "<iframe src=\"https://evil.example\"></iframe>",
        "<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>",
        "<object data=\"https://evil.example/x\"></object>",
        "<embed src=\"https://evil.example/x\">",
        "<form action=\"https://evil.example\"><input type=submit></form>",
        "<button formaction=javascript:alert(1)>x</button>",
        "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
        "<base href=\"javascript:alert(1)//\">",
        "<table background=\"javascript:alert(1)\"><tr><td>x</td></tr></table>",
        "<video><source onerror=\"alert(1)\"></video>",
        "<details open ontoggle=alert(1)>",
        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
        "<a href=\"https://ok.example\" style=\"position:fixed\" class=\"x\" id=\"y\">ok</a>",
        "<template><script>alert(1)</script></template>",
        "<!--<img src=x onerror=alert(1)>-->",
        "<![CDATA[<script>alert(1)</script>]]>",
        "<p>{{name}} <b>${user}</b> [[placeholder]]</p>",
        "<img src=\"https://evil.example/track.gif\">",
        "<img src=\"//evil.example/track.gif\">",
        "<img src=\"cid:unknown@x\">",
        "<a href=\"cid:image001@x\">cid link</a>",
        "<a href=\"/relative/path\">rel</a>",
      })
  void nothingDangerousSurvives(String input) {
    assertNothingDangerous(safe(input));
  }

  @Test
  void allowedMarkupIsKeptAndMailMarkupIsMapped() {
    String out =
        safe(
            "<div>one</div><h1>big</h1><h4>small</h4><b>bold</b><i>it</i><strike>x</strike>"
                + "<ul><li>a</li></ul><table><tbody><tr><td>c</td></tr></tbody></table>"
                + "<span style=\"color:red\">kept text</span><font face=Arial>font text</font>");
    assertThat(out)
        .contains("<p>one</p>")
        .contains("<h2>big</h2>")
        .contains("<h3>small</h3>")
        .contains("<strong>bold</strong>")
        .contains("<em>it</em>")
        .contains("<s>x</s>")
        .contains("<ul><li>a</li></ul>")
        .contains("<td>c</td>")
        .contains("kept text")
        .contains("font text")
        .doesNotContain("<span")
        .doesNotContain("<font");
  }

  @Test
  void linksGetRelAndOnlySafeSchemes() {
    assertThat(safe("<a href=\"https://acmemsp.example/a?b=1&amp;c=2\">x</a>"))
        .isEqualTo(
            "<a href=\"https://acmemsp.example/a?b&#61;1&amp;c&#61;2\""
                + " rel=\"noopener noreferrer nofollow\">x</a>");
    assertThat(safe("<a href=\"mailto:support@acmemsp.example\">mail</a>"))
        .contains("href=\"mailto:support&#64;acmemsp.example\"");
    assertThat(safe("<a href=\"/relative\">rel</a>")).isEqualTo("rel");
  }

  @Test
  void scriptAndStyleContentIsDroppedNotShownAsText() {
    assertThat(safe("<p>a</p><script>var secret=1</script><style>p{}</style>"))
        .isEqualTo("<p>a</p>");
  }

  @Test
  void remoteImagesAreReplacedAndListed() {
    var result =
        HtmlToSafeHtml.sanitise(
            "<img src=\"https://x.example/a.png\" alt=\"Logo\"><img src=\"//y.example/b.png\">",
            List.of());
    assertThat(result.html())
        .isEqualTo(
            HtmlToSafeHtml.REMOTE_IMAGE_PLACEHOLDER + HtmlToSafeHtml.REMOTE_IMAGE_PLACEHOLDER);
    assertThat(result.blockedRemoteImages())
        .containsExactly("https://x.example/a.png", "https://y.example/b.png");
  }

  @Test
  void cidImagesMatchAttachmentsByContentId() {
    var attachments =
        List.of(
            new InboundAttachment("a.pdf", "application/pdf", null, false, new byte[] {1}),
            new InboundAttachment("logo.png", "image/png", "Logo@Example", true, new byte[] {2}));
    assertThat(
            HtmlToSafeHtml.sanitise("<img src=\"cid:logo@example\" alt=\"<b>x</b>\">", attachments)
                .html())
        .isEqualTo("<img src=\"attachment:1\" alt=\"&lt;b&gt;x&lt;/b&gt;\" />");
    assertThat(HtmlToSafeHtml.sanitise("<img src=\"cid:other\">", attachments).html())
        .isEqualTo(HtmlToSafeHtml.IMAGE_REMOVED_PLACEHOLDER);
  }

  @Test
  void emptyAndNullInput() {
    assertThat(HtmlToSafeHtml.sanitise(null, List.of()).html()).isEmpty();
    assertThat(HtmlToSafeHtml.sanitise("   ", List.of()).html()).isEmpty();
  }

  @Test
  void deeplyNestedAndUnclosedMarkupIsHandled() {
    String nested = "<div>".repeat(5000) + "deep" + "</div>".repeat(10);
    String out = safe(nested);
    assertThat(out).contains("deep");
    assertNothingDangerous(out);
  }
}
