package com.ticketsystem.api.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth_token} access. Only token hashes are passed in or stored, never raw tokens. */
@Repository
public class AuthTokenRepository {

  /** A stored token. {@code tokenHash} and {@code codeHash} are hex SHA-256. */
  public record TokenRow(
      long id,
      TokenPurpose purpose,
      long userId,
      String tokenHash,
      String codeHash,
      int failedCodeAttempts,
      Instant expiresAt,
      Instant usedAt,
      Instant revokedAt) {

    public boolean usableAt(Instant now) {
      return usedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }
  }

  private final JdbcClient jdbc;

  public AuthTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Newer issuance voids older: removes the user's unused tokens of this purpose. */
  public int deleteUnused(long userId, TokenPurpose purpose) {
    return jdbc.sql("DELETE FROM auth_token WHERE user_id = ? AND purpose = ? AND used_at IS NULL")
        .params(userId, purpose.name())
        .update();
  }

  public long insert(
      long userId,
      TokenPurpose purpose,
      String tokenHash,
      String codeHash,
      Instant now,
      Instant expiresAt) {
    return jdbc.sql(
            "INSERT INTO auth_token (purpose, user_id, token_hash, code_hash, created_at,"
                + " expires_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
        .params(
            purpose.name(),
            userId,
            tokenHash,
            codeHash,
            Timestamp.from(now),
            Timestamp.from(expiresAt))
        .query(Long.class)
        .single();
  }

  /** Looks a token up by hash and purpose and locks its row for the transaction. */
  public Optional<TokenRow> findForUpdate(String tokenHash, TokenPurpose purpose) {
    return jdbc.sql("SELECT * FROM auth_token WHERE token_hash = ? AND purpose = ? FOR UPDATE")
        .params(tokenHash, purpose.name())
        .query(AuthTokenRepository::map)
        .optional();
  }

  public Optional<TokenRow> find(String tokenHash, TokenPurpose purpose) {
    return jdbc.sql("SELECT * FROM auth_token WHERE token_hash = ? AND purpose = ?")
        .params(tokenHash, purpose.name())
        .query(AuthTokenRepository::map)
        .optional();
  }

  /** Marks a token used; false when it was already used (lost a race). */
  public boolean markUsed(long id, Instant now) {
    return jdbc.sql("UPDATE auth_token SET used_at = ? WHERE id = ? AND used_at IS NULL")
            .params(Timestamp.from(now), id)
            .update()
        == 1;
  }

  /**
   * After a password reset or change, the user's outstanding reset and magic links stop working.
   */
  public void deleteUnusedSignInLinks(long userId) {
    jdbc.sql(
            "DELETE FROM auth_token WHERE user_id = ? AND used_at IS NULL"
                + " AND purpose IN ('PASSWORD_RESET', 'MAGIC_LINK')")
        .param(userId)
        .update();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static TokenRow map(ResultSet rs, int row) throws SQLException {
    return new TokenRow(
        rs.getLong("id"),
        TokenPurpose.valueOf(rs.getString("purpose")),
        rs.getLong("user_id"),
        rs.getString("token_hash"),
        rs.getString("code_hash"),
        rs.getInt("failed_code_attempts"),
        instant(rs, "expires_at"),
        instant(rs, "used_at"),
        instant(rs, "revoked_at"));
  }
}
