package com.ticketsystem.api.archfixture.ticket;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test-only negative fixture for ArchitectureTest: a repository for client-owned data outside a
 * {@code ..clientdata..} package, using JdbcClient with an unscoped {@link #findAll}. The rules
 * that every {@code *Repository} lives in {@code clientdata} and that only {@code clientdata} plus
 * a named allow-list may use JDBC must reject it. Test sources only and not annotated, so it never
 * becomes a Spring bean.
 */
public class TicketRepository {

  private final JdbcClient jdbc;

  public TicketRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every organisation's tickets - exactly what the rules exist to stop. */
  public List<Long> findAll() {
    return jdbc.sql("SELECT id FROM ticket").query(Long.class).list();
  }
}
