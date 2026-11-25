package com.ticketsystem.api.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Format, parse and prefix rules of the reference module (spec 6, ADR-014, rule 10). */
class ReferenceFormatTest {

  @ParameterizedTest
  @CsvSource({"NWD, 1, NWD-001", "ACD, 42, ACD-042", "NWD, 999, NWD-999", "NWD, 1000, NWD-1000"})
  void formatsWithAtLeastThreeDigits(String prefix, long number, String expected) {
    TicketReference ref = ReferenceFormat.parse(prefix + "-" + number).orElseThrow();
    assertThat(ReferenceFormat.format(ref)).isEqualTo(expected);
    assertThat(ref.toString()).isEqualTo(expected);
  }

  @Test
  void parsesAnyCaseAndMissingZerosToTheSameReference() {
    assertThat(ReferenceFormat.parse("nwd-1")).isEqualTo(ReferenceFormat.parse("NWD-001"));
    assertThat(ReferenceFormat.parse("nwd-1").orElseThrow().toString()).isEqualTo("NWD-001");
    assertThat(ReferenceFormat.parse("NWD-1000").orElseThrow().toString()).isEqualTo("NWD-1000");
  }

  /**
   * The shared normalisation contract with WP-07a's ReferenceExtractor.normaliseToken (same vectors
   * as its ReferenceExtractorTest), so inbound matching and the reference module agree.
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
  void agreesWithTheInboundExtractorVectors(String token, String expected) {
    TicketReference ref = ReferenceFormat.parse(token).orElseThrow();
    assertThat(ref.prefix() + "/" + ref.number()).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "[NWD-1]",
        "NWD-",
        "NWD-0",
        "N-1",
        "1NS-1",
        "NWD-1a",
        "NWD 1",
        "",
        "ABCDEFG-1",
        "NWD-1234567890",
        "NWD--1",
        "NS_S-1"
      })
  void rejectsWhatIsNotAReference(String token) {
    assertThat(ReferenceFormat.parse(token)).isEmpty();
  }

  @Test
  void theValueObjectRefusesInvalidParts() {
    assertThatThrownBy(() -> new TicketReference("nwd", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TicketReference("NWD", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"acd, ACD", "' Ab1 ', AB1", "ZZ, ZZ", "ABCDEF, ABCDEF", "x9y8z7, X9Y8Z7"})
  void acceptsAndUpperCasesValidPrefixes(String raw, String expected) {
    assertThat(ReferenceFormat.checkOrganisationPrefix(raw))
        .isEqualTo(new ReferenceFormat.PrefixCheck.Valid(expected));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"1AB", "A", "ABCDEFG", "", "A-B", "AB C", "ÄBC"})
  void refusesBadPrefixShapes(String raw) {
    assertThat(ReferenceFormat.checkOrganisationPrefix(raw))
        .isInstanceOfSatisfying(
            ReferenceFormat.PrefixCheck.Invalid.class,
            i -> assertThat(i.message()).contains("2 to 6 letters or digits"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRI", "tri", " Tri "})
  void refusesTheTriagePrefixInAnyCase(String raw) {
    assertThat(ReferenceFormat.checkOrganisationPrefix(raw))
        .isInstanceOfSatisfying(
            ReferenceFormat.PrefixCheck.Invalid.class,
            i -> assertThat(i.message()).contains("reserved for the triage queue"));
  }
}
