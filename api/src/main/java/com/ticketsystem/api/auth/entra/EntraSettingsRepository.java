package com.ticketsystem.api.auth.entra;

import com.ticketsystem.api.crypto.DataCipher;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The single {@code entra_settings} row. The client secret crosses this class only as ciphertext;
 * {@link EntraSettingsService} encrypts and decrypts it. Not client-owned data (no AccessScope):
 * one MSP-wide row, admin only.
 */
@Repository
public class EntraSettingsRepository {

  /** The settings row. {@code secret} is ciphertext and never leaves the auth module. */
  public record Row(
      boolean enabled,
      UUID clientId,
      UUID mspTenantId,
      DataCipher.Sealed secret,
      LocalDate secretExpiresOn,
      int settingsVersion,
      Integer testedVersion,
      Instant testedAt,
      Instant expiryWarningSentAt,
      Instant updatedAt) {

    /** Client ID, secret and MSP tenant are all set. */
    public boolean complete() {
      return clientId != null && mspTenantId != null && secret != null;
    }

    /** The current settings passed Test sign-in. */
    public boolean tested() {
      return testedVersion != null && testedVersion == settingsVersion;
    }

    @Override
    public String toString() {
      return "EntraSettings[enabled="
          + enabled
          + ", complete="
          + complete()
          + ", version="
          + settingsVersion
          + "]";
    }
  }

  private static final String SELECT =
      "SELECT enabled, client_id, msp_tenant_id, secret_ciphertext, secret_key_id,"
          + " secret_expires_on, settings_version, tested_version, tested_at,"
          + " expiry_warning_sent_at, updated_at FROM entra_settings WHERE id = 1";

  private final JdbcClient jdbc;

  public EntraSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Row get() {
    return jdbc.sql(SELECT).query(EntraSettingsRepository::map).single();
  }

  /** The row locked for the rest of the transaction (settings changes are serialised). */
  public Row getForUpdate() {
    return jdbc.sql(SELECT + " FOR UPDATE").query(EntraSettingsRepository::map).single();
  }

  /**
   * Saves the app details. {@code secret} null keeps the stored one. {@code bumpVersion} records
   * that the client ID, secret or tenant changed (Test sign-in needed again); {@code disable} turns
   * the switch off in the same statement.
   */
  public void saveDetails(
      UUID clientId,
      UUID mspTenantId,
      DataCipher.Sealed secret,
      LocalDate secretExpiresOn,
      boolean clearExpiryWarning,
      boolean bumpVersion,
      boolean disable,
      Long updatedBy,
      Instant now) {
    jdbc.sql(
            "UPDATE entra_settings SET client_id = :client, msp_tenant_id = :tenant,"
                + " secret_ciphertext = COALESCE(:cipher, secret_ciphertext),"
                + " secret_key_id = COALESCE(:keyId, secret_key_id),"
                + " secret_expires_on = :expires,"
                + " expiry_warning_sent_at = CASE WHEN :clearWarning THEN NULL"
                + " ELSE expiry_warning_sent_at END,"
                + " settings_version = settings_version + CASE WHEN :bump THEN 1 ELSE 0 END,"
                + " enabled = CASE WHEN :disable THEN FALSE ELSE enabled END,"
                + " updated_at = :now, updated_by = :by WHERE id = 1")
        .param("client", clientId)
        .param("tenant", mspTenantId)
        .param("cipher", secret == null ? null : secret.ciphertext())
        .param("keyId", secret == null ? null : secret.keyId())
        .param("expires", secretExpiresOn == null ? null : Date.valueOf(secretExpiresOn))
        .param("clearWarning", clearExpiryWarning)
        .param("bump", bumpVersion)
        .param("disable", disable)
        .param("now", Timestamp.from(now))
        .param("by", updatedBy)
        .update();
  }

  public void setEnabled(boolean enabled, Long updatedBy, Instant now) {
    jdbc.sql("UPDATE entra_settings SET enabled = ?, updated_at = ?, updated_by = ? WHERE id = 1")
        .params(enabled, Timestamp.from(now), updatedBy)
        .update();
  }

  /** Records a passed Test sign-in, only if the settings have not changed since it started. */
  public boolean markTested(int settingsVersion, Instant now) {
    return jdbc.sql(
                "UPDATE entra_settings SET tested_version = settings_version, tested_at = ?"
                    + " WHERE id = 1 AND settings_version = ?")
            .params(Timestamp.from(now), settingsVersion)
            .update()
        == 1;
  }

  public void markExpiryWarningSent(Instant now) {
    jdbc.sql("UPDATE entra_settings SET expiry_warning_sent_at = ? WHERE id = 1")
        .param(Timestamp.from(now))
        .update();
  }

  private static Row map(ResultSet rs, int n) throws SQLException {
    byte[] cipher = rs.getBytes("secret_ciphertext");
    Date expires = rs.getDate("secret_expires_on");
    return new Row(
        rs.getBoolean("enabled"),
        rs.getObject("client_id", UUID.class),
        rs.getObject("msp_tenant_id", UUID.class),
        cipher == null ? null : new DataCipher.Sealed(cipher, rs.getString("secret_key_id")),
        expires == null ? null : expires.toLocalDate(),
        rs.getInt("settings_version"),
        rs.getObject("tested_version", Integer.class),
        instant(rs.getTimestamp("tested_at")),
        instant(rs.getTimestamp("expiry_warning_sent_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
