package com.ticketsystem.api.mail.inbound;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.owasp.html.AttributePolicy;
import org.owasp.html.Handler;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamRenderer;

/**
 * Sanitises inbound email HTML with the OWASP Java HTML Sanitizer and the one rich-text allow list
 * (the list is written in docs/35-security.md and matches the comment sanitiser):
 *
 * <ul>
 *   <li>Elements: {@code p br strong em u s ul ol li blockquote pre code h2 h3 a img table thead
 *       tbody tr th td}. Common mail markup is mapped onto them ({@code div}/{@code center} to
 *       {@code p}, {@code h1} to {@code h2}, {@code h4-h6} to {@code h3}, {@code b} to {@code
 *       strong}, {@code i} to {@code em}, {@code strike}/{@code del} to {@code s}); any other
 *       element is removed and its text kept, except script, style and similar whose content goes
 *       too.
 *   <li>{@code a}: only {@code href} with an absolute {@code http:}, {@code https:} or {@code
 *       mailto:} URL, always with {@code rel="noopener noreferrer nofollow"}.
 *   <li>{@code img}: {@code src} and {@code alt} only. A {@code cid:} image that matches an image
 *       attachment of the same message (first match by Content-ID) becomes {@code
 *       src="attachment:<index>"} (index into {@link InboundMessage#attachments()}; the ticket code
 *       swaps it for the stored attachment's URL). A remote ({@code http:}, {@code https:}, {@code
 *       //}) image is replaced by the text "[Remote image blocked]" and its URL returned in {@link
 *       SafeHtml#blockedRemoteImages()} so the UI can offer click-to-load. Any other image ({@code
 *       data:}, unknown {@code cid:}, relative) is replaced by "[Image removed]".
 *   <li>No {@code style}, {@code class}, {@code id}, event handler or any other attribute; so no
 *       CSS {@code url()}, no {@code javascript:}/{@code data:}/{@code vbscript:} links, no SVG,
 *       MathML, forms, iframes, objects, meta refresh or base.
 * </ul>
 */
public final class HtmlToSafeHtml {

  /** URL scheme of an inline-image placeholder: {@code attachment:<index>}. */
  public static final String ATTACHMENT_SCHEME = "attachment:";

  static final String REMOTE_IMAGE_PLACEHOLDER = "[Remote image blocked]";
  static final String IMAGE_REMOVED_PLACEHOLDER = "[Image removed]";

  /** Most blocked remote image URLs returned; further images still get the placeholder. */
  static final int MAX_BLOCKED_IMAGES = 100;

  private static final Pattern SAFE_LINK = Pattern.compile("(?i)^(https?://|mailto:)[^\\s]+$");
  private static final Pattern REMOTE_IMAGE = Pattern.compile("(?i)^(https?:)?//[^\\s]+$");

  private static final AttributePolicy LINK_POLICY =
      (elementName, attributeName, value) -> {
        String v = value.strip();
        return SAFE_LINK.matcher(v).matches() ? v : null;
      };

  private HtmlToSafeHtml() {}

  /**
   * @param html raw inbound HTML (null gives an empty result)
   * @param attachments the message's attachments, to resolve {@code cid:} images
   */
  public static SafeHtml sanitise(String html, List<InboundAttachment> attachments) {
    if (html == null || html.isBlank()) {
      return new SafeHtml("", List.of());
    }
    StringBuilder out = new StringBuilder(Math.min(html.length(), 1 << 20));
    HtmlStreamRenderer renderer = HtmlStreamRenderer.create(out, Handler.DO_NOTHING);
    ImageRewriter images = new ImageRewriter(renderer, attachments);
    HtmlSanitizer.Policy policy = policyBuilder().build(images);
    HtmlSanitizer.sanitize(html, policy);
    return new SafeHtml(out.toString(), images.blocked);
  }

  /** The allow list. A fresh builder per call: {@link HtmlPolicyBuilder} is not thread safe. */
  private static HtmlPolicyBuilder policyBuilder() {
    return new HtmlPolicyBuilder()
        .allowElements(
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
            "td")
        .allowElements((name, attrs) -> "p", "div", "center")
        .allowElements((name, attrs) -> "h2", "h1")
        .allowElements((name, attrs) -> "h3", "h4", "h5", "h6")
        .allowElements((name, attrs) -> "strong", "b")
        .allowElements((name, attrs) -> "em", "i")
        .allowElements((name, attrs) -> "s", "strike", "del")
        // URL attributes are filtered by protocol first; cid: is needed for inline images and is
        // refused on links by LINK_POLICY.
        .allowUrlProtocols("http", "https", "mailto", "cid")
        .allowAttributes("href")
        .matching(LINK_POLICY)
        .onElements("a")
        .requireRelsOnLinks("noopener", "noreferrer", "nofollow")
        .allowAttributes("src", "alt")
        .onElements("img");
  }

  /**
   * Sits after the policy (so it only ever sees allowed elements and attributes) and turns each
   * image into an attachment placeholder or a text placeholder.
   */
  private static final class ImageRewriter implements HtmlStreamEventReceiver {

    private final HtmlStreamEventReceiver out;
    private final List<InboundAttachment> attachments;
    private final List<String> blocked = new ArrayList<>();

    ImageRewriter(HtmlStreamEventReceiver out, List<InboundAttachment> attachments) {
      this.out = out;
      this.attachments = attachments == null ? List.of() : attachments;
    }

    @Override
    public void openDocument() {
      out.openDocument();
    }

    @Override
    public void closeDocument() {
      out.closeDocument();
    }

    @Override
    public void openTag(String elementName, List<String> attrs) {
      if (!elementName.equals("img")) {
        out.openTag(elementName, attrs);
        return;
      }
      String src = null;
      String alt = null;
      for (int i = 0; i + 1 < attrs.size(); i += 2) {
        if (attrs.get(i).equals("src")) {
          src = attrs.get(i + 1).strip();
        } else if (attrs.get(i).equals("alt")) {
          alt = attrs.get(i + 1);
        }
      }
      if (src != null && src.regionMatches(true, 0, "cid:", 0, 4)) {
        int index = attachmentIndex(src.substring(4));
        if (index >= 0) {
          List<String> safe = new ArrayList<>(List.of("src", ATTACHMENT_SCHEME + index));
          if (alt != null) {
            safe.add("alt");
            safe.add(alt);
          }
          out.openTag("img", safe);
          return;
        }
      } else if (src != null && REMOTE_IMAGE.matcher(src).matches()) {
        if (blocked.size() < MAX_BLOCKED_IMAGES) {
          blocked.add(src.startsWith("//") ? "https:" + src : src);
        }
        out.text(REMOTE_IMAGE_PLACEHOLDER);
        return;
      }
      out.text(IMAGE_REMOVED_PLACEHOLDER);
    }

    @Override
    public void closeTag(String elementName) {
      if (!elementName.equals("img")) {
        out.closeTag(elementName);
      }
    }

    @Override
    public void text(String text) {
      out.text(text);
    }

    private int attachmentIndex(String rawCid) {
      String cid;
      try {
        cid = URLDecoder.decode(rawCid.replace("+", "%2B"), StandardCharsets.UTF_8).strip();
      } catch (IllegalArgumentException e) {
        cid = rawCid.strip();
      }
      if (cid.startsWith("<") && cid.endsWith(">")) {
        cid = cid.substring(1, cid.length() - 1);
      }
      for (int i = 0; i < attachments.size(); i++) {
        InboundAttachment a = attachments.get(i);
        String id = a.contentId();
        // Only image parts can back an <img>; the first matching Content-ID wins.
        if (id != null
            && a.contentType().startsWith("image/")
            && id.toLowerCase(Locale.ROOT).equals(cid.toLowerCase(Locale.ROOT))) {
          return i;
        }
      }
      return -1;
    }
  }

  /**
   * Sanitised HTML and the remote images that were blocked.
   *
   * @param html safe HTML fragment
   * @param blockedRemoteImages URLs of the blocked remote images, in order (http/https only), at
   *     most {@value HtmlToSafeHtml#MAX_BLOCKED_IMAGES}
   */
  public record SafeHtml(String html, List<String> blockedRemoteImages) {
    public SafeHtml {
      blockedRemoteImages = List.copyOf(blockedRemoteImages);
    }
  }
}
