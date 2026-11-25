package com.ticketsystem.api.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceExtractorTest {

  private static List<String> refs(String subject) {
    return ReferenceExtractor.extract(subject).stream()
        .map(c -> c.prefix() + "/" + c.number())
        .toList();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "[NWD-001] Outlook keeps asking for a password | NWD/1",
        "RE: FW: [nwd-1] Outlook | NWD/1",
        "AW: [NWD-001] Outlook | NWD/1",
        "SV: Re: [Nwd-0001] Outlook | NWD/1",
        "Fwd: RE: [ACD-042] (Internal) Ticket assigned | ACD/42",
        "Printer offline [ACD-42] | ACD/42",
        "[[ACD-042]] double brackets | ACD/42",
        "[ ACD-042 ] spaces inside | ACD/42",
        "Re:[ACD-1000]no spaces around | ACD/1000",
        "[TRI-007] triage | TRI/7",
        "[AB-5] two-letter prefix | AB/5",
        "[ABCDEF-12] six-letter prefix | ABCDEF/12",
        "[A1B2-3] digits in prefix | A1B2/3",
      })
  void findsReferenceVariants(String subject, String expected) {
    assertThat(refs(subject)).containsExactly(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ACD-042 without brackets",
        "[ACD-] no number",
        "[ACD] no dash",
        "[-042] no prefix",
        "[A-1] one-letter prefix",
        "[ABCDEFG-1] seven-letter prefix",
        "[1AB-1] prefix starting with a digit",
        "[ACD-042x] trailing letter",
        "[ACD-04 2] space in number",
        "[ACD_042] underscore",
        "[ACD-000] number zero",
        "[ACD-1234567890] ten digits",
        "(ACD-042) round brackets",
        "{ACD-042} braces",
        "[ACD-٤٢] non-ASCII digits",
        "",
      })
  void ignoresLookAlikes(String subject) {
    assertThat(ReferenceExtractor.extract(subject)).isEmpty();
  }

  @Test
  void nullSubjectGivesNoCandidates() {
    assertThat(ReferenceExtractor.extract(null)).isEmpty();
  }

  @Test
  void returnsAllCandidatesInOrderWithoutDuplicates() {
    assertThat(refs("[ACD-042] and [NWD-1] and [acd-42] and [NWD-001]"))
        .containsExactly("ACD/42", "NWD/1");
  }

  @Test
  void capsTheNumberOfCandidates() {
    StringBuilder subject = new StringBuilder();
    for (int i = 1; i <= 50; i++) {
      subject.append("[ACD-").append(i).append("] ");
    }
    assertThat(ReferenceExtractor.extract(subject.toString()))
        .hasSize(ReferenceExtractor.MAX_CANDIDATES);
  }

  @Test
  void hugeSubjectIsFast() {
    String subject = "[".repeat(200_000) + "ACD-" + "0".repeat(200_000) + "1]";
    long start = System.nanoTime();
    ReferenceExtractor.extract(subject);
    assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
  }

  /**
   * The shared normalisation contract (WP-07a scope: "coordinate the normalisation function so
   * WP-04 can reuse it"): the reference module's ReferenceFormat.parse must give the same answers.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "nwd-1 | NWD/1",
        "NWD-001 | NWD/1",
        "Nwd-0001 | NWD/1",
        "acd-042 | ACD/42",
        "ACD-1000 | ACD/1000",
        "' tri-7 ' | TRI/7",
      })
  void normalisesTokens(String token, String expected) {
    assertThat(ReferenceExtractor.normaliseToken(token))
        .hasValueSatisfying(c -> assertThat(c.prefix() + "/" + c.number()).isEqualTo(expected));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[NWD-1]", "NWD-", "NWD-0", "N-1", "1NS-1", "NWD-1a", "NWD 1"})
  void rejectsBadTokens(String token) {
    assertThat(ReferenceExtractor.normaliseToken(token)).isEmpty();
  }
}
