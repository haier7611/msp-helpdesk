package com.ticketsystem.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CSV cells of the audit export: attacker-chosen strings stay inert (AUD-07 / PR #7 Low). */
class AuditLogCsvTest {

  @Test
  void formulasAreNeutralisedEvenAfterLeadingSpaces() {
    assertThat(AuditLogService.cell("=HYPERLINK(\"http://x.example\")"))
        .isEqualTo("\"'=HYPERLINK(\"\"http://x.example\"\")\"");
    assertThat(AuditLogService.cell("+1")).isEqualTo("'+1");
    assertThat(AuditLogService.cell("-2")).isEqualTo("'-2");
    assertThat(AuditLogService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    assertThat(AuditLogService.cell("  =1+1")).isEqualTo("'  =1+1");
    assertThat(AuditLogService.cell("alex@example.test")).isEqualTo("alex@example.test");
  }

  @Test
  void controlAndBidiCharactersBecomeSpacesAndCommasAreQuoted() {
    assertThat(AuditLogService.cell("a\r\nb\tc")).isEqualTo("a  b c");
    assertThat(AuditLogService.cell("\t=1")).isEqualTo("' =1");
    assertThat(AuditLogService.cell("evil‮txt.exe")).isEqualTo("evil txt.exe");
    assertThat(AuditLogService.cell("a,b")).isEqualTo("\"a,b\"");
    assertThat(AuditLogService.cell(null)).isEmpty();
    assertThat(AuditLogService.cell(42)).isEqualTo("42");
  }
}
