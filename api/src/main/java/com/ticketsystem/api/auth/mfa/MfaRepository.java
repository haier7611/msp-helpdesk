package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.crypto.DataCipher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code user_mfa} and {@code mfa_recovery_code}. Secrets cross this class only as ciphertext;
 * {@link MfaService} encrypts and decrypts. Not client-owned data (no AccessScope): every method is
 * keyed by the signed-in or target user id.
 */
@Repository
public class MfaRepository {

  /** A user's MFA row (ciphertexts only). */
  public record Row(
      long userId,
      DataCipher.Sealed secret,
      Instant confirmedAt,
      long lastUsedStep,
      DataCipher.Sealed pending,
      Instant pendingCreatedAt) {

    public boolean enrolled() {
      return confirmedAt != null && secret != null;
    }

    @Override
    public String toString() {
      return "MfaRow[userId=" + userId + ", enrolled=" + enrolled() + "]";
    }
  }

  private final JdbcClient jdbc;

  public MfaRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Row> find(long userId) {
    return query("SELECT * FROM user_mfa WHERE user_id = ?", userId);
  }

  public Optional<Row> findForUpdate(long userId) {
    return query("SELECT * FROM user_mfa WHERE user_id = ? FOR UPDATE", userId);
  }

  public boolean isEnrolled(long userId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM user_mfa WHERE user_id = ? AND confirmed_at IS NOT NULL)")
        .param(userId)
        .query(Boolean.class)
        .single();
  }

  /** Starts (or restarts) an enrolment: the new secret is pending, any active one stays. */
  public void savePending(long userId, DataCipher.Sealed pending, Instant now) {
    jdbc.sql(
            "INSERT INTO user_mfa (user_id, pending_ciphertext, pending_key_id, pending_created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET"
                + " pending_ciphertext = EXCLUDED.pending_ciphertext, pending_key_id ="
                + " EXCLUDED.pending_key_id, pending_created_at = EXCLUDED.pending_created_at,"
                + " updated_at = EXCLUDED.updated_at")
        .params(userId, pending.ciphertext(), pending.keyId(), ts(now), ts(now))
        .update();
  }

  /** The pending secret becomes the active one; {@code step} is the step its code matched. */
  public void confirmPending(long userId, long step, Instant now) {
    jdbc.sql(
            "UPDATE user_mfa SET secret_ciphertext = pending_ciphertext, secret_key_id ="
                + " pending_key_id, confirmed_at = ?, last_used_step = ?, pending_ciphertext ="
                + " NULL, pending_key_id = NULL, pending_created_at = NULL, updated_at = ? WHERE"
                + " user_id = ? AND pending_ciphertext IS NOT NULL")
        .params(ts(now), step, ts(now), userId)
        .update();
  }

  /**
   * Records an accepted code's step; false when that step (or a later one) was already used, which
   * makes the check-and-set atomic against two requests with the same code.
   */
  public boolean acceptStep(long userId, long step) {
    return jdbc.sql(
                "UPDATE user_mfa SET last_used_step = ? WHERE user_id = ? AND last_used_step < ?")
            .params(step, userId, step)
            .update()
        == 1;
  }

  /** MFA reset: forget the secret, any pending enrolment and the recovery codes. */
  public void delete(long userId) {
    jdbc.sql("DELETE FROM mfa_recovery_code WHERE user_id = ?").param(userId).update();
    jdbc.sql("DELETE FROM user_mfa WHERE user_id = ?").param(userId).update();
  }

  public void replaceRecoveryCodes(long userId, List<String> hashes, Instant now) {
    jdbc.sql("DELETE FROM mfa_recovery_code WHERE user_id = ?").param(userId).update();
    for (String hash : hashes) {
      jdbc.sql("INSERT INTO mfa_recovery_code (user_id, code_hash, created_at) VALUES (?, ?, ?)")
          .params(userId, hash, ts(now))
          .update();
    }
  }

  /** Spends a recovery code; true exactly once per code. */
  public boolean useRecoveryCode(long userId, String hash, Instant now) {
    return jdbc.sql(
                "UPDATE mfa_recovery_code SET used_at = ? WHERE user_id = ? AND code_hash = ? AND"
                    + " used_at IS NULL")
            .params(ts(now), userId, hash)
            .update()
        == 1;
  }

  public int unusedRecoveryCodes(long userId) {
    return jdbc.sql("SELECT count(*) FROM mfa_recovery_code WHERE user_id = ? AND used_at IS NULL")
        .param(userId)
        .query(Integer.class)
        .single();
  }

  private Optional<Row> query(String sql, long userId) {
    return jdbc.sql(sql)
        .param(userId)
        .query(
            (rs, n) -> {
              byte[] secret = rs.getBytes("secret_ciphertext");
              byte[] pending = rs.getBytes("pending_ciphertext");
              Timestamp confirmed = rs.getTimestamp("confirmed_at");
              Timestamp pendingAt = rs.getTimestamp("pending_created_at");
              return new Row(
                  rs.getLong("user_id"),
                  secret == null
                      ? null
                      : new DataCipher.Sealed(secret, rs.getString("secret_key_id")),
                  confirmed == null ? null : confirmed.toInstant(),
                  rs.getLong("last_used_step"),
                  pending == null
                      ? null
                      : new DataCipher.Sealed(pending, rs.getString("pending_key_id")),
                  pendingAt == null ? null : pendingAt.toInstant());
            })
        .optional();
  }

  private static Timestamp ts(Instant instant) {
    return Timestamp.from(instant);
  }
}
