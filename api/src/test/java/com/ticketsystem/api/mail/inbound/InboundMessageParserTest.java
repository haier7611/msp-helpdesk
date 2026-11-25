package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Huge, deep, broken and random input fails safely (WP-07a acceptance). */
class InboundMessageParserTest {

  private static final String HEAD =
      "From: Dana Molar <dana.molar@acmedental.example>\r\nTo: support@acmemsp.example\r\n"
          + "Subject: test\r\nMessage-ID: <t@acmedental.example>\r\nMIME-Version: 1.0\r\n";

  private static ParseResult parse(InboundLimits limits, String raw) {
    return new InboundMessageParser(limits).parse(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void emptyAndNull() {
    var p = new InboundMessageParser(InboundLimits.DEFAULT);
    assertThat(p.parse(null)).isEqualTo(new ParseResult.Unparseable(ParseResult.Reason.EMPTY, 0));
    assertThat(p.parse(new byte[0]))
        .isEqualTo(new ParseResult.Unparseable(ParseResult.Reason.EMPTY, 0));
  }

  @Test
  void overTheSizeCapIsNotParsed() {
    String raw = HEAD + "\r\n" + "x".repeat(2000);
    var r = parse(new InboundLimits(1000, 500, 20), raw);
    assertThat(r)
        .isEqualTo(new ParseResult.Unparseable(ParseResult.Reason.TOO_LARGE, raw.length()));
    // the engine passes it through
    var engine =
        new InboundMailEngine(
            Fixtures.SUPPORT, java.util.Set.of(), new InboundLimits(1000, 500, 20));
    assertThat(engine.analyse(raw.getBytes(StandardCharsets.UTF_8), null))
        .isEqualTo(new InboundAnalysis.Unparseable(ParseResult.Reason.TOO_LARGE, raw.length()));
  }

  @Test
  void defaultCapIs30Megabytes() {
    byte[] raw = new byte[30 * 1024 * 1024 + 1];
    var r = new InboundMessageParser(InboundLimits.DEFAULT).parse(raw);
    assertThat(r).isEqualTo(new ParseResult.Unparseable(ParseResult.Reason.TOO_LARGE, raw.length));
  }

  @Test
  void deepNestingIsCut() {
    StringBuilder sb = new StringBuilder(HEAD);
    int depth = 60;
    for (int i = 0; i < depth; i++) {
      sb.append("Content-Type: multipart/mixed; boundary=\"b").append(i).append("\"\r\n\r\n");
      sb.append("--b").append(i).append("\r\n");
    }
    sb.append("Content-Type: text/plain\r\n\r\ndeep\r\n");
    for (int i = depth - 1; i >= 0; i--) {
      sb.append("--b").append(i).append("--\r\n");
    }
    assertThat(parse(InboundLimits.DEFAULT, sb.toString()))
        .isInstanceOf(ParseResult.Unparseable.class)
        .extracting(r -> ((ParseResult.Unparseable) r).reason())
        .isEqualTo(ParseResult.Reason.TOO_DEEP);
  }

  @Test
  void tooManyPartsIsCut() {
    StringBuilder sb = new StringBuilder(HEAD);
    sb.append("Content-Type: multipart/mixed; boundary=\"m\"\r\n\r\n");
    for (int i = 0; i < 600; i++) {
      sb.append("--m\r\nContent-Type: text/plain\r\n\r\npart ").append(i).append("\r\n");
    }
    sb.append("--m--\r\n");
    assertThat(parse(InboundLimits.DEFAULT, sb.toString()))
        .extracting(r -> ((ParseResult.Unparseable) r).reason())
        .isEqualTo(ParseResult.Reason.TOO_MANY_PARTS);
  }

  @Test
  void missingFromAndBadDateAreLenient() {
    var r =
        parse(
            InboundLimits.DEFAULT,
            "Subject: no sender\r\nFrom: not an address\r\nDate: yesterday-ish\r\n\r\nbody");
    var m = ((ParseResult.Parsed) r).message();
    assertThat(m.from()).isNull();
    assertThat(m.date()).isNull();
    assertThat(m.messageId()).isNull();
    assertThat(m.subject()).isEqualTo("no sender");
    assertThat(m.textBody()).isEqualTo("body");
  }

  @Test
  void headerInjectionInSubjectIsFlattened() {
    var r =
        parse(
            InboundLimits.DEFAULT,
            HEAD.replace("Subject: test", "Subject: =?UTF-8?Q?a=0D=0ABcc:_x@evil.example?=")
                + "\r\nbody");
    assertThat(((ParseResult.Parsed) r).message().subject()).isEqualTo("a Bcc: x@evil.example");
  }

  @Test
  void attachmentFileNamesLosePathParts() {
    String raw =
        HEAD
            + "Content-Type: multipart/mixed; boundary=\"m\"\r\n\r\n--m\r\nContent-Type: text/plain\r\n\r\nhi\r\n"
            + "--m\r\nContent-Type: application/octet-stream\r\n"
            + "Content-Disposition: attachment; filename=\"..\\\\..\\\\evil.exe\"\r\n\r\nMZ\r\n"
            + "--m\r\nContent-Type: application/octet-stream\r\n"
            + "Content-Disposition: attachment; filename=\"=?UTF-8?B?Li4vLi4vZXRjL3Bhc3N3ZA==?=\"\r\n\r\nx\r\n--m--\r\n";
    var m = ((ParseResult.Parsed) parse(InboundLimits.DEFAULT, raw)).message();
    assertThat(m.attachments())
        .extracting(InboundAttachment::fileName)
        .containsExactly("evil.exe", "passwd");
  }

  private static String attachmentName(String partHeaders) {
    String raw =
        HEAD
            + "Content-Type: multipart/mixed; boundary=\"m\"\r\n\r\n--m\r\nContent-Type: text/plain\r\n\r\nhi\r\n"
            + "--m\r\n"
            + partHeaders
            + "\r\n\r\nx\r\n--m--\r\n";
    var m = ((ParseResult.Parsed) parse(InboundLimits.DEFAULT, raw)).message();
    assertThat(m.attachments()).hasSize(1);
    return m.attachments().get(0).fileName();
  }

  /** Fix 2026-11-20: non-ASCII names with spaces, every encoding decoded exactly once. */
  @Test
  void attachmentFileNamesAreDecodedExactlyOnce() {
    String want = "Übersicht Drucker Märkte Q1 bis Q2.xls";
    String enc = "%C3%9Cbersicht%20Drucker%20M%C3%A4rkte%20Q1%20bis%20Q2.xls";
    String octet = "Content-Type: application/vnd.ms-excel\r\n";
    // RFC 2231 / 5987 extended value
    assertThat(attachmentName(octet + "Content-Disposition: attachment; filename*=UTF-8''" + enc))
        .isEqualTo(want);
    // ... split into continuations
    assertThat(
            attachmentName(
                octet
                    + "Content-Disposition: attachment;\r\n filename*0*=UTF-8''"
                    + enc.substring(0, enc.indexOf("%20Drucker"))
                    + ";\r\n filename*1*="
                    + enc.substring(enc.indexOf("%20Drucker"))))
        .isEqualTo(want);
    // ... wins over the ASCII fallback, in either order
    assertThat(
            attachmentName(
                octet
                    + "Content-Disposition: attachment; filename*=utf-8''"
                    + enc
                    + "; filename=\"Ubersicht.xls\""))
        .isEqualTo(want);
    assertThat(
            attachmentName(
                octet
                    + "Content-Disposition: attachment; filename=\"Ubersicht.xls\"; filename*=UTF-8''"
                    + enc))
        .isEqualTo(want);
    // ... unquoted with raw UTF-8 (not allowed, sent anyway)
    assertThat(
            attachmentName(
                octet
                    + "Content-Disposition: attachment; filename*=UTF-8''Übersicht%20Drucker%20Märkte%20Q1%20bis%20Q2.xls"))
        .isEqualTo(want);
    // RFC 2047 encoded word in a plain parameter
    assertThat(
            attachmentName(
                octet
                    + "Content-Disposition: attachment; filename=\"=?UTF-8?B?"
                    + java.util.Base64.getEncoder()
                        .encodeToString(want.getBytes(StandardCharsets.UTF_8))
                    + "?=\""))
        .isEqualTo(want);
    // Raw UTF-8 in a plain parameter, spaces left percent-encoded (seen in a real client)
    assertThat(
            attachmentName(
                octet
                    + "Content-Disposition: attachment; filename=\"Übersicht%20Drucker%20Märkte%20Q1%20bis%20Q2.xls\""))
        .isEqualTo(want);
    // An RFC 5987 value put in a plain parameter
    assertThat(
            attachmentName(
                octet + "Content-Disposition: attachment; filename=\"UTF-8''" + enc + "\""))
        .isEqualTo(want);
    // Content-Type name= when there is no Content-Disposition filename
    assertThat(attachmentName("Content-Type: application/vnd.ms-excel; name*=UTF-8''" + enc))
        .isEqualTo(want);
    // Plain names are untouched
    assertThat(attachmentName(octet + "Content-Disposition: attachment; filename=\"" + want + "\""))
        .isEqualTo(want);
  }

  @Test
  void aLiteralPerCentSignStaysAndNothingIsDecodedTwice() {
    String octet = "Content-Type: application/pdf\r\n";
    assertThat(attachmentName(octet + "Content-Disposition: attachment; filename=\"50% off.pdf\""))
        .isEqualTo("50% off.pdf");
    assertThat(
            attachmentName(
                octet + "Content-Disposition: attachment; filename*=UTF-8''50%25%20off.pdf"))
        .isEqualTo("50% off.pdf");
    // %2520 is decoded once to %20 and stays there
    assertThat(
            attachmentName(octet + "Content-Disposition: attachment; filename*=UTF-8''a%2520b.pdf"))
        .isEqualTo("a%20b.pdf");
    assertThat(attachmentName(octet + "Content-Disposition: attachment; filename=\"a%2520b.pdf\""))
        .isEqualTo("a%20b.pdf");
    assertThat(
            attachmentName(
                octet + "Content-Disposition: attachment; filename=\"it's 'a' file.pdf\""))
        .isEqualTo("it's 'a' file.pdf");
    // An encoded path is still cut after decoding
    assertThat(
            attachmentName(
                octet + "Content-Disposition: attachment; filename*=UTF-8''..%2F..%2Fevil.pdf"))
        .isEqualTo("evil.pdf");
  }

  @Test
  void randomBytesNeverThrow() {
    Random random = new Random(42);
    var engine = Fixtures.ENGINE;
    for (int i = 0; i < 300; i++) {
      byte[] raw = new byte[random.nextInt(4000) + 1];
      random.nextBytes(raw);
      assertThat(engine.analyse(raw, null)).isNotNull();
    }
  }

  @Test
  void mutatedFixturesNeverThrow() {
    Random random = new Random(7);
    String[] names = {
      "01-outlook-desktop-reply.eml",
      "21-inline-image-related.eml",
      "23-hostile-html.eml",
      "31-bounce-dsn.eml"
    };
    for (String name : names) {
      byte[] original = Fixtures.bytes(name);
      for (int i = 0; i < 100; i++) {
        byte[] raw = original.clone();
        int flips = 1 + random.nextInt(20);
        for (int f = 0; f < flips; f++) {
          raw[random.nextInt(raw.length)] = (byte) random.nextInt(256);
        }
        if (random.nextBoolean()) {
          raw = java.util.Arrays.copyOf(raw, random.nextInt(raw.length) + 1);
        }
        assertThat(Fixtures.ENGINE.analyse(raw, Fixtures.BLOCK_LIST)).isNotNull();
      }
    }
  }

  @Test
  void limitsMustBePositive() {
    assertThatThrownBy(() -> new InboundLimits(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
