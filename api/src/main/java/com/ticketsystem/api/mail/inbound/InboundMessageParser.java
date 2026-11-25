package com.ticketsystem.api.mail.inbound;

import com.ticketsystem.api.ticket.attachment.AttachmentProcessor;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimePartDataSource;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParameterList;
import jakarta.mail.internet.ParseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a raw RFC 5322 message ({@code .eml} bytes) into an {@link InboundMessage} with Jakarta
 * (Angus) Mail. Never throws: oversized, over-nested or broken messages come back as {@link
 * ParseResult.Unparseable}. Character sets and transfer encodings it does not know are decoded
 * leniently rather than failing the whole message.
 */
public final class InboundMessageParser {

  private static final Session SESSION = Session.getInstance(new Properties());
  private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]{1,995}>");
  private static final Set<String> KEY_HEADERS =
      Set.of("from", "to", "subject", "date", "message-id");

  /**
   * Header caps, applied before anything is decoded (RFC 2047 decoding is quadratic, so one huge
   * header could stall the poller). Longer values are truncated at a space and the message is
   * flagged {@link InboundMessage#headersTruncated}; it still becomes a ticket.
   */
  static final int MAX_SUBJECT_CHARS = 2 * 1024;

  static final int MAX_ADDRESS_HEADER_CHARS = 32 * 1024;
  static final int MAX_OTHER_HEADER_CHARS = 8 * 1024;
  static final int MAX_HEADER_BLOCK_CHARS = 256 * 1024;

  /** Part headers (Content-Type, Content-Disposition, Content-ID) longer than this are ignored. */
  static final int MAX_PART_HEADER_CHARS = 8 * 1024;

  private static final Set<String> ADDRESS_HEADERS =
      Set.of(
          "from",
          "to",
          "cc",
          "bcc",
          "reply-to",
          "sender",
          "references",
          "in-reply-to",
          "delivered-to",
          "x-original-to",
          "envelope-to",
          "x-delivered-to",
          "return-path");

  private final InboundLimits limits;

  public InboundMessageParser(InboundLimits limits) {
    this.limits = limits;
  }

  public ParseResult parse(byte[] raw) {
    if (raw == null || raw.length == 0) {
      return new ParseResult.Unparseable(ParseResult.Reason.EMPTY, 0);
    }
    if (raw.length > limits.maxMessageBytes()) {
      return new ParseResult.Unparseable(ParseResult.Reason.TOO_LARGE, raw.length);
    }
    try {
      return new ParseResult.Parsed(parseMessage(raw));
    } catch (LimitExceeded e) {
      return new ParseResult.Unparseable(e.reason, raw.length);
    } catch (NoHeaders e) {
      return new ParseResult.Unparseable(ParseResult.Reason.NO_HEADERS, raw.length);
    } catch (Exception | StackOverflowError e) {
      // Whatever the content, a bad message must never stop the poller.
      return new ParseResult.Unparseable(ParseResult.Reason.MALFORMED, raw.length);
    }
  }

  private InboundMessage parseMessage(byte[] raw) throws MessagingException, IOException {
    // Cut oversized header fields in the raw bytes first, so neither Angus Mail's header parser
    // nor RFC 2047 decoding ever sees megabytes of header.
    boolean[] truncated = {false};
    raw = precutHeaders(raw, truncated);
    MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
    Map<String, List<String>> headers = readHeaders(message, truncated);
    if (headers.keySet().stream().noneMatch(KEY_HEADERS::contains)) {
      throw new NoHeaders();
    }

    Body body = new Body();
    walk(message, 0, body);

    List<InboundAddress> fromList = InboundAddress.parseHeader(first(headers, "from"));
    String subject = InboundText.singleLine(InboundText.decodeWords(first(headers, "subject")));
    List<String> messageIds = messageIds(first(headers, "message-id"));
    return new InboundMessage(
        fromList.isEmpty() ? null : fromList.get(0),
        addresses(headers, "to"),
        addresses(headers, "cc"),
        addresses(headers, "reply-to"),
        subject,
        messageIds.isEmpty() ? null : messageIds.get(0),
        messageIds(first(headers, "in-reply-to")),
        messageIds(first(headers, "references")),
        date(first(headers, "date")),
        body.text == null ? null : body.text.toString(),
        body.html == null ? null : body.html.toString(),
        body.attachments,
        headers,
        truncated[0]);
  }

  private static Map<String, List<String>> readHeaders(MimeMessage message, boolean[] truncated)
      throws MessagingException {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    Enumeration<Header> all = message.getAllHeaders();
    long total = 0;
    while (all.hasMoreElements()) {
      Header h = all.nextElement();
      if (h.getName() == null || h.getValue() == null) {
        continue;
      }
      String name = h.getName().strip().toLowerCase(Locale.ROOT);
      if (name.isEmpty() || name.chars().anyMatch(c -> c <= ' ' || c > '~')) {
        continue;
      }
      int cap = capFor(name);
      String raw = h.getValue();
      if (raw.length() > cap) {
        // Folding adds at most a line break per line; cut generously before unfolding.
        raw =
            cutAtSpace(MimeUtility.unfold(raw.substring(0, Math.min(raw.length(), cap * 2))), cap);
        truncated[0] = true;
      } else {
        raw = MimeUtility.unfold(raw);
      }
      total += raw.length();
      if (total > MAX_HEADER_BLOCK_CHARS) {
        truncated[0] = true;
        break;
      }
      headers.computeIfAbsent(name, k -> new ArrayList<>()).add(raw.strip());
    }
    Map<String, List<String>> frozen = new LinkedHashMap<>();
    headers.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
    return frozen;
  }

  private static int capFor(String name) {
    if (name.equals("subject")) {
      return MAX_SUBJECT_CHARS;
    }
    return ADDRESS_HEADERS.contains(name) ? MAX_ADDRESS_HEADER_CHARS : MAX_OTHER_HEADER_CHARS;
  }

  /**
   * Linear scan of the top-level header block: a field longer than twice its character cap (room
   * for folding and multi-byte characters; {@link #readHeaders} then applies the exact cap) is cut,
   * and fields after about {@link #MAX_HEADER_BLOCK_CHARS} are dropped. Returns the input array
   * unchanged when nothing was cut.
   */
  static byte[] precutHeaders(byte[] raw, boolean[] truncated) {
    ByteArrayOutputStream out = null;
    int pos = 0;
    long kept = 0;
    boolean dropping = false;
    while (pos < raw.length) {
      // An empty line ends the header block.
      if (raw[pos] == '\n' || (raw[pos] == '\r' && pos + 1 < raw.length && raw[pos + 1] == '\n')) {
        break;
      }
      int start = pos;
      int end = lineEnd(raw, pos);
      while (end < raw.length && (raw[end] == ' ' || raw[end] == '\t')) {
        end = lineEnd(raw, end);
      }
      int colon = start;
      while (colon < end && colon - start < 100 && raw[colon] != ':') {
        colon++;
      }
      String name =
          new String(raw, start, colon - start, StandardCharsets.ISO_8859_1)
              .strip()
              .toLowerCase(Locale.ROOT);
      int limit = capFor(name) * 2;
      int length = end - start;
      boolean cut = length > limit;
      if (!dropping && kept + Math.min(length, limit) > MAX_HEADER_BLOCK_CHARS * 2L) {
        dropping = true;
      }
      if ((cut || dropping) && out == null) {
        out = new ByteArrayOutputStream(Math.min(raw.length, 1 << 20));
        out.write(raw, 0, start);
      }
      if (dropping) {
        truncated[0] = true;
      } else if (cut) {
        truncated[0] = true;
        out.write(raw, start, limit);
        out.write('\r');
        out.write('\n');
        kept += limit;
      } else {
        if (out != null) {
          out.write(raw, start, length);
        }
        kept += length;
      }
      pos = end;
    }
    if (out == null) {
      return raw;
    }
    out.write(raw, pos, raw.length - pos);
    return out.toByteArray();
  }

  /** Index just after the line break of the line starting at {@code pos}. */
  private static int lineEnd(byte[] raw, int pos) {
    int i = pos;
    while (i < raw.length && raw[i] != '\n') {
      i++;
    }
    return Math.min(i + 1, raw.length);
  }

  private static String first(Map<String, List<String>> headers, String name) {
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private static List<InboundAddress> addresses(Map<String, List<String>> headers, String name) {
    List<InboundAddress> result = new ArrayList<>();
    for (String value : headers.getOrDefault(name, List.of())) {
      result.addAll(InboundAddress.parseHeader(value));
    }
    return result;
  }

  private static List<String> messageIds(String value) {
    List<String> ids = new ArrayList<>();
    if (value == null) {
      return ids;
    }
    Matcher m = MESSAGE_ID.matcher(value);
    while (m.find() && ids.size() < 100) {
      ids.add(m.group());
    }
    return ids;
  }

  /** Cuts to at most {@code max} characters, at the last space if there is one. */
  private static String cutAtSpace(String value, int max) {
    if (value.length() <= max) {
      return value;
    }
    String cut = value.substring(0, max);
    int space = cut.lastIndexOf(' ');
    return space > max / 2 ? cut.substring(0, space) : cut;
  }

  private static Instant date(String value) {
    if (value == null) {
      return null;
    }
    try {
      java.util.Date d = new MailDateFormat().parse(value);
      return d == null ? null : d.toInstant();
    } catch (java.text.ParseException | RuntimeException e) {
      return null;
    }
  }

  /** True when the part header is absent or within {@link #MAX_PART_HEADER_CHARS}. */
  private static boolean partHeaderOk(Part part, String name) {
    try {
      String[] values = part.getHeader(name);
      if (values == null) {
        return true;
      }
      long length = 0;
      for (String v : values) {
        length += v == null ? 0 : v.length();
      }
      return length <= MAX_PART_HEADER_CHARS;
    } catch (Exception e) {
      return false;
    }
  }

  // ---- MIME walk ----

  private static final class Body {
    StringBuilder text;
    StringBuilder html;
    final List<InboundAttachment> attachments = new ArrayList<>();
    int parts;
  }

  private void walk(MimePart part, int depth, Body body) throws MessagingException, IOException {
    if (depth > limits.maxDepth()) {
      throw new LimitExceeded(ParseResult.Reason.TOO_DEEP);
    }
    if (++body.parts > limits.maxParts()) {
      throw new LimitExceeded(ParseResult.Reason.TOO_MANY_PARTS);
    }
    ContentType type = contentType(part);
    String baseType = type.getBaseType().toLowerCase(Locale.ROOT);
    if (baseType.startsWith("multipart/")) {
      MimeMultipart multipart = new MimeMultipart(new MimePartDataSource(part));
      int count = multipart.getCount();
      for (int i = 0; i < count; i++) {
        walk((MimePart) multipart.getBodyPart(i), depth + 1, body);
      }
      return;
    }
    String disposition = disposition(part);
    String fileName = fileName(part);
    boolean isBodyText =
        (baseType.equals("text/plain") || baseType.equals("text/html"))
            && !"attachment".equals(disposition)
            && fileName == null;
    if (isBodyText) {
      String text = decodeText(content(part), type.getParameter("charset"));
      if (baseType.equals("text/plain")) {
        body.text = append(body.text, text);
      } else {
        body.html = append(body.html, text);
      }
      return;
    }
    String contentId = contentId(part);
    boolean inline = "inline".equals(disposition) || (disposition == null && contentId != null);
    if (fileName == null && baseType.equals("message/rfc822")) {
      fileName = "attached-message.eml";
    }
    body.attachments.add(
        new InboundAttachment(fileName, baseType, contentId, inline, content(part)));
  }

  private static StringBuilder append(StringBuilder sb, String text) {
    if (sb == null) {
      return new StringBuilder(text);
    }
    return sb.append('\n').append(text);
  }

  private static ContentType contentType(Part part) {
    try {
      String value = partHeaderOk(part, "Content-Type") ? part.getContentType() : null;
      if (value != null) {
        return new ContentType(value);
      }
    } catch (Exception e) {
      // fall through: unknown type is treated as opaque bytes
    }
    try {
      return new ContentType("application/octet-stream");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String disposition(Part part) {
    if (!partHeaderOk(part, "Content-Disposition")) {
      return null;
    }
    try {
      String d = part.getDisposition();
      return d == null ? null : d.strip().toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The attachment's file name: Content-Disposition {@code filename}, else Content-Type {@code
   * name}. Each encoding is decoded exactly once, where it is found (fix 2026-11-20):
   *
   * <ul>
   *   <li>RFC 2231 / RFC 5987 extended values ({@code filename*=UTF-8''...}, also split as {@code
   *       filename*0*}) are decoded by Jakarta Mail and win over a plain {@code filename=} ASCII
   *       fallback sent alongside them. An unquoted extended value holding raw UTF-8 (not allowed,
   *       but sent by some mailers) is retried quoted rather than dropped.
   *   <li>A plain value is RFC 2047 decoded ({@code =?UTF-8?B?...?=}); then an RFC 5987 value put
   *       in a plain parameter, or a plainly percent-encoded name ({@code Übersicht%20März.xls}),
   *       is percent-decoded once ({@link AttachmentProcessor#decodePercentEncodedName}), the same
   *       pass an upload gets. {@code 50% off.pdf} stays as it is. An extended value is never
   *       percent-decoded a second time.
   * </ul>
   *
   * Path parts are dropped here; control and bidi characters later in {@link
   * AttachmentProcessor#cleanFilename}.
   */
  static String fileName(Part part) {
    if (!partHeaderOk(part, "Content-Disposition") || !partHeaderOk(part, "Content-Type")) {
      return null;
    }
    String name;
    try {
      name = parameter(header(part, "Content-Disposition"), "filename");
      if (name == null) {
        name = parameter(header(part, "Content-Type"), "name");
      }
    } catch (Exception e) {
      return null;
    }
    if (name == null) {
      return null;
    }
    name = InboundText.singleLine(name);
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    name = name.substring(slash + 1).strip();
    return name.isEmpty() ? null : name;
  }

  private static final Pattern RFC5987_VALUE =
      Pattern.compile("^(?i)(utf-8|iso-8859-1|us-ascii)'[a-z0-9-]{0,17}'(.*)$", Pattern.DOTALL);

  /** The first value of a part header, or null. */
  private static String header(Part part, String name) throws MessagingException {
    String[] values = part.getHeader(name);
    return values == null || values.length == 0 ? null : values[0];
  }

  /** One parameter of a structured header, decoded once (see {@link #fileName}). */
  private static String parameter(String header, String param) throws MessagingException {
    if (header == null) {
      return null;
    }
    String value = MimeUtility.unfold(header);
    int semi = value.indexOf(';');
    if (semi < 0) {
      return null;
    }
    String params = value.substring(semi);
    String quoted = Pattern.quote(param);
    boolean extended = Pattern.compile("(?i);\\s*" + quoted + "\\*").matcher(params).find();
    if (extended) {
      // The extended (Unicode) value wins over a plain ASCII fallback, whatever the order.
      params =
          params.replaceAll(
              "(?i);\\s*" + quoted + "\\s*=\\s*(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^;]*)", "");
    }
    ParameterList list;
    try {
      list = new ParameterList(params);
    } catch (ParseException e) {
      if (!extended) {
        throw e;
      }
      // An unquoted extended value with raw (unencoded) non-ASCII: quote it and try once more.
      list =
          new ParameterList(
              params.replaceAll(
                  "(?i)(;\\s*" + quoted + "\\*(?:\\d+\\*?)?\\s*=\\s*)([^\";][^;]*)", "$1\"$2\""));
    }
    String found = list.get(param);
    if (found == null || extended) {
      return found;
    }
    // RFC 2047 is not percent-encoding, so the name it carries still gets its one percent pass.
    String words = InboundText.decodeWords(rawUtf8(found));
    Matcher rfc5987 = RFC5987_VALUE.matcher(words);
    if (rfc5987.matches()) {
      String decoded = AttachmentProcessor.decodePercentEncodedName(rfc5987.group(2));
      return decoded.equals(rfc5987.group(2)) && decoded.contains("%") ? words : decoded;
    }
    return AttachmentProcessor.decodePercentEncodedName(words);
  }

  /**
   * Angus Mail reads header bytes as ISO-8859-1, so a plain parameter holding raw UTF-8 (RFC 6532)
   * arrives as mojibake ({@code Ä\u0090á»\u0091i}); turned back into the characters it was when the
   * bytes are valid UTF-8, left as it is otherwise. Extended values are decoded byte-wise by Angus
   * and never come through here.
   */
  private static String rawUtf8(String value) {
    boolean high = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c > 0xFF) {
        return value;
      }
      high |= c > 0x7F;
    }
    if (!high) {
      return value;
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(java.nio.ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1)))
          .toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      return value;
    }
  }

  private static String contentId(MimePart part) {
    if (!partHeaderOk(part, "Content-ID")) {
      return null;
    }
    try {
      String id = part.getContentID();
      if (id == null) {
        return null;
      }
      id = InboundText.singleLine(id);
      if (id.startsWith("<") && id.endsWith(">")) {
        id = id.substring(1, id.length() - 1).strip();
      }
      return id.isEmpty() ? null : id;
    } catch (Exception e) {
      return null;
    }
  }

  /** Transfer-decoded bytes; an unknown transfer encoding falls back to the raw bytes. */
  private static byte[] content(MimePart part) throws MessagingException, IOException {
    try (InputStream in = part.getInputStream()) {
      return in.readAllBytes();
    } catch (MessagingException | IOException | RuntimeException e) {
      if (part instanceof jakarta.mail.internet.MimeBodyPart bp) {
        try (InputStream in = bp.getRawInputStream()) {
          return in.readAllBytes();
        }
      }
      if (part instanceof MimeMessage mm) {
        try (InputStream in = mm.getRawInputStream()) {
          return in.readAllBytes();
        }
      }
      throw e;
    }
  }

  private static String decodeText(byte[] bytes, String charsetName) {
    Charset charset = StandardCharsets.UTF_8;
    if (charsetName != null) {
      try {
        charset = Charset.forName(MimeUtility.javaCharset(charsetName.strip()));
      } catch (RuntimeException e) {
        charset = StandardCharsets.UTF_8;
      }
    }
    return new String(bytes, charset);
  }

  private static final class LimitExceeded extends RuntimeException {
    final ParseResult.Reason reason;

    LimitExceeded(ParseResult.Reason reason) {
      super(reason.name(), null, false, false);
      this.reason = reason;
    }
  }

  private static final class NoHeaders extends RuntimeException {
    NoHeaders() {
      super("no headers", null, false, false);
    }
  }
}
