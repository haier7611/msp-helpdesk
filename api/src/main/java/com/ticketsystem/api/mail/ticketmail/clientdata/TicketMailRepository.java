package com.ticketsystem.api.mail.ticketmail.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.signature.SignatureProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What outbound ticket mail needs besides the ticket services: loop-safety suppression ({@code
 * ticket_mail_suppression}), a supporter's signature fields, the Message-IDs of the client's own
 * mail on a ticket (threading) and a person's name and address. It never reads internal notes or
 * SLA fields: outbound mail cannot carry them.
 */
@Repository
public class TicketMailRepository {

  /** A user as outbound mail needs them. */
  public record Person(
      long id, String name, String email, String role, String status, Long organisationId) {
    @Override
    public String toString() {
      return "Person[id=" + id + "]";
    }
  }

  private final JdbcClient jdbc;

  public TicketMailRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Is automatic client mail switched off for this ticket (bulk or automated reporter)? */
  public boolean isSuppressed(AccessScope scope, long ticketId) {
    scope.requireSupporterOrSystem();
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT EXISTS (SELECT 1 FROM ticket_mail_suppression s JOIN ticket t ON t.id"
                        + " = s.ticket_id WHERE s.ticket_id = :t AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + ")")
                .param("t", ticketId),
            scope)
        .query(Boolean.class)
        .single();
  }

  public void suppress(
      AccessScope scope, Long organisationId, long ticketId, String reason, Instant now) {
    if (organisationId == null) {
      scope.requireSupporterOrSystem();
    } else {
      scope.require(organisationId);
    }
    int n =
        ScopeSql.bind(
                jdbc.sql(
                        "INSERT INTO ticket_mail_suppression (ticket_id, reason, created_at)"
                            + " SELECT :t, :r, :now WHERE "
                            + ScopeSql.ticketVisible(scope, ":t")
                            + " ON CONFLICT DO NOTHING")
                    .param("t", ticketId)
                    .param("r", reason)
                    .param("now", Timestamp.from(now)),
                scope)
            .update();
    if (n == 0 && !ScopeSql.isTicketVisible(jdbc, scope, ticketId)) {
      throw AccessScope.notFound();
    }
  }

  /** Message-IDs of the client's emails stored on the ticket, newest first. */
  public List<String> clientMessageIds(AccessScope scope, long ticketId, int limit) {
    scope.requireSupporterOrSystem();
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT c.message_id FROM public_comment c JOIN ticket t ON t.id = c.ticket_id"
                        + " WHERE c.ticket_id = :t AND c.message_id IS NOT NULL AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY c.id DESC LIMIT :limit")
                .param("t", ticketId)
                .param("limit", limit),
            scope)
        .query(String.class)
        .list();
  }

  /**
   * A supporter's signature fields (the {@code supporter_profile} table; display name = the user's
   * name) and own email, for the outbound signature. Supporters only: customers have no signature.
   */
  public Optional<SignatureProfile> signatureProfile(AccessScope scope, long userId) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT u.email, u.name, COALESCE(p.job_title, '') AS job_title, COALESCE(p.phone, '')"
                + " AS phone, COALESCE(p.mobile, '') AS mobile, COALESCE(p.free_line, '') AS"
                + " free_line FROM app_user u LEFT JOIN supporter_profile p ON p.user_id = u.id"
                + " WHERE u.id = :id AND u.role IN ('AGENT', 'ADMIN')")
        .param("id", userId)
        .query(
            (rs, n) ->
                new SignatureProfile(
                    rs.getString("name"),
                    rs.getString("job_title"),
                    rs.getString("phone"),
                    rs.getString("mobile"),
                    rs.getString("free_line"),
                    rs.getString("email")))
        .optional();
  }

  /** A supporter, or a customer of an organisation the scope sees. */
  /** The id of the account holding {@code email}, of any role, if there is one. */
  public Optional<Long> accountIdByEmail(AccessScope scope, String email) {
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT id FROM app_user WHERE lower(email) = lower(:e)")
        .param("e", email)
        .query(Long.class)
        .optional();
  }

  public Optional<Person> person(AccessScope scope, long userId) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT id, name, email, role, status, organisation_id FROM app_user WHERE id = :id")
        .param("id", userId)
        .query(
            (rs, n) ->
                new Person(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getString("role"),
                    rs.getString("status"),
                    rs.getObject("organisation_id", Long.class)))
        .optional()
        .filter(p -> p.organisationId() == null || scope.canSee(p.organisationId()));
  }
}
