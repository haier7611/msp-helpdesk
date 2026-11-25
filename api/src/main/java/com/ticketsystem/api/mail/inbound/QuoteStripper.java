package com.ticketsystem.api.mail.inbound;

import com.ticketsystem.api.mail.inbound.HtmlEvents.Close;
import com.ticketsystem.api.mail.inbound.HtmlEvents.Event;
import com.ticketsystem.api.mail.inbound.HtmlEvents.Open;
import com.ticketsystem.api.mail.inbound.HtmlEvents.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes quoted history from a reply so the ticket shows only the new text ("as far as possible");
 * the caller keeps the original body and the raw {@code .eml}.
 *
 * <p>Recognised: Gmail ({@code div.gmail_quote}), Apple Mail and Thunderbird ({@code blockquote
 * type=cite} with the "On ... wrote:" line before it, {@code moz-cite-prefix}), Outlook on the web
 * ({@code #divRplyFwdMsg}, {@code #appendonsend}), Outlook desktop (a "From: / Sent: / Subject:"
 * header block, with the rule or border above it), Yahoo ({@code yahoo_quoted}), "-----Original
 * Message-----" separators, "On ... wrote:" attribution lines (English, German, French, Spanish,
 * Italian, Dutch, Scandinavian) and {@code >} quoted lines. Everything from the first quote marker
 * to the end is cut; {@code >} lines are also removed where they are interleaved. If nothing would
 * be left, the input is returned unchanged.
 */
public final class QuoteStripper {

  private static final int MAX_LINE = 1000;

  /** Longest line checked as an "On ... wrote:" attribution. */
  static final int MAX_ATTRIBUTION_LINE = 500;

  /**
   * Bodies longer than this are returned unstripped: the stripper is linear, but a cap keeps the
   * worst case small.
   */
  static final int MAX_TEXT_CHARS = 1_000_000;

  static final int MAX_HTML_CHARS = 2_000_000;

  private static final Set<String> ATTRIBUTION_STARTERS =
      Set.of("on", "am", "le", "el", "il", "op", "den", "på", "d.");
  private static final List<String> ATTRIBUTION_VERBS =
      List.of(
          " wrote ",
          " schrieb ",
          " a écrit ",
          " escribió ",
          " ha scritto ",
          " schreef ",
          " skrev ",
          " kirjoitti ");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern ATTRIBUTION_START =
      Pattern.compile("(?iu)^(on|am|le|el|il|op|den|på)\\s.*");
  private static final Pattern ORIGINAL_MESSAGE =
      Pattern.compile(
          "(?iu)^-{2,}\\s*(original message|ursprüngliche nachricht|message d'origine"
              + "|oorspronkelijk bericht|mensaje original|messaggio originale"
              + "|ursprungligt meddelande|oprindelig meddelelse)\\s*-{2,}$");
  private static final Pattern HEADER_FROM =
      Pattern.compile("(?iu)^\\*?(from|von|de|från|fra|van|da)\\s?:\\*?(\\s.*)?$");
  private static final Pattern HEADER_SENT =
      Pattern.compile(
          "(?iu)^\\*?(sent|date|gesendet|datum|envoyé|skickat|sendt|verzonden|inviato|enviado)"
              + "\\s?:.*");
  private static final Pattern RULE = Pattern.compile("^[_\\-=]{8,}$");

  private static final Set<String> QUOTE_IDS =
      Set.of("divrplyfwdmsg", "appendonsend", "mail-editor-reference-message-container");
  private static final Set<String> QUOTE_CLASSES =
      Set.of("gmail_quote", "yahoo_quoted", "moz-cite-prefix", "protonmail_quote");

  private QuoteStripper() {}

  // ---- plain text ----

  /** The new text of a plain-text reply. */
  public static String stripText(String text) {
    if (text == null || text.isBlank() || text.length() > MAX_TEXT_CHARS) {
      return text;
    }
    String[] lines = text.split("\r?\n", -1);
    int cut = lines.length;
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].strip();
      if (t.length() > MAX_LINE) {
        continue;
      }
      if (isAttribution(t) || ORIGINAL_MESSAGE.matcher(t).matches()) {
        cut = i;
        break;
      }
      // "On Tue, 17 Nov 2026 at 09:12, Name <a@b>" wrapped before "wrote:".
      if (i + 1 < lines.length
          && ATTRIBUTION_START.matcher(t).matches()
          && lines[i + 1].strip().length() <= MAX_LINE
          && isAttribution(t + " " + lines[i + 1].strip())) {
        cut = i;
        break;
      }
      if (HEADER_FROM.matcher(t).matches() && sentFollows(lines, i)) {
        cut = i;
        int prev = previousNonBlank(lines, i);
        if (prev >= 0 && RULE.matcher(lines[prev].strip()).matches()) {
          cut = prev;
        }
        break;
      }
    }
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < cut; i++) {
      if (!lines[i].stripLeading().startsWith(">")) {
        kept.add(lines[i]);
      }
    }
    String result = String.join("\n", kept).strip();
    return result.isEmpty() ? text : result;
  }

  /**
   * "On &lt;anything&gt; wrote:" and its translations, including the German order "Am ... schrieb
   * Name:". A plain scan (no backtracking regex): starter word, at least one word, a verb, and a
   * trailing colon, on a line of at most {@value #MAX_ATTRIBUTION_LINE} characters.
   */
  static boolean isAttribution(String line) {
    if (line.length() > MAX_ATTRIBUTION_LINE) {
      return false;
    }
    String s = line.strip();
    if (!s.endsWith(":")) {
      return false;
    }
    s = WHITESPACE.matcher(s.substring(0, s.length() - 1).toLowerCase(Locale.ROOT)).replaceAll(" ");
    s = s.strip();
    int space = s.indexOf(' ');
    if (space < 0 || !ATTRIBUTION_STARTERS.contains(s.substring(0, space))) {
      return false;
    }
    String rest = " " + s.substring(space + 1) + " ";
    for (String verb : ATTRIBUTION_VERBS) {
      if (rest.indexOf(verb) > 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean sentFollows(String[] lines, int i) {
    for (int j = i + 1; j < lines.length && j <= i + 6; j++) {
      String t = lines[j].strip();
      if (t.length() <= MAX_LINE && HEADER_SENT.matcher(t).matches()) {
        return true;
      }
    }
    return false;
  }

  private static int previousNonBlank(String[] lines, int i) {
    for (int j = i - 1; j >= 0; j--) {
      if (!lines[j].isBlank()) {
        return j;
      }
    }
    return -1;
  }

  // ---- HTML ----

  /**
   * The new part of an HTML reply, still unsanitised: pass the result to {@link HtmlToSafeHtml}.
   */
  public static String stripHtml(String html) {
    if (html == null || html.isBlank() || html.length() > MAX_HTML_CHARS) {
      return html;
    }
    List<Event> events = HtmlEvents.lex(html);
    int cut = htmlCut(events);
    if (cut >= events.size()) {
      return html;
    }
    List<Event> kept = events.subList(0, cut);
    if (HtmlText.fromEvents(kept).isBlank()) {
      return html;
    }
    return HtmlEvents.render(kept);
  }

  static int htmlCut(List<Event> events) {
    for (int i = 0; i < events.size(); i++) {
      Event e = events.get(i);
      if (e instanceof Open o) {
        String id = o.attr("id");
        if (id != null && QUOTE_IDS.contains(id.strip().toLowerCase(java.util.Locale.ROOT))) {
          return withRuleBefore(events, i);
        }
        for (String c : QUOTE_CLASSES) {
          if (o.hasClass(c)) {
            return i;
          }
        }
        if (o.name().equals("blockquote")
            && "cite".equalsIgnoreCase(String.valueOf(o.attr("type")).strip())) {
          return attributionBefore(events, i);
        }
      } else if (e instanceof Text t) {
        String lead = t.text().strip();
        if (lead.isEmpty() || lead.length() > MAX_LINE) {
          continue;
        }
        String ahead = textAhead(events, i, 600);
        if (ORIGINAL_MESSAGE.matcher(firstLine(ahead)).matches()) {
          return withRuleBefore(events, startOfBlock(events, i));
        }
        if (HEADER_FROM.matcher(firstLine(ahead)).matches() && sentInText(ahead)) {
          return withRuleBefore(events, startOfBlock(events, i));
        }
      }
    }
    return events.size();
  }

  /** Apple Mail / Thunderbird: cut at the "On ... wrote:" block just before the blockquote. */
  private static int attributionBefore(List<Event> events, int blockquote) {
    StringBuilder text = new StringBuilder();
    int firstTextIndex = -1;
    for (int j = blockquote - 1; j >= 0 && blockquote - j < 40; j--) {
      Event e = events.get(j);
      if (e instanceof Text t) {
        text.insert(0, t.text());
        firstTextIndex = j;
        String candidate = text.toString().strip().replaceAll("\\s+", " ");
        if (candidate.length() > MAX_LINE) {
          break;
        }
        if (isAttribution(candidate)) {
          return startOfBlock(events, firstTextIndex);
        }
      } else if (e instanceof Open o && isBlock(o.name()) && !text.toString().isBlank()) {
        break;
      }
    }
    return blockquote;
  }

  /** Index of the first of the open tags directly in front of a text event. */
  private static int startOfBlock(List<Event> events, int textIndex) {
    int j = textIndex;
    while (j - 1 >= 0) {
      Event prev = events.get(j - 1);
      if (prev instanceof Open || (prev instanceof Text t && t.text().isBlank())) {
        j--;
      } else {
        break;
      }
    }
    return j;
  }

  /** Also cut an {@code <hr>} directly above the quote. */
  private static int withRuleBefore(List<Event> events, int index) {
    int j = index;
    while (j - 1 >= 0) {
      Event prev = events.get(j - 1);
      if (prev instanceof Text t && t.text().isBlank()) {
        j--;
      } else if (prev instanceof Open o && o.name().equals("hr")) {
        return j - 1;
      } else if (prev instanceof Close c && c.name().equals("hr")) {
        j--;
      } else {
        break;
      }
    }
    return index;
  }

  /** Text from event {@code i} on, with a line break at block boundaries, up to {@code max}. */
  private static String textAhead(List<Event> events, int i, int max) {
    StringBuilder sb = new StringBuilder();
    for (int j = i; j < events.size() && sb.length() < max; j++) {
      Event e = events.get(j);
      if (e instanceof Text t) {
        sb.append(t.text());
      } else if (e instanceof Open o && (isBlock(o.name()) || o.name().equals("br"))) {
        sb.append('\n');
      } else if (e instanceof Close c && isBlock(c.name())) {
        sb.append('\n');
      }
    }
    return sb.toString().strip();
  }

  private static boolean sentInText(String text) {
    String[] lines = text.split("\n");
    for (int i = 1; i < lines.length && i <= 8; i++) {
      if (HEADER_SENT.matcher(lines[i].strip()).matches()) {
        return true;
      }
    }
    return false;
  }

  private static String firstLine(String text) {
    int nl = text.indexOf('\n');
    String line = (nl < 0 ? text : text.substring(0, nl)).strip();
    return line.length() > MAX_LINE ? "" : line;
  }

  static boolean isBlock(String name) {
    return switch (name) {
      case "p",
          "div",
          "blockquote",
          "table",
          "tr",
          "td",
          "th",
          "li",
          "ul",
          "ol",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "pre",
          "hr",
          "body",
          "section",
          "article",
          "header",
          "footer" ->
          true;
      default -> false;
    };
  }
}
