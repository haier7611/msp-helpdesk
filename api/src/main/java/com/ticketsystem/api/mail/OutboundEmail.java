package com.ticketsystem.api.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One email to put in the outbox. Validated on construction, so nothing that could inject headers
 * or carry an unparseable address ever reaches the table.
 *
 * @param reference the ticket's primary reference from the reference module (e.g. {@code NWD-001}),
 *     or null; it is put in square brackets at the start of the subject
 * @param fromName display name, or null for the support name (supporter mail uses {@code <Display
 *     name> (<support name>)}, built by the caller)
 * @param extraHeaders extra headers such as {@code In-Reply-To} and {@code References}
 * @param sensitive the body holds a live token: blanked after sending, never sent after {@code
 *     expiresAt}
 * @param parts inline images ({@code cid:}) and attached files, may be empty
 */
public record OutboundEmail(
    MailKind kind,
    List<String> to,
    List<String> cc,
    String fromName,
    String replyTo,
    String reference,
    String subject,
    String htmlBody,
    String textBody,
    Map<String, String> extraHeaders,
    Long ticketId,
    boolean sensitive,
    Instant expiresAt,
    List<MailPart> parts) {

  /** Most total bytes of extra parts on one email (the logo plus reply images and files). */
  public static final int MAX_PART_BYTES = 15 * 1024 * 1024;

  /** Headers set by the sender itself; callers cannot override them. */
  static final Set<String> RESERVED_HEADERS =
      Set.of(
          "from",
          "to",
          "cc",
          "bcc",
          "reply-to",
          "subject",
          "date",
          "message-id",
          "mime-version",
          "content-type",
          "content-transfer-encoding",
          "auto-submitted",
          "x-auto-response-suppress",
          "sender",
          "return-path");

  private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9-]{1,32}");
  static final int MAX_RECIPIENTS = 50;
  static final int MAX_SUBJECT = 900;

  public OutboundEmail {
    if (kind == null) {
      throw new IllegalArgumentException("kind is required");
    }
    to = addresses(to, "to");
    if (to.isEmpty()) {
      throw new IllegalArgumentException("at least one recipient is required");
    }
    cc = cc == null ? List.of() : addresses(cc, "cc");
    if (to.size() + cc.size() > MAX_RECIPIENTS) {
      throw new IllegalArgumentException("too many recipients");
    }
    if (fromName != null) {
      fromName = singleLine(fromName, "fromName").strip();
      if (fromName.isEmpty() || fromName.length() > 200) {
        throw new IllegalArgumentException("fromName must be 1-200 characters");
      }
    }
    if (replyTo != null) {
      replyTo = addresses(List.of(replyTo), "replyTo").get(0);
    }
    if (reference != null && !REFERENCE.matcher(reference).matches()) {
      throw new IllegalArgumentException("reference has an invalid format");
    }
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject is required");
    }
    // Line breaks in a subject are folded into spaces rather than refused (pasted titles).
    subject = subject.replaceAll("[\\r\\n]+", " ").strip();
    // outbox_message.subject is VARCHAR(998) and the stored form adds "[REF] (Internal) "
    // so cut long subjects instead of failing the insert.
    if (subject.length() > MAX_SUBJECT) {
      subject = subject.substring(0, MAX_SUBJECT);
    }
    if (htmlBody == null || htmlBody.isBlank() || textBody == null || textBody.isBlank()) {
      throw new IllegalArgumentException("both an HTML and a plain-text body are required");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    if (extraHeaders != null) {
      extraHeaders.forEach(
          (name, value) -> {
            if (name == null || !HEADER_NAME.matcher(name).matches()) {
              throw new IllegalArgumentException("invalid header name");
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
              throw new IllegalArgumentException("header " + name + " is set by the sender");
            }
            if (value == null || value.isBlank() || value.length() > 2000) {
              throw new IllegalArgumentException("header " + name + " needs a value");
            }
            headers.put(name, singleLine(value, "header " + name).strip());
          });
    }
    extraHeaders = Map.copyOf(headers);
    if (expiresAt != null && !sensitive) {
      throw new IllegalArgumentException("expiresAt is only for sensitive mail");
    }
    parts = parts == null ? List.of() : List.copyOf(parts);
    long total = 0;
    java.util.Set<String> cids = new java.util.HashSet<>();
    for (MailPart part : parts) {
      total += part.size();
      if (part.contentId() != null && !cids.add(part.contentId())) {
        throw new IllegalArgumentException("duplicate content id");
      }
    }
    if (total > MAX_PART_BYTES) {
      throw new IllegalArgumentException("parts are too large");
    }
    if (sensitive && !parts.isEmpty()) {
      throw new IllegalArgumentException("sensitive mail carries no parts");
    }
  }

  /** Convenience constructor without parts. */
  public OutboundEmail(
      MailKind kind,
      List<String> to,
      List<String> cc,
      String fromName,
      String replyTo,
      String reference,
      String subject,
      String htmlBody,
      String textBody,
      Map<String, String> extraHeaders,
      Long ticketId,
      boolean sensitive,
      Instant expiresAt) {
    this(
        kind,
        to,
        cc,
        fromName,
        replyTo,
        reference,
        subject,
        htmlBody,
        textBody,
        extraHeaders,
        ticketId,
        sensitive,
        expiresAt,
        List.of());
  }

  /** No bodies, subjects or addresses: they can hold tokens and personal data. */
  @Override
  public String toString() {
    return "OutboundEmail[kind="
        + kind
        + ", recipients="
        + (to.size() + cc.size())
        + ", reference="
        + reference
        + ", sensitive="
        + sensitive
        + ", parts="
        + parts.size()
        + "]";
  }

  /** Subject as sent:{@code [REF] subject}, with {@code (Internal)} for agent-only mail. */
  String fullSubject() {
    if (reference == null) {
      return kind == MailKind.INTERNAL ? "(Internal) " + subject : subject;
    }
    return "["
        + reference
        + "] "
        + (kind == MailKind.INTERNAL ? "(Internal) " : "")
        + stripLeadingReference(subject);
  }

  /** Avoids "[NWD-001] [NWD-001] ..." when a caller passes a subject that already has it. */
  private String stripLeadingReference(String s) {
    String tag = "[" + reference + "]";
    if (s.regionMatches(true, 0, tag, 0, tag.length())) {
      String rest = s.substring(tag.length()).strip();
      return rest.isEmpty() ? s : rest;
    }
    return s;
  }

  public static Builder builder(MailKind kind) {
    return new Builder(kind);
  }

  private static List<String> addresses(List<String> values, String field) {
    if (values == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " contains an empty address");
      }
      try {
        InternetAddress address = new InternetAddress(value.strip(), true);
        String bare = address.getAddress();
        if (bare.indexOf('@') < 1 || bare.contains(",")) {
          throw new IllegalArgumentException(field + " contains an invalid address");
        }
        out.add(bare);
      } catch (AddressException e) {
        throw new IllegalArgumentException(field + " contains an invalid address");
      }
    }
    return List.copyOf(out);
  }

  private static String singleLine(String value, String field) {
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException(field + " must not contain line breaks");
    }
    return value;
  }

  /** Fluent construction; {@link #build()} validates. */
  public static final class Builder {
    private final MailKind kind;
    private List<String> to = List.of();
    private List<String> cc = List.of();
    private String fromName;
    private String replyTo;
    private String reference;
    private String subject;
    private String htmlBody;
    private String textBody;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Long ticketId;
    private boolean sensitive;
    private Instant expiresAt;
    private final List<MailPart> parts = new ArrayList<>();

    private Builder(MailKind kind) {
      this.kind = kind;
    }

    public Builder to(String... addresses) {
      this.to = List.of(addresses);
      return this;
    }

    public Builder to(List<String> addresses) {
      this.to = List.copyOf(addresses);
      return this;
    }

    public Builder cc(List<String> addresses) {
      this.cc = List.copyOf(addresses);
      return this;
    }

    public Builder fromName(String fromName) {
      this.fromName = fromName;
      return this;
    }

    public Builder replyTo(String replyTo) {
      this.replyTo = replyTo;
      return this;
    }

    public Builder reference(String reference) {
      this.reference = reference;
      return this;
    }

    public Builder subject(String subject) {
      this.subject = subject;
      return this;
    }

    public Builder body(String html, String text) {
      this.htmlBody = html;
      this.textBody = text;
      return this;
    }

    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    public Builder ticketId(Long ticketId) {
      this.ticketId = ticketId;
      return this;
    }

    /** Marks the mail as carrying a live token that stops working at {@code expiresAt}. */
    public Builder sensitive(Instant expiresAt) {
      this.sensitive = true;
      this.expiresAt = expiresAt;
      return this;
    }

    public Builder part(MailPart part) {
      this.parts.add(part);
      return this;
    }

    public OutboundEmail build() {
      return new OutboundEmail(
          kind, to, cc, fromName, replyTo, reference, subject, htmlBody, textBody, headers,
          ticketId, sensitive, expiresAt, parts);
    }
  }
}
