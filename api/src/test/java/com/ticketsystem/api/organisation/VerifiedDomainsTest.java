package com.ticketsystem.api.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Verified-domain rules (spec 11b, ADR-022): shape, normalisation, public domains refused. */
class VerifiedDomainsTest {

  private final VerifiedDomains domains = new VerifiedDomains();

  @ParameterizedTest
  @CsvSource({
    "acme-dental.example, acme-dental.example",
    "' Acme-Dental.Example ', acme-dental.example",
    "@acme.example, acme.example",
    "acme.example., acme.example",
    "mail.beta-clinic.co.example, mail.beta-clinic.co.example",
    "xn--bcher-kva.example, xn--bcher-kva.example"
  })
  void acceptsAndNormalisesHostNames(String raw, String expected) {
    assertThat(domains.check(raw)).isEqualTo(new VerifiedDomains.Check.Valid(expected));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "acme",
        "user@acme.example",
        "acme..example",
        "-acme.example",
        "acme-.example",
        "acme example.com",
        "http://acme.example",
        "acme.example/x",
        "*.acme.example"
      })
  void refusesWhatIsNotADomain(String raw) {
    assertThat(domains.check(raw))
        .isInstanceOfSatisfying(
            VerifiedDomains.Check.Invalid.class,
            i -> assertThat(i.code()).isEqualTo("invalid_domain"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "gmail.com",
        "GMAIL.COM",
        "outlook.com",
        "hotmail.com",
        "yahoo.com",
        "icloud.com",
        "@proton.me",
        "eu.gmail.com",
        "mail.yahoo.co.uk"
      })
  void refusesPublicMailDomainsAndTheirSubdomains(String raw) {
    assertThat(domains.check(raw))
        .isInstanceOfSatisfying(
            VerifiedDomains.Check.Invalid.class,
            i -> {
              assertThat(i.code()).isEqualTo("public_domain");
              assertThat(i.message()).contains("public email domain");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"notgmail.com", "gmail.com.acme.example", "outlook-support.example"})
  void doesNotRefuseLookAlikesThatAreNotPublic(String raw) {
    assertThat(domains.check(raw)).isInstanceOf(VerifiedDomains.Check.Valid.class);
  }
}
