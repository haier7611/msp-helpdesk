package com.ticketsystem.api.crypto.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.entra.EntraSettingsService;
import com.ticketsystem.api.auth.mfa.MfaService;
import com.ticketsystem.api.crypto.DataCipher;
import com.ticketsystem.api.mail.intake.MailboxService;
import java.util.List;
import java.util.function.LongFunction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every value encrypted with {@link DataCipher}, for the data-key rotation command only. SYSTEM
 * scope only. Holds ciphertext, never plain values. Table and column names come from {@link Kind}
 * constants, never from input.
 */
@Repository
public class EncryptedSecretRepository {

  /** Where encrypted values live, and the associated data each was sealed with. */
  public enum Kind {
    MFA_SECRET("user_mfa", "user_id", "secret_ciphertext", "secret_key_id", MfaService::aad),
    MFA_PENDING("user_mfa", "user_id", "pending_ciphertext", "pending_key_id", MfaService::aad),
    MAILBOX_SECRET("mailbox", "id", "secret_ciphertext", "secret_key_id", MailboxService::aad),
    /** A mailbox's separate SMTP password. */
    MAILBOX_SMTP_SECRET(
        "mailbox", "id", "smtp_secret_ciphertext", "smtp_secret_key_id", MailboxService::smtpAad),
    ENTRA_SECRET(
        "entra_settings",
        "id",
        "secret_ciphertext",
        "secret_key_id",
        id -> EntraSettingsService.SECRET_AAD);

    final String table;
    final String idColumn;
    final String valueColumn;
    final String keyColumn;
    private final LongFunction<String> aad;

    Kind(
        String table,
        String idColumn,
        String valueColumn,
        String keyColumn,
        LongFunction<String> aad) {
      this.table = table;
      this.idColumn = idColumn;
      this.valueColumn = valueColumn;
      this.keyColumn = keyColumn;
      this.aad = aad;
    }

    /** The associated data the owning service seals this row's value with. */
    public String associatedData(long rowId) {
      return aad.apply(rowId);
    }
  }

  /** One stored value. {@code toString} never prints the ciphertext. */
  public record Stored(Kind kind, long rowId, byte[] ciphertext, String keyId) {
    public DataCipher.Sealed sealed() {
      return new DataCipher.Sealed(ciphertext, keyId);
    }

    @Override
    public String toString() {
      return "Stored[" + kind + " row " + rowId + " key " + keyId + "]";
    }
  }

  /** How many values of a kind are sealed with a key id. */
  public record KeyCount(Kind kind, String keyId, long count) {}

  private final JdbcClient jdbc;

  public EncryptedSecretRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Values of {@code kind} not sealed with {@code currentKeyId}, with a row id above {@code
   * afterId}, in id order, at most {@code limit} (paged, so values that cannot be decrypted are
   * skipped rather than read again).
   */
  public List<Stored> notOnKey(
      AccessScope scope, Kind kind, String currentKeyId, long afterId, int limit) {
    requireSystem(scope);
    return jdbc.sql(
            "SELECT "
                + kind.idColumn
                + " AS row_id, "
                + kind.valueColumn
                + " AS value, "
                + kind.keyColumn
                + " AS key_id FROM "
                + kind.table
                + " WHERE "
                + kind.valueColumn
                + " IS NOT NULL AND "
                + kind.keyColumn
                + " <> :current AND "
                + kind.idColumn
                + " > :after ORDER BY "
                + kind.idColumn
                + " LIMIT :limit")
        .param("current", currentKeyId)
        .param("after", afterId)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Stored(
                    kind, rs.getLong("row_id"), rs.getBytes("value"), rs.getString("key_id")))
        .list();
  }

  /** Counts per kind and key id, for the report. */
  public List<KeyCount> countByKey(AccessScope scope) {
    requireSystem(scope);
    return java.util.Arrays.stream(Kind.values())
        .flatMap(
            kind ->
                jdbc
                    .sql(
                        "SELECT "
                            + kind.keyColumn
                            + " AS key_id, count(*) AS n FROM "
                            + kind.table
                            + " WHERE "
                            + kind.valueColumn
                            + " IS NOT NULL GROUP BY "
                            + kind.keyColumn
                            + " ORDER BY 1")
                    .query((rs, n) -> new KeyCount(kind, rs.getString("key_id"), rs.getLong("n")))
                    .list()
                    .stream())
        .toList();
  }

  /**
   * Replaces one value only if it is still exactly what was read (a running app may have changed it
   * meanwhile, for example an MFA re-enrolment); returns false when it was not replaced.
   */
  public boolean replace(AccessScope scope, Stored old, DataCipher.Sealed replacement) {
    requireSystem(scope);
    Kind kind = old.kind();
    return jdbc.sql(
                "UPDATE "
                    + kind.table
                    + " SET "
                    + kind.valueColumn
                    + " = :value, "
                    + kind.keyColumn
                    + " = :newKey WHERE "
                    + kind.idColumn
                    + " = :id AND "
                    + kind.keyColumn
                    + " = :oldKey AND "
                    + kind.valueColumn
                    + " = :oldValue")
            .param("value", replacement.ciphertext())
            .param("newKey", replacement.keyId())
            .param("id", old.rowId())
            .param("oldKey", old.keyId())
            .param("oldValue", old.ciphertext())
            .update()
        == 1;
  }

  private static void requireSystem(AccessScope scope) {
    if (!scope.isSystem()) {
      throw AccessScope.notFound();
    }
  }
}
