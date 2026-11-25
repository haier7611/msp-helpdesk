package com.ticketsystem.api.ticket.richtext;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.owasp.html.AttributePolicy;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Sanitises rich text from the composer (public replies, internal notes, ticket descriptions) on
 * save, with the one allow list (security control C5; written in docs/35-security.md and shared
 * with the inbound mail sanitiser):
 *
 * <ul>
 *   <li>Elements: {@code p br strong em u s ul ol li blockquote pre code h2 h3 a img table thead
 *       tbody tr th td}; anything else is removed (script, style and similar with their content).
 *   <li>{@code a}: only {@code href} with an absolute {@code http:}, {@code https:} or {@code
 *       mailto:} URL, always with {@code rel="noopener noreferrer nofollow"}.
 *   <li>{@code img}: only {@code src="attachment:<id>"} where {@code <id>} is one of the
 *       attachments saved with the same comment, plus {@code alt}. Remote, {@code data:} and other
 *       images are removed, so an image pasted into an internal note cannot be shown in a public
 *       reply.
 *   <li>No {@code style}, {@code class}, {@code id}, event handlers or any other attribute.
 * </ul>
 *
 * The display side runs DOMPurify again and maps {@code attachment:<id>} to the download URL.
 */
public final class RichTextSanitiser {

  /** URL scheme of an inline image in stored HTML: {@code attachment:<public id>}. */
  public static final String ATTACHMENT_SCHEME = "attachment:";

  /** The allowed elements, as written in docs/35-security.md. */
  public static final List<String> ALLOWED_ELEMENTS =
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

  private static final Pattern SAFE_LINK = Pattern.compile("(?i)^(https?://|mailto:)[^\\s]+$");
  private static final Pattern ATTACHMENT_SRC =
      Pattern.compile(
          "(?i)^attachment:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");
  private static final Pattern ATTACHMENT_REF =
      Pattern.compile(
          "(?i)attachment:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private RichTextSanitiser() {}

  /**
   * @param html the composer's HTML (null gives "")
   * @param commentAttachments public ids of the attachments saved with this comment
   */
  public static String sanitise(String html, Set<UUID> commentAttachments) {
    if (html == null || html.isBlank()) {
      return "";
    }
    return policy(commentAttachments).sanitize(html);
  }

  /** Attachment ids referenced as inline images in already-sanitised HTML. */
  public static Set<UUID> inlineImageIds(String safeHtml) {
    return ATTACHMENT_REF
        .matcher(safeHtml)
        .results()
        .map(m -> UUID.fromString(m.group(1).toLowerCase(Locale.ROOT)))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static PolicyFactory policy(Set<UUID> commentAttachments) {
    AttributePolicy links =
        (element, attribute, value) -> {
          String v = value.strip();
          return SAFE_LINK.matcher(v).matches() ? v : null;
        };
    AttributePolicy images =
        (element, attribute, value) -> {
          var m = ATTACHMENT_SRC.matcher(value.strip());
          if (!m.matches()) {
            return null;
          }
          UUID id = UUID.fromString(m.group(1).toLowerCase(Locale.ROOT));
          return commentAttachments.contains(id) ? ATTACHMENT_SCHEME + id : null;
        };
    return new HtmlPolicyBuilder()
        .allowElements(ALLOWED_ELEMENTS.toArray(String[]::new))
        .allowUrlProtocols("http", "https", "mailto", "attachment")
        .allowAttributes("href")
        .matching(links)
        .onElements("a")
        .requireRelsOnLinks("noopener", "noreferrer", "nofollow")
        .allowAttributes("src")
        .matching(images)
        .onElements("img")
        .allowAttributes("alt")
        .onElements("img")
        .toFactory();
  }
}
