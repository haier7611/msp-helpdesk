package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.mail.inbound.AuthenticationResults.Result;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthenticationResultsParserTest {

  private final AuthenticationResultsParser parser =
      new AuthenticationResultsParser(Set.of("mx.acmemsp.example"));

  private AuthenticationResults parse(String fromDomain, String... headers) {
    return parser.parse(List.of(headers), fromDomain);
  }

  @Test
  void passWithCommentsVersionAndQuotedValues() {
    var r =
        parse(
            "acmedental.example",
            "Mx.AcmeMsp.Example 1; spf=pass (sender ip is 192.0.2.1) smtp.mailfrom=acmedental.example;"
                + " dkim=pass (2048-bit key; secure) header.d=acmedental.example;"
                + " dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=\"acmedental.example\"");
    assertThat(r).isEqualTo(new AuthenticationResults(true, Result.PASS, Result.PASS, Result.PASS));
    assertThat(r.dmarcPass()).isTrue();
  }

  @Test
  void anyPassingDkimSignatureCounts() {
    var r =
        parse(
            "acmedental.example",
            "mx.acmemsp.example; dkim=fail header.d=other.example; dkim=pass header.d=acmedental.example;"
                + " dmarc=pass header.from=acmedental.example");
    assertThat(r.dkim()).isEqualTo(Result.PASS);
  }

  @Test
  void untrustedOrMissingIsNotAuthenticated() {
    assertThat(parse("acmedental.example")).isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
    assertThat(
            parse(
                "acmedental.example",
                "mx.attacker.example; dmarc=pass header.from=acmedental.example"))
        .isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
    // Look-alike id: prefix and suffix tricks.
    assertThat(
            parse(
                "acmedental.example",
                "mx.acmemsp.example.attacker.example; dmarc=pass header.from=acmedental.example",
                "evilmx.acmemsp.example; dmarc=pass header.from=acmedental.example"))
        .isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
  }

  @Test
  void noneResultAndUnknownValues() {
    assertThat(parse("acmedental.example", "mx.acmemsp.example; none"))
        .isEqualTo(new AuthenticationResults(true, Result.NONE, Result.NONE, Result.NONE));
    var r =
        parse(
            "acmedental.example",
            "mx.acmemsp.example; spf=softfail; dkim=temperror; dmarc=bestguesspass");
    assertThat(r.spf()).isEqualTo(Result.SOFTFAIL);
    assertThat(r.dkim()).isEqualTo(Result.TEMPERROR);
    assertThat(r.dmarc()).isEqualTo(Result.OTHER);
    assertThat(r.dmarcPass()).isFalse();
  }

  @Test
  void dmarcPassMustMatchTheFromDomain() {
    assertThat(
            parse("acmedental.example", "mx.acmemsp.example; dmarc=pass header.from=other.example")
                .dmarc())
        .isEqualTo(Result.FAIL);
    assertThat(
            parse(
                    "ACMEDENTAL.example",
                    "mx.acmemsp.example; dmarc=pass header.from=AcmeDental.EXAMPLE")
                .dmarcPass())
        .isTrue();
    // No From address at all: a pass cannot be checked, so it does not count.
    assertThat(parse(null, "mx.acmemsp.example; dmarc=pass header.from=acmedental.example").dmarc())
        .isEqualTo(Result.FAIL);
  }

  @Test
  void spacesAroundEqualsAndSemicolonsInComments() {
    var r =
        parse(
            "acmedental.example",
            "mx.acmemsp.example; dmarc = pass (comment; with; semicolons) header.from = acmedental.example");
    assertThat(r.dmarcPass()).isTrue();
  }

  @Test
  void aPassHiddenInACommentDoesNotCount() {
    var r =
        parse(
            "acmedental.example",
            "mx.acmemsp.example; dmarc=fail (dmarc=pass header.from=acmedental.example)"
                + " header.from=acmedental.example");
    assertThat(r.dmarc()).isEqualTo(Result.FAIL);
  }

  @Test
  void headerWithoutAuthservIdNeedsExplicitTrust() {
    String m365 =
        "spf=pass smtp.mailfrom=acmedental.example; dmarc=pass header.from=acmedental.example";
    assertThat(parse("acmedental.example", m365).authenticated()).isFalse();
    var trusting =
        new AuthenticationResultsParser(Set.of(AuthenticationResultsParser.NO_AUTHSERV_ID));
    assertThat(trusting.parse(List.of(m365), "acmedental.example").dmarcPass()).isTrue();
    // An id-less header does not satisfy a named trusted id, and vice versa.
    assertThat(
            trusting
                .parse(
                    List.of("mx.acmemsp.example; dmarc=pass header.from=acmedental.example"),
                    "acmedental.example")
                .authenticated())
        .isFalse();
  }

  /** AUD-43: the topmost header is the receiving server's own; with a trusted id it wins. */
  @Test
  void aGenuineTrustedTopmostHeaderWins() {
    var r =
        parse(
            "acmedental.example",
            "mx.acmemsp.example; spf=pass smtp.mailfrom=acmedental.example;"
                + " dkim=pass header.d=acmedental.example; dmarc=pass header.from=acmedental.example",
            "mx.attacker.example; dmarc=fail header.from=acmedental.example");
    assertThat(r).isEqualTo(new AuthenticationResults(true, Result.PASS, Result.PASS, Result.PASS));
  }

  /** AUD-43: a forged trusted header below the genuine trusted topmost one is ignored. */
  @Test
  void aForgedTrustedHeaderBelowTheTopmostIsIgnored() {
    var r =
        parse(
            "acmedental.example",
            "mx.acmemsp.example; spf=fail smtp.mailfrom=acmedental.example;"
                + " dmarc=fail header.from=acmedental.example",
            "mx.acmemsp.example; spf=pass smtp.mailfrom=acmedental.example;"
                + " dkim=pass header.d=acmedental.example; dmarc=pass header.from=acmedental.example");
    assertThat(r).isEqualTo(new AuthenticationResults(true, Result.FAIL, Result.FAIL, Result.NONE));
  }

  /**
   * AUD-43: only the topmost header is read. An untrusted topmost header (for example a list that
   * names an id the receiving server does not stamp) means not authenticated, even with a forged
   * trusted header further down.
   */
  @Test
  void anUntrustedTopmostHeaderMeansNotAuthenticatedWhateverLowerHeadersSay() {
    assertThat(
            parse(
                "acmedental.example",
                "mx.google.com; dmarc=fail header.from=acmedental.example",
                "mx.acmemsp.example; spf=pass smtp.mailfrom=acmedental.example;"
                    + " dmarc=pass header.from=acmedental.example"))
        .isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
    // An id-less topmost header while "(none)" is not trusted: the same.
    assertThat(
            parse(
                "acmedental.example",
                "spf=pass smtp.mailfrom=acmedental.example; dmarc=pass header.from=acmedental.example",
                "mx.acmemsp.example; dmarc=pass header.from=acmedental.example"))
        .isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
    // An empty or comment-only topmost header does not let a lower one through.
    assertThat(
            parse(
                "acmedental.example",
                "(nothing here)",
                "mx.acmemsp.example; dmarc=pass header.from=acmedental.example"))
        .isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
  }

  /** AUD-43: "(none)" (Microsoft 365) matches only an id-less topmost header. */
  @Test
  void noneMatchesOnlyAnIdLessTopmostHeader() {
    var m365 = new AuthenticationResultsParser(Set.of(AuthenticationResultsParser.NO_AUTHSERV_ID));
    // Id-less topmost: used; a forged id-less header below is ignored.
    var r =
        m365.parse(
            List.of(
                "spf=fail smtp.mailfrom=acmedental.example; dmarc=fail header.from=acmedental.example",
                "spf=pass smtp.mailfrom=acmedental.example; dmarc=pass header.from=acmedental.example"),
            "acmedental.example");
    assertThat(r.authenticated()).isTrue();
    assertThat(r.dmarc()).isEqualTo(Result.FAIL);
    // A named topmost header with a forged id-less one below: not authenticated.
    assertThat(
            m365.parse(
                List.of(
                    "mx.attacker.example; dmarc=fail header.from=acmedental.example",
                    "spf=pass smtp.mailfrom=acmedental.example;"
                        + " dmarc=pass header.from=acmedental.example"),
                "acmedental.example"))
        .isEqualTo(AuthenticationResults.NOT_AUTHENTICATED);
  }

  @Test
  void garbageDoesNotThrow() {
    for (String g :
        new String[] {"", ";;;", "(((", "\"unterminated", "mx.acmemsp.example; =;=pass"}) {
      parse("acmedental.example", g);
    }
    assertThat(parse("acmedental.example", "mx.acmemsp.example; =;=pass").dmarcPass()).isFalse();
  }
}
