package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.Priority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code request_type}: installation-wide configuration (no organisation). The scope decides which
 * types are offered: customers only see portal-visible types, so "Alert" is hidden from the portal.
 * Listed in {@code sort_order} ("Other or general question" last, Alert after it). Archived types
 * are left out of the lists offered for new tickets but still found by id, so tickets that use one
 * keep showing it.
 */
@Repository
public class RequestTypeRepository {

  public record RequestTypeRow(
      long id,
      String name,
      Priority defaultPriority,
      boolean portalVisible,
      String description,
      int sortOrder,
      boolean archived,
      Long defaultAssigneeUserId) {}

  private static final String COLUMNS =
      "id, name, default_priority, portal_visible, description, sort_order, archived,"
          + " default_assignee_user_id";

  private final JdbcClient jdbc;

  public RequestTypeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Types offered for new tickets (not archived), in display order. */
  public List<RequestTypeRow> list(AccessScope scope) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM request_type WHERE NOT archived"
                + (customerFacing(scope) ? " AND portal_visible" : "")
                + " ORDER BY sort_order, lower(name), id")
        .query(RequestTypeRepository::map)
        .list();
  }

  /** Every type, archived ones included (Admin - Request types). */
  public List<RequestTypeRow> listAll(AccessScope scope) {
    scope.requireAdmin();
    return jdbc.sql("SELECT " + COLUMNS + " FROM request_type ORDER BY archived, sort_order, id")
        .query(RequestTypeRepository::map)
        .list();
  }

  public Optional<RequestTypeRow> find(AccessScope scope, long id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM request_type WHERE id = :id"
                + (customerFacing(scope) ? " AND portal_visible AND NOT archived" : ""))
        .param("id", id)
        .query(RequestTypeRepository::map)
        .optional();
  }

  /** A type by name, case-insensitive (e.g. "Alert" for the API). */
  public Optional<RequestTypeRow> findByName(AccessScope scope, String name) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM request_type WHERE lower(name) = lower(:name)")
        .param("name", name)
        .query(RequestTypeRepository::map)
        .optional();
  }

  /** Is the name taken by another type (case-insensitive)? */
  public boolean nameTaken(AccessScope scope, String name, Long exceptId) {
    scope.requireAdmin();
    return jdbc.sql(
                "SELECT count(*) FROM request_type WHERE lower(name) = lower(:name)"
                    + " AND (CAST(:except AS BIGINT) IS NULL OR id <> :except)")
            .param("name", name)
            .param("except", exceptId)
            .query(Long.class)
            .single()
        > 0;
  }

  public long insert(
      AccessScope scope,
      String name,
      String description,
      Priority priority,
      boolean portalVisible,
      Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO request_type (name, description, default_priority, portal_visible,"
                + " sort_order, archived, created_at) VALUES (:name, :description, :priority,"
                + " :visible, (SELECT coalesce(max(sort_order), 0) + 10 FROM request_type),"
                + " FALSE, :now) RETURNING id")
        .param("name", name)
        .param("description", description)
        .param("priority", priority.name())
        .param("visible", portalVisible)
        .param("now", java.sql.Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public boolean update(
      AccessScope scope,
      long id,
      String name,
      String description,
      Priority priority,
      boolean portalVisible,
      boolean archived) {
    scope.requireAdmin();
    return jdbc.sql(
                "UPDATE request_type SET name = :name, description = :description,"
                    + " default_priority = :priority, portal_visible = :visible,"
                    + " archived = :archived WHERE id = :id")
            .param("id", id)
            .param("name", name)
            .param("description", description)
            .param("priority", priority.name())
            .param("visible", portalVisible)
            .param("archived", archived)
            .update()
        == 1;
  }

  /** Sets the display order: the given ids get 10, 20, 30 ... in that order. */
  public void reorder(AccessScope scope, List<Long> ids) {
    scope.requireAdmin();
    int order = 10;
    for (Long id : ids) {
      jdbc.sql("UPDATE request_type SET sort_order = :order WHERE id = :id")
          .param("order", order)
          .param("id", id)
          .update();
      order += 10;
    }
  }

  /** How many tickets use the type (shown when archiving). */
  public long ticketCount(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql("SELECT count(*) FROM ticket WHERE request_type_id = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  /** How many sender rules route mail to the type (archiving is refused while any do). */
  public long senderRuleCount(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql("SELECT count(*) FROM sender_rule WHERE request_type_id = :id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  private static boolean customerFacing(AccessScope scope) {
    return scope.kind() == AccessScope.Kind.CUSTOMER;
  }

  private static RequestTypeRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    long assignee = rs.getLong("default_assignee_user_id");
    boolean noAssignee = rs.wasNull();
    return new RequestTypeRow(
        rs.getLong("id"),
        rs.getString("name"),
        Priority.valueOf(rs.getString("default_priority")),
        rs.getBoolean("portal_visible"),
        rs.getString("description"),
        rs.getInt("sort_order"),
        rs.getBoolean("archived"),
        noAssignee ? null : assignee);
  }
}
