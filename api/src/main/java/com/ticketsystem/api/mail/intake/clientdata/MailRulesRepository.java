package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code mail_block_rule} and {@code sender_rule}. The block list is installation-wide (admin and
 * SYSTEM only). Sender rules name an organisation, so reads are also scoped by organisation.
 */
@Repository
public class MailRulesRepository {

  public record BlockRuleRow(long id, String kind, String value, Instant createdAt) {}

  public record SenderRuleRow(
      long id,
      String kind,
      String value,
      long organisationId,
      long requestTypeId,
      Instant createdAt) {}

  private final JdbcClient jdbc;

  public MailRulesRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<BlockRuleRow> blockRules(AccessScope scope) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql("SELECT id, kind, value, created_at FROM mail_block_rule ORDER BY id")
        .query(
            (rs, n) ->
                new BlockRuleRow(
                    rs.getLong("id"),
                    rs.getString("kind"),
                    rs.getString("value"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /** Adds a rule; empty when the same rule exists already. */
  public Optional<Long> addBlockRule(
      AccessScope scope, String kind, String value, Long userId, Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO mail_block_rule (kind, value, created_by_user_id, created_at) VALUES"
                + " (:k, :v, :u, :now) ON CONFLICT DO NOTHING RETURNING id")
        .param("k", kind)
        .param("v", value)
        .param("u", userId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .optional();
  }

  public Optional<BlockRuleRow> deleteBlockRule(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "DELETE FROM mail_block_rule WHERE id = :id RETURNING id, kind, value, created_at")
        .param("id", id)
        .query(
            (rs, n) ->
                new BlockRuleRow(
                    rs.getLong("id"),
                    rs.getString("kind"),
                    rs.getString("value"),
                    rs.getTimestamp("created_at").toInstant()))
        .optional();
  }

  /** Sender rules of the organisations the scope sees. */
  public List<SenderRuleRow> senderRules(AccessScope scope) {
    IntakeScopes.requireAdminOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                "SELECT id, kind, value, organisation_id, request_type_id, created_at FROM"
                    + " sender_rule WHERE "
                    + ScopeSql.organisation(scope, "organisation_id")
                    + " ORDER BY id"),
            scope)
        .query(MailRulesRepository::mapSender)
        .list();
  }

  public Optional<Long> addSenderRule(
      AccessScope scope,
      String kind,
      String value,
      long organisationId,
      long requestTypeId,
      Long userId,
      Instant now) {
    scope.requireAdmin();
    return jdbc.sql(
            "INSERT INTO sender_rule (kind, value, organisation_id, request_type_id,"
                + " created_by_user_id, created_at) VALUES (:k, :v, :org, :type, :u, :now) ON"
                + " CONFLICT DO NOTHING RETURNING id")
        .param("k", kind)
        .param("v", value)
        .param("org", organisationId)
        .param("type", requestTypeId)
        .param("u", userId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .optional();
  }

  public Optional<SenderRuleRow> deleteSenderRule(AccessScope scope, long id) {
    scope.requireAdmin();
    return jdbc.sql(
            "DELETE FROM sender_rule WHERE id = :id RETURNING id, kind, value, organisation_id,"
                + " request_type_id, created_at")
        .param("id", id)
        .query(MailRulesRepository::mapSender)
        .optional();
  }

  /**
   * The rule for a sender: an exact address rule wins over a domain rule; a domain rule matches the
   * sender's exact domain only (no parent-domain walk, so a rule for a public suffix such as {@code
   * co.uk} can never catch other organisations' mail).
   */
  public Optional<SenderRuleRow> match(AccessScope scope, String address, String domain) {
    IntakeScopes.requireAdminOrSystem(scope);
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT id, kind, value, organisation_id, request_type_id, created_at FROM"
                        + " sender_rule WHERE ((kind = 'ADDRESS' AND value = :a) OR (kind ="
                        + " 'DOMAIN' AND value = :d)) AND "
                        + ScopeSql.organisation(scope, "organisation_id")
                        + " ORDER BY CASE kind WHEN 'ADDRESS' THEN 0 ELSE 1 END LIMIT 1")
                .param("a", address)
                .param("d", domain),
            scope)
        .query(MailRulesRepository::mapSender)
        .optional();
  }

  private static SenderRuleRow mapSender(java.sql.ResultSet rs, int n)
      throws java.sql.SQLException {
    return new SenderRuleRow(
        rs.getLong("id"),
        rs.getString("kind"),
        rs.getString("value"),
        rs.getLong("organisation_id"),
        rs.getLong("request_type_id"),
        rs.getTimestamp("created_at").toInstant());
  }
}
