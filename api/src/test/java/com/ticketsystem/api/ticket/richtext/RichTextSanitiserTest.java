package com.ticketsystem.api.ticket.richtext;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.security.SecurityGoLive;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Security test 3, save part: the composer allow list (spec 9, docs/35-security.md). */
@SecurityGoLive({3})
class RichTextSanitiserTest {

  private static final UUID MINE = UUID.fromString("11111111-2222-4333-8444-555555555555");
  private static final UUID OTHER = UUID.fromString("99999999-8888-4777-8666-555555555555");

  private static String clean(String html) {
    return RichTextSanitiser.sanitise(html, Set.of(MINE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<a href=\"javascript:alert(1)\">x</a>",
        "<a href=\"data:text/html;base64,PHNjcmlwdD4=\">x</a>",
        "<svg><script>alert(1)</script></svg>",
        "<svg onload=alert(1)></svg>",
        "<p style=\"background:url(https://evil.example/x)\">x</p>",
        "<style>p{background:url(https://evil.example/x)}</style>",
        "<img src=\"https://evil.example/track.png\">",
        "<img src=\"data:image/png;base64,iVBORw0KGgo=\">",
        "<iframe src=\"https://evil.example\"></iframe>",
        "<form action=\"https://evil.example\"><input name=a></form>",
        "<p onclick=\"alert(1)\" class=\"x\" id=\"y\">x</p>",
        "<math><mi xlink:href=\"javascript:alert(1)\">x</mi></math>",
        "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\">",
        "<base href=\"https://evil.example/\">",
        "<a href=\"vbscript:msgbox(1)\">x</a>",
        "<object data=\"https://evil.example/x.swf\"></object>"
      })
  void hostileMarkupIsStripped(String hostile) {
    String out = clean(hostile).toLowerCase();
    assertThat(out)
        .doesNotContain("<script")
        .doesNotContain("onerror")
        .doesNotContain("onload")
        .doesNotContain("onclick")
        .doesNotContain("javascript:")
        .doesNotContain("vbscript:")
        .doesNotContain("data:")
        .doesNotContain("<svg")
        .doesNotContain("style")
        .doesNotContain("url(")
        .doesNotContain("evil.example")
        .doesNotContain("<iframe")
        .doesNotContain("<form")
        .doesNotContain("<input")
        .doesNotContain("class=")
        .doesNotContain("id=")
        .doesNotContain("<meta")
        .doesNotContain("<base")
        .doesNotContain("<object");
  }

  @Test
  void allowedFormattingSurvives() {
    String html =
        "<h2>Title</h2><p><strong>b</strong> <em>i</em> <u>u</u> <s>s</s></p><ul><li>one</li></ul>"
            + "<ol><li>two</li></ol><blockquote>q</blockquote><pre><code>x</code></pre>"
            + "<table><thead><tr><th>h</th></tr></thead><tbody><tr><td>d</td></tr></tbody></table>";
    assertThat(clean(html)).isEqualTo(html);
  }

  @Test
  void linksKeepSafeSchemesWithRel() {
    String out =
        clean("<a href=\"https://example.test/a\">a</a><a href=\"mailto:x@example.test\">m</a>");
    assertThat(out).contains("href=\"https://example.test/a\"").contains("href=\"mailto:x");
    assertThat(out).contains("rel=\"noopener noreferrer nofollow\"");
  }

  @Test
  void onlyImagesOfThisCommentAreKept() {
    String out =
        clean(
            "<p><img src=\"attachment:"
                + MINE
                + "\" alt=\"mine\"><img src=\"attachment:"
                + OTHER
                + "\" alt=\"other\"></p>");
    assertThat(out).contains("attachment:" + MINE).doesNotContain(OTHER.toString());
    assertThat(RichTextSanitiser.inlineImageIds(out)).containsExactly(MINE);
  }

  @Test
  void anImagePastedIntoANoteCannotBeReferencedFromAReplyWithoutIt() {
    // The reply's own attachment set is empty, so the note's image is removed.
    String out = RichTextSanitiser.sanitise("<img src=\"attachment:" + MINE + "\">", Set.of());
    assertThat(out).doesNotContain("img").doesNotContain(MINE.toString());
  }

  @Test
  void nullAndBlankBecomeEmpty() {
    assertThat(RichTextSanitiser.sanitise(null, Set.of())).isEmpty();
    assertThat(RichTextSanitiser.sanitise("   ", Set.of())).isEmpty();
  }

  @Test
  void theAllowListMatchesTheDocumentedOne() {
    assertThat(String.join(" ", RichTextSanitiser.ALLOWED_ELEMENTS))
        .isEqualTo(
            "p br strong em u s ul ol li blockquote pre code h2 h3 a img table thead tbody tr th"
                + " td");
  }
}
