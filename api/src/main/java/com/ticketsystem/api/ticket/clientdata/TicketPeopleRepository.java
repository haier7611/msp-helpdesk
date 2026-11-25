package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The people checks tickets need: is someone a supporter who may work an organisation (assignee),
 * is someone a customer of an organisation (reporter), and display names for the agent view.
 */
@Repository
public class TicketPeopleRepository {

  /** A supporter for pickers; out of office from My settings, end date may be empty. */
  public record SupporterRow(
      long id,
      String name,
      String email,
      boolean outOfOffice,
      java.time.LocalDate outOfOfficeUntil) {}

  /** A customer account that can be a reporter. */
  public record Contact(long userId, String email) {}

  /**
   * The account holding an address, of any role or organisation. Only used to decide whether a CC
   * address may be added; callers never show another organisation's details.
   */
  public record Account(long userId, String role, Long organisationId, String status) {}

  private final JdbcClient jdbc;

  public TicketPeopleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Is {@code userId} an active supporter who may work {@code organisationId}? Admins and
   * all-organisation agents may work every organisation; a restricted agent only listed ones.
   * Triage ({@code organisationId} null) is open to every active supporter.
   */
  public boolean isActiveSupporterFor(AccessScope scope, long userId, Long organisationId) {
    scope.requireSupporterOrSystem();
    if (organisationId != null) {
      scope.require(organisationId);
    }
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM app_user u WHERE u.id = :u AND u.status = 'ACTIVE' AND"
                + " (u.role = 'ADMIN' OR (u.role = 'AGENT' AND (CAST(:org AS BIGINT) IS NULL OR"
                + " u.all_organisations OR EXISTS (SELECT 1 FROM supporter_organisation s WHERE"
                + " s.user_id = u.id AND s.organisation_id = :org)))))")
        .param("u", userId)
        .param("org", organisationId)
        .query(Boolean.class)
        .single();
  }

  /** A customer of {@code organisationId} (active or invited); empty otherwise. */
  public Optional<Contact> customerOf(AccessScope scope, long userId, long organisationId) {
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT id, email FROM app_user WHERE id = :u AND role = 'CUSTOMER' AND"
                + " organisation_id = :org AND status IN ('ACTIVE', 'INVITED')")
        .param("u", userId)
        .param("org", organisationId)
        .query((rs, n) -> new Contact(rs.getLong("id"), rs.getString("email")))
        .optional();
  }

  /**
   * Display names for the agent view: supporters, and customers of organisations the scope sees.
   * Supporters and system only (empty map otherwise).
   */
  public Map<Long, String> names(AccessScope scope, Collection<Long> userIds) {
    Map<Long, String> out = new HashMap<>();
    if (userIds.isEmpty() || (!scope.isSupporter() && !scope.isSystem())) {
      return out;
    }
    jdbc.sql("SELECT id, name, role, organisation_id FROM app_user WHERE id IN (:ids)")
        .param("ids", userIds)
        .query(
            rs -> {
              Long org = rs.getObject("organisation_id", Long.class);
              if (org == null || scope.canSee(org)) {
                out.put(rs.getLong("id"), rs.getString("name"));
              }
            });
    return out;
  }

  /**
   * Active supporters who may work {@code organisationId} (admins, all-organisation agents and
   * agents listed for it), by name; every active supporter when it is null. Supporters only; 404
   * for an organisation the scope may not see.
   */
  public List<SupporterRow> supporters(AccessScope scope, Long organisationId) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    if (organisationId != null) {
      scope.require(organisationId);
    }
    return jdbc.sql(
            "SELECT u.id, u.name, u.email, COALESCE(pr.out_of_office, FALSE) AS ooo,"
                + " pr.ooo_ends_on FROM app_user u LEFT JOIN supporter_preferences pr ON"
                + " pr.user_id = u.id WHERE u.status = 'ACTIVE' AND"
                + " (u.role = 'ADMIN' OR (u.role = 'AGENT' AND (CAST(:org AS BIGINT) IS NULL OR"
                + " u.all_organisations OR EXISTS (SELECT 1 FROM supporter_organisation s WHERE"
                + " s.user_id = u.id AND s.organisation_id = :org)))) ORDER BY lower(u.name), u.id")
        .param("org", organisationId)
        .query(
            (rs, n) ->
                new SupporterRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getBoolean("ooo"),
                    rs.getDate("ooo_ends_on") == null
                        ? null
                        : rs.getDate("ooo_ends_on").toLocalDate()))
        .list();
  }

  /**
   * The account holding {@code email} (normalised), if any. Supporters only, and only for a ticket
   * in an organisation the scope may work ({@code organisationId}).
   */
  public Optional<Account> accountByEmail(AccessScope scope, long organisationId, String email) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT id, role, organisation_id, status FROM app_user WHERE lower(email) ="
                + " lower(:e)")
        .param("e", email)
        .query(
            (rs, n) ->
                new Account(
                    rs.getLong("id"),
                    rs.getString("role"),
                    rs.getObject("organisation_id", Long.class),
                    rs.getString("status")))
        .optional();
  }

  /** Is {@code domain} (lower case) one of {@code organisationId}'s verified domains? */
  public boolean isVerifiedDomainOf(AccessScope scope, long organisationId, String domain) {
    scope.require(organisationId);
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM organisation_domain WHERE organisation_id = :org AND"
                + " domain = :d)")
        .param("org", organisationId)
        .param("d", domain)
        .query(Boolean.class)
        .single();
  }

  /** The organisation's name for a message to an agent who may work it. */
  public Optional<String> organisationName(AccessScope scope, long organisationId) {
    scope.require(organisationId);
    return jdbc.sql("SELECT name FROM organisation WHERE id = :org")
        .param("org", organisationId)
        .query(String.class)
        .optional();
  }
}
