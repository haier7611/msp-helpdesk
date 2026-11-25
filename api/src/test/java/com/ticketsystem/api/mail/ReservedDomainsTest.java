package com.ticketsystem.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-034: the reserved test domains that never go through a support mailbox's SMTP. */
class ReservedDomainsTest {

  @Test
  void reservedTopLevelDomainsAndTheirSubdomainsAreReserved() {
    assertThat(ReservedDomains.isReserved("sam@client.example")).isTrue();
    assertThat(ReservedDomains.isReserved("sam@a.b.client.EXAMPLE")).isTrue();
    assertThat(ReservedDomains.isReserved("sam@example")).isTrue();
    assertThat(ReservedDomains.isReserved("lee@clinic.test")).isTrue();
    assertThat(ReservedDomains.isReserved("x@nowhere.invalid")).isTrue();
    assertThat(ReservedDomains.isReserved("y@app.localhost")).isTrue();
    assertThat(ReservedDomains.isReserved("z@trailing.test.")).isTrue();
  }

  @Test
  void ordinaryDomainsAreNot() {
    assertThat(ReservedDomains.isReserved("sam@client.example.com")).isFalse();
    assertThat(ReservedDomains.isReserved("sam@example.org")).isFalse();
    assertThat(ReservedDomains.isReserved("sam@mytest.com")).isFalse();
    assertThat(ReservedDomains.isReserved("sam@contest")).isFalse();
    assertThat(ReservedDomains.isReserved("sam@localhost.example.net")).isFalse();
    assertThat(ReservedDomains.isReserved(null)).isFalse();
  }
}
