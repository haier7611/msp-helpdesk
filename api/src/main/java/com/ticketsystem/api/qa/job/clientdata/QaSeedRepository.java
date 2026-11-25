package com.ticketsystem.api.qa.job.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The QA seed marker table and the deletes the wipe command needs. Everything here is for the
 * host-shell QA commands only: every method requires a SYSTEM scope (404 otherwise), and the
 * deletes act only on ids the caller took from the marker table or from a seeded organisation.
 */
@Repository
public class QaSeedRepository {

  /** What a marker row points at. */
  public enum Kind {
    ORGANISATION,
    USER,
    TICKET,
    NOTE,
    /** A later step on an already marked ticket (for example the merged duplicate). */
    STEP
  }

  public record Marker(Kind kind, long refId, String refKey) {}

  private final JdbcClient jdbc;

  public QaSeedRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Marker> find(AccessScope scope, Kind kind, String refKey) {
    requireSystem(scope);
    return jdbc.sql("SELECT kind, ref_id, ref_key FROM qa_seed WHERE kind = ? AND ref_key = ?")
        .params(kind.name(), refKey)
        .query((rs, n) -> map(rs.getString("kind"), rs.getLong("ref_id"), rs.getString("ref_key")))
        .optional();
  }

  public List<Marker> list(AccessScope scope, Kind kind) {
    requireSystem(scope);
    return jdbc.sql("SELECT kind, ref_id, ref_key FROM qa_seed WHERE kind = ? ORDER BY id")
        .param(kind.name())
        .query((rs, n) -> map(rs.getString("kind"), rs.getLong("ref_id"), rs.getString("ref_key")))
        .list();
  }

  public void mark(AccessScope scope, Kind kind, long refId, String refKey, Instant now) {
    requireSystem(scope);
    jdbc.sql(
            "INSERT INTO qa_seed (kind, ref_id, ref_key, created_at) VALUES (?, ?, ?, ?) ON"
                + " CONFLICT DO NOTHING")
        .params(kind.name(), refId, refKey, Timestamp.from(now))
        .update();
  }

  public void unmark(AccessScope scope, Kind kind, long refId) {
    requireSystem(scope);
    jdbc.sql("DELETE FROM qa_seed WHERE kind = ? AND ref_id = ?")
        .params(kind.name(), refId)
        .update();
  }

  /**
   * Ids of every ticket in these organisations, seeded or not (used only with an explicit flag).
   */
  public List<Long> ticketIdsInOrganisations(AccessScope scope, List<Long> organisationIds) {
    requireSystem(scope);
    if (organisationIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT id FROM ticket WHERE organisation_id IN (:ids) ORDER BY id")
        .param("ids", organisationIds)
        .query(Long.class)
        .list();
  }

  /**
   * Deletes the tickets and everything that belongs to them (same set of tables as the retention
   * job, any status). Returns how many tickets were deleted.
   */
  public int deleteTickets(AccessScope scope, List<Long> ticketIds) {
    requireSystem(scope);
    if (ticketIds.isEmpty()) {
      return 0;
    }
    List<String> statements = new ArrayList<>();
    statements.add(
        "UPDATE ticket SET duplicate_of_id = NULL WHERE duplicate_of_id IN (:ids) AND id NOT IN"
            + " (:ids)");
    statements.add(
        "UPDATE ticket SET follow_up_of_id = NULL WHERE follow_up_of_id IN (:ids) AND id NOT IN"
            + " (:ids)");
    statements.add(
        "DELETE FROM ticket_link WHERE low_ticket_id IN (:ids) OR high_ticket_id IN (:ids)");
    statements.add("DELETE FROM attachment WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM public_comment WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM internal_note WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM system_entry WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM ticket_event WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM ticket_participant WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM inbound_mail WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM outbox_message WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM ticket_mail_suppression WHERE ticket_id IN (:ids)");
    statements.add("DELETE FROM portal_submission WHERE ticket_id IN (:ids)");
    statements.add(
        "UPDATE ticket_reference SET issued_ticket_id = NULL WHERE issued_ticket_id IN (:ids) AND"
            + " ticket_id NOT IN (:ids)");
    statements.add("DELETE FROM ticket_reference WHERE ticket_id IN (:ids)");
    for (String sql : statements) {
      jdbc.sql(sql).param("ids", ticketIds).update();
    }
    return jdbc.sql("DELETE FROM ticket WHERE id IN (:ids)").param("ids", ticketIds).update();
  }

  /** Queued or sent mail addressed to seeded accounts (reserved .example domains only). */
  public int deleteOutboxTo(AccessScope scope, List<String> emails) {
    requireSystem(scope);
    int total = 0;
    for (String email : emails) {
      total +=
          jdbc.sql(
                  "DELETE FROM outbox_message WHERE ',' || lower(to_addresses) || ',' LIKE ?"
                      + " ESCAPE '\\'")
              .param("%," + escapeLike(email.toLowerCase(Locale.ROOT)) + ",%")
              .update();
    }
    return total;
  }

  /**
   * Deletes one user and the rows that hang off it. A foreign key from data the seed did not create
   * (a ticket a tester raised) makes this throw, and the caller keeps the user.
   */
  public int deleteUser(AccessScope scope, long userId) {
    requireSystem(scope);
    jdbc.sql("DELETE FROM spring_session WHERE principal_name = ?")
        .param(Long.toString(userId))
        .update();
    return jdbc.sql("DELETE FROM app_user WHERE id = ?").param(userId).update();
  }

  /** Deletes one organisation; a foreign key from remaining data makes this throw. */
  public int deleteOrganisation(AccessScope scope, long organisationId) {
    requireSystem(scope);
    return jdbc.sql("DELETE FROM organisation WHERE id = ?").param(organisationId).update();
  }

  /** Active admins that are not in {@code excludedIds} (the last-admin guard). */
  public long countOtherActiveAdmins(AccessScope scope, List<Long> excludedIds) {
    requireSystem(scope);
    List<Long> excluded = excludedIds.isEmpty() ? List.of(-1L) : excludedIds;
    return jdbc.sql(
            "SELECT count(*) FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id NOT IN"
                + " (:ids)")
        .param("ids", excluded)
        .query(Long.class)
        .single();
  }

  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Marker map(String kind, long refId, String refKey) {
    return new Marker(Kind.valueOf(kind), refId, refKey);
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
