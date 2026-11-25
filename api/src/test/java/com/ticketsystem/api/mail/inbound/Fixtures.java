package com.ticketsystem.api.mail.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/** Loads the synthetic fixture corpus in {@code src/test/resources/mail/inbound}. */
final class Fixtures {

  static final String SUPPORT = "support@acmemsp.example";
  static final String AUTHSERV = "mx.acmemsp.example";

  static final InboundMailEngine ENGINE =
      new InboundMailEngine(SUPPORT, Set.of(AUTHSERV), InboundLimits.DEFAULT);

  static final BlockListMatcher BLOCK_LIST =
      new BlockListMatcher(Set.of("scam@other.example"), Set.of("spam.example"));

  private Fixtures() {}

  static byte[] bytes(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/mail/inbound/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static InboundMessage message(String name) {
    ParseResult r = new InboundMessageParser(InboundLimits.DEFAULT).parse(bytes(name));
    if (r instanceof ParseResult.Parsed p) {
      return p.message();
    }
    throw new AssertionError(name + " did not parse: " + r);
  }

  static InboundAnalysis.Analysed analysed(String name) {
    InboundAnalysis a = ENGINE.analyse(bytes(name), BLOCK_LIST);
    if (a instanceof InboundAnalysis.Analysed ok) {
      return ok;
    }
    throw new AssertionError(name + " was not analysed: " + a);
  }
}
