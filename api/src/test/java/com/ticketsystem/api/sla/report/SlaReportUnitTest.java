package com.ticketsystem.api.sla.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CSV cells and the average/median maths of the SLA report. */
class SlaReportUnitTest {

  @Test
  void csvCellsAreQuotedAndFormulaLeadingValuesNeutralised() {
    assertThat(SlaReportService.cell(null)).isEmpty();
    assertThat(SlaReportService.cell("Printer")).isEqualTo("Printer");
    assertThat(SlaReportService.cell("a,b")).isEqualTo("\"a,b\"");
    assertThat(SlaReportService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(SlaReportService.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    assertThat(SlaReportService.cell("+1")).isEqualTo("'+1");
    assertThat(SlaReportService.cell("-2")).isEqualTo("'-2");
    assertThat(SlaReportService.cell("@sum")).isEqualTo("'@sum");
    assertThat(SlaReportService.cell(42L)).isEqualTo("42");
  }

  @Test
  void averagesAndMediansRoundToOneDecimal() {
    assertThat(SlaReportService.stats(List.of()).averageMinutes()).isNull();
    var odd = SlaReportService.stats(List.of(420L, 20L, 360L));
    assertThat(odd.count()).isEqualTo(3);
    assertThat(odd.averageMinutes()).isEqualTo(266.7);
    assertThat(odd.medianMinutes()).isEqualTo(360.0);
    var even = SlaReportService.stats(List.of(60L, 30L, 15L, 90L));
    assertThat(even.averageMinutes()).isEqualTo(48.8);
    assertThat(even.medianMinutes()).isEqualTo(45.0);
  }
}
