package com.ticketsystem.api.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code app_user} access for the auth flows (sign-in, invitations, links), which act on one known
 * account and return no other organisation's data, so no AccessScope here. A customer's
 * organisation is set when they are invited (database check: customers have exactly one, supporters
 * none); organisation changes and customer lists go through scoped repositories ({@code
 * access.clientdata}). Emails are passed in their {@link Emails} canonical form.
 */
@Repository
public class UserRepository {

  private static final String COLUMNS =
      "id, email, name, role, status, password_hash, security_version";

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AppUser> findByEmail(String email) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE lower(email) = lower(?)")
        .param(email)
        .query(UserRepository::map)
        .optional();
  }

  public Optional<AppUser> findById(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ?")
        .param(id)
        .query(UserRepository::map)
        .optional();
  }

  /** Locks the row for the rest of the transaction. */
  public Optional<AppUser> findByIdForUpdate(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ? FOR UPDATE")
        .param(id)
        .query(UserRepository::map)
        .optional();
  }

  public boolean anyActiveAdmin() {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE')")
        .query(Boolean.class)
        .single();
  }

  public boolean anyAdmin() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE role = 'ADMIN')")
        .query(Boolean.class)
        .single();
  }

  /** {@code organisationId}: the customer's organisation; null for supporters. */
  public long insertInvited(
      String email, String name, Role role, Long organisationId, Instant now) {
    return jdbc.sql(
            "INSERT INTO app_user (email, name, role, status, organisation_id, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'INVITED', ?, ?, ?) RETURNING id")
        .params(email, name, role.name(), organisationId, ts(now), ts(now))
        .query(Long.class)
        .single();
  }

  /** A supporter account that is active at once (first-admin setup). */
  public long insertActive(String email, String name, Role role, String passwordHash, Instant now) {
    return insertActive(email, name, role, null, passwordHash, now);
  }

  /** {@code organisationId}: the customer's organisation; null for supporters. */
  public long insertActive(
      String email, String name, Role role, Long organisationId, String passwordHash, Instant now) {
    return jdbc.sql(
            "INSERT INTO app_user (email, name, role, status, organisation_id, password_hash,"
                + " created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?) RETURNING id")
        .params(email, name, role.name(), organisationId, passwordHash, ts(now), ts(now))
        .query(Long.class)
        .single();
  }

  /** Re-invitation of a still-invited user may correct the name, role and organisation. */
  public void updateInvited(long id, String name, Role role, Long organisationId, Instant now) {
    jdbc.sql(
            "UPDATE app_user SET name = ?, role = ?, organisation_id = ?, updated_at = ? WHERE id ="
                + " ? AND status = 'INVITED'")
        .params(name, role.name(), organisationId, ts(now), id)
        .update();
  }

  /** Accepting an invitation: set the password and activate. */
  public void activate(long id, String passwordHash, Instant now) {
    jdbc.sql(
            "UPDATE app_user SET status = 'ACTIVE', password_hash = ?, updated_at = ? WHERE id = ?")
        .params(passwordHash, ts(now), id)
        .update();
  }

  /** Password change or reset: new hash and a bumped security_version (sessions check it). */
  public void changePassword(long id, String passwordHash, Instant now) {
    jdbc.sql(
            "UPDATE app_user SET password_hash = ?, security_version = security_version + 1,"
                + " updated_at = ? WHERE id = ?")
        .params(passwordHash, ts(now), id)
        .update();
  }

  /** A user changes their own display name (My account). Not a security event. */
  public void updateName(long id, String name, Instant now) {
    jdbc.sql("UPDATE app_user SET name = ?, updated_at = ? WHERE id = ?")
        .params(name, ts(now), id)
        .update();
  }

  /** Re-hash on sign-in when the encoder parameters were raised; not a security event. */
  public void upgradePasswordHash(long id, String passwordHash) {
    jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?")
        .params(passwordHash, id)
        .update();
  }

  private static Timestamp ts(Instant instant) {
    return Timestamp.from(instant);
  }

  private static AppUser map(ResultSet rs, int row) throws SQLException {
    return new AppUser(
        rs.getLong("id"),
        rs.getString("email"),
        rs.getString("name"),
        Role.valueOf(rs.getString("role")),
        UserStatus.valueOf(rs.getString("status")),
        rs.getString("password_hash"),
        rs.getInt("security_version"));
  }
}
