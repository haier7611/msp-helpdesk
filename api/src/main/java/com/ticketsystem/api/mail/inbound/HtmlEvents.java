package com.ticketsystem.api.mail.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.owasp.html.Handler;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamRenderer;

/**
 * The raw tag and text stream of an HTML document, as lexed by the OWASP sanitizer's own HTML lexer
 * (element and attribute names in lower case, entities decoded). Used to find quoted history and to
 * extract plain text; nothing here makes HTML safe - that is {@link HtmlToSafeHtml}.
 */
final class HtmlEvents {

  /** One lexer event. */
  sealed interface Event {}

  record Open(String name, List<String> attrs) implements Event {
    String attr(String attrName) {
      for (int i = 0; i + 1 < attrs.size(); i += 2) {
        if (attrs.get(i).equals(attrName)) {
          return attrs.get(i + 1);
        }
      }
      return null;
    }

    boolean hasClass(String className) {
      String c = attr("class");
      if (c == null) {
        return false;
      }
      for (String token : c.toLowerCase(Locale.ROOT).split("\\s+")) {
        if (token.equals(className)) {
          return true;
        }
      }
      return false;
    }
  }

  record Close(String name) implements Event {}

  record Text(String text) implements Event {}

  private HtmlEvents() {}

  static List<Event> lex(String html) {
    List<Event> events = new ArrayList<>();
    HtmlSanitizer.sanitize(
        html,
        new HtmlSanitizer.Policy() {
          @Override
          public void openDocument() {}

          @Override
          public void closeDocument() {}

          @Override
          public void openTag(String elementName, List<String> attrs) {
            List<String> normalised = new ArrayList<>(attrs.size());
            for (int i = 0; i < attrs.size(); i++) {
              String a = attrs.get(i);
              normalised.add(i % 2 == 0 ? a.toLowerCase(Locale.ROOT) : a);
            }
            events.add(new Open(elementName.toLowerCase(Locale.ROOT), List.copyOf(normalised)));
          }

          @Override
          public void closeTag(String elementName) {
            events.add(new Close(elementName.toLowerCase(Locale.ROOT)));
          }

          @Override
          public void text(String textChunk) {
            events.add(new Text(textChunk));
          }
        });
    return events;
  }

  /** Writes events back out as HTML (well-formed escaping; balancing is left to the sanitizer). */
  static String render(List<Event> events) {
    StringBuilder out = new StringBuilder();
    HtmlStreamRenderer renderer = HtmlStreamRenderer.create(out, Handler.DO_NOTHING);
    replay(events, renderer);
    return out.toString();
  }

  static void replay(List<Event> events, HtmlStreamEventReceiver receiver) {
    receiver.openDocument();
    for (Event e : events) {
      switch (e) {
        case Open o -> receiver.openTag(o.name(), new ArrayList<>(o.attrs()));
        case Close c -> receiver.closeTag(c.name());
        case Text t -> receiver.text(t.text());
      }
    }
    receiver.closeDocument();
  }
}
