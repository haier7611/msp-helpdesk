package com.ticketsystem.api.sla.clientdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.sla.ticket.SlaTestBase;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

/**
 * ADR-047 Decision 2: the minute job's "needs (re)calculating" query covers triage tickets, so it
 * relies on the V98 partial index {@code ticket_sla_work_ix}, whose predicate must stay identical
 * to the query's WHERE clause. With sequential scans priced out, the plan must use that index (a
 * drifted predicate would fall back to a scan of every ticket, every minute).
 */
class TicketSlaWorkListIndexIntegrationTest extends SlaTestBase {

  @Test
  void theWorkListQueryIsServedByItsPartialIndex() {
    String sql = TicketSlaRepository.STALE_WORK_LIST_SQL.replace(":limit", "50");
    List<String> plan =
        jdbc.execute(
            (ConnectionCallback<List<String>>)
                con -> {
                  List<String> lines = new ArrayList<>();
                  try (Statement st = con.createStatement()) {
                    st.execute("SET enable_seqscan = off");
                    try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                      while (rs.next()) {
                        lines.add(rs.getString(1));
                      }
                    } finally {
                      st.execute("RESET enable_seqscan");
                    }
                  }
                  return lines;
                });
    assertThat(String.join("\n", plan)).contains("ticket_sla_work_ix").doesNotContain("Seq Scan");
  }

  @Test
  void theOldOrganisationOnlyIndexIsGone() {
    Integer old =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'ticket_sla_stale_ix'",
            Integer.class);
    assertThat(old).isZero();
  }
}
