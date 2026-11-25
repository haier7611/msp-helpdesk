package com.ticketsystem.api.mail.inbound;

import com.ticketsystem.api.mail.inbound.HtmlEvents.Close;
import com.ticketsystem.api.mail.inbound.HtmlEvents.Event;
import com.ticketsystem.api.mail.inbound.HtmlEvents.Open;
import com.ticketsystem.api.mail.inbound.HtmlEvents.Text;
import java.util.List;
import java.util.Set;

/**
 * Plain text from HTML (for HTML-only mail): text content with line breaks at block boundaries,
 * without the content of script, style, head and similar elements.
 */
public final class HtmlText {

  private static final Set<String> SKIPPED =
      Set.of("script", "style", "head", "title", "noscript", "template", "svg", "math", "xml");

  private HtmlText() {}

  public static String toPlainText(String html) {
    if (html == null) {
      return null;
    }
    return fromEvents(HtmlEvents.lex(html));
  }

  static String fromEvents(List<Event> events) {
    StringBuilder sb = new StringBuilder();
    int skipDepth = 0;
    for (Event e : events) {
      switch (e) {
        case Open o -> {
          if (SKIPPED.contains(o.name())) {
            skipDepth++;
          } else if (skipDepth == 0 && (o.name().equals("br") || QuoteStripper.isBlock(o.name()))) {
            sb.append('\n');
          }
        }
        case Close c -> {
          if (SKIPPED.contains(c.name())) {
            skipDepth = Math.max(0, skipDepth - 1);
          } else if (skipDepth == 0 && QuoteStripper.isBlock(c.name())) {
            sb.append('\n');
          }
        }
        case Text t -> {
          if (skipDepth == 0) {
            sb.append(t.text().replace(' ', ' '));
          }
        }
      }
    }
    // Collapse runs of spaces and of blank lines.
    String text = sb.toString().replaceAll("[ \\t\\x0B\\f\\r]+", " ");
    text = text.replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n");
    return text.strip();
  }
}
