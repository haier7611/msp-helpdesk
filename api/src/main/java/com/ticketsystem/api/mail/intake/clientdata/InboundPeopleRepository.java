package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who an inbound sender is: a supporter, a saved contact of an organisation, or someone on an
 * organisation's verified domain. Reads of customers and domains are scoped by organisation like
 * every other client-owned row; the poller uses the SYSTEM scope.
 */
@Repository
public class InboundPeopleRepository {

  /**
   * An account with this email address.
   *
   * @param role CUSTOMER, AGENT or ADMIN
   * @param status INVITED, ACTIVE or DEACTIVATED
   * @param organisationId the customer's organisation; null for supporters
   */
  public record Account(
      long userId, String email, String name, String role, String status, Long organisationId) {

    public boolean activeSupporter() {
      return "ACTIVE".equals(status) && ("AGENT".equals(role) || "ADMIN".equals(role));
    }

    /** A saved contact: a customer who is active or invited. */
    public boolean contact() {
      return "CUSTOMER".equals(role) && ("ACTIVE".equals(status) || "INVITED".equals(status));
    }

    @Override
    public String toString() {
      return "Account[id=" + userId + ", role=" + role + ", status=" + status + "]";
    }
  }

  private final JdbcClient jdbc;

  public InboundPeopleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The account with this address. Customers of organisations the scope cannot see are left out;
   * supporters are always returned (they belong to no organisation).
   */
  public Optional<Account> byEmail(AccessScope scope, String email) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT id, email, name, role, status, organisation_id FROM app_user WHERE"
                + " lower(email) = lower(:e)")
        .param("e", email)
        .query(
            (rs, n) ->
                new Account(
                    rs.getLong("id"),
                    rs.getString("email"),
                    rs.getString("name"),
                    rs.getString("role"),
                    rs.getString("status"),
                    rs.getObject("organisation_id", Long.class)))
        .optional()
        .filter(a -> a.organisationId() == null || scope.canSee(a.organisationId()));
  }

  /** The organisation whose verified domain is exactly {@code domain} (no parent-domain walk). */
  public Optional<Long> organisationOfDomain(AccessScope scope, String domain) {
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT organisation_id FROM organisation_domain WHERE domain = :d")
        .param("d", domain)
        .query(Long.class)
        .optional()
        .filter(scope::canSee);
  }

  /** A verified domain and its organisation. */
  public record DomainOwner(String domain, long organisationId) {}

  /**
   * A verified domain that a domain block rule {@code value} would cover: the domain itself or any
   * subdomain of it (the block list matches subdomains). The exact match comes first.
   */
  public Optional<DomainOwner> verifiedDomainUnder(AccessScope scope, String value) {
    scope.requireAdmin();
    return jdbc.sql(
            "SELECT domain, organisation_id FROM organisation_domain WHERE domain = :d OR"
                + " right(domain, length(:d) + 1) = '.' || :d ORDER BY (domain = :d) DESC,"
                + " domain LIMIT 1")
        .param("d", value)
        .query((rs, n) -> new DomainOwner(rs.getString(1), rs.getLong(2)))
        .optional();
  }

  /** Name of an organisation (block-list warnings). */
  public Optional<String> organisationName(AccessScope scope, long organisationId) {
    scope.requireSupporterOrSystem();
    if (!scope.canSee(organisationId)) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT name FROM organisation WHERE id = :id")
        .param("id", organisationId)
        .query(String.class)
        .optional();
  }

  /**
   * Creates an unverified contact (an invited customer who cannot sign in until invited) for an
   * unknown sender on a verified domain whose mail passed DMARC.
   */
  public long insertContact(
      AccessScope scope, String email, String name, long organisationId, Instant now) {
    scope.requireSupporterOrSystem();
    scope.require(organisationId);
    return jdbc.sql(
            "INSERT INTO app_user (email, name, role, status, organisation_id, created_at,"
                + " updated_at) VALUES (lower(:e), :n, 'CUSTOMER', 'INVITED', :org, :now, :now)"
                + " RETURNING id")
        .param("e", email)
        .param("n", name)
        .param("org", organisationId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }
}
