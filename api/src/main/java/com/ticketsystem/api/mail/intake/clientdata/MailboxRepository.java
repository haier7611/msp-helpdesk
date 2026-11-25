package com.ticketsystem.api.mail.intake.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.crypto.DataCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code mailbox}: admin-configured IMAP mailboxes and the poller's progress. The secret (password
 * or OAuth2 client secret) is only ever read by {@link #secret} for the poller and the test
 * connection; {@link MailboxRow} says only whether one is set.
 */
@Repository
public class MailboxRepository {

  /** A mailbox as the admin API and the poller see it (no secret). */
  public record MailboxRow(
      long id,
      String name,
      String address,
      boolean enabled,
      String imapHost,
      int imapPort,
      String imapSecurity,
      String imapFolder,
      String authType,
      String username,
      boolean secretSet,
      String oauthTokenUrl,
      String oauthClientId,
      String oauthScope,
      String trustedAuthservIds,
      Long uidValidity,
      long lastUid,
      Instant lastPollAt,
      Instant lastSuccessAt,
      String lastError,
      int consecutiveFailures,
      Instant nextPollAt,
      String smtpHost,
      Integer smtpPort,
      String smtpSecurity,
      String smtpLastError,
      boolean smtpSameSignIn,
      String smtpUsername,
      boolean smtpSecretSet) {

    /**
     * Enabled, with SMTP settings and the secret its SMTP sign-in uses (the incoming one, or its
     * own with a separate sign-in): this mailbox sends ticket mail.
     */
    public boolean sends() {
      return enabled && smtpHost != null && (smtpSameSignIn ? secretSet : smtpSecretSet);
    }
  }

  /** Editable settings. */
  public record MailboxValues(
      String name,
      String address,
      boolean enabled,
      String imapHost,
      int imapPort,
      String imapSecurity,
      String imapFolder,
      String authType,
      String username,
      String oauthTokenUrl,
      String oauthClientId,
      String oauthScope,
      String trustedAuthservIds,
      String smtpHost,
      Integer smtpPort,
      String smtpSecurity,
      boolean smtpSameSignIn,
      String smtpUsername) {

    /** Without a separate SMTP sign-in (the incoming one is used). */
    public MailboxValues(
        String name,
        String address,
        boolean enabled,
        String imapHost,
        int imapPort,
        String imapSecurity,
        String imapFolder,
        String authType,
        String username,
        String oauthTokenUrl,
        String oauthClientId,
        String oauthScope,
        String trustedAuthservIds,
        String smtpHost,
        Integer smtpPort,
        String smtpSecurity) {
      this(
          name,
          address,
          enabled,
          imapHost,
          imapPort,
          imapSecurity,
          imapFolder,
          authType,
          username,
          oauthTokenUrl,
          oauthClientId,
          oauthScope,
          trustedAuthservIds,
          smtpHost,
          smtpPort,
          smtpSecurity,
          true,
          null);
    }
  }

  private static final String COLUMNS =
      "id, name, address, enabled, imap_host, imap_port, imap_security, imap_folder, auth_type,"
          + " username, secret_ciphertext IS NOT NULL AS secret_set, oauth_token_url,"
          + " oauth_client_id, oauth_scope, trusted_authserv_ids, uid_validity, last_uid,"
          + " last_poll_at, last_success_at, last_error, consecutive_failures, next_poll_at,"
          + " smtp_host, smtp_port, smtp_security, smtp_last_error, smtp_same_sign_in,"
          + " smtp_username, smtp_secret_ciphertext IS NOT NULL AS smtp_secret_set";

  /**
   * Enabled, with SMTP settings and the secret its SMTP sign-in uses: the SQL twin of {@link
   * MailboxRow#sends()}.
   */
  private static final String SENDS =
      "(enabled AND smtp_host IS NOT NULL AND CASE WHEN smtp_same_sign_in THEN secret_ciphertext"
          + " IS NOT NULL ELSE smtp_secret_ciphertext IS NOT NULL END)";

  /** The sending mailbox first (enabled, SMTP, secret), then enabled ones, then by id. */
  private static final String SENDING_ORDER = " ORDER BY " + SENDS + " DESC, enabled DESC, id";

  private final JdbcClient jdbc;

  public MailboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insert(AccessScope scope, MailboxValues v, Instant now) {
    IntakeScopes.requireAdminOrSystem(scope);
    return bindValues(
            jdbc.sql(
                "INSERT INTO mailbox (name, address, enabled, imap_host, imap_port, imap_security,"
                    + " imap_folder, auth_type, username, oauth_token_url, oauth_client_id,"
                    + " oauth_scope, trusted_authserv_ids, smtp_host, smtp_port, smtp_security,"
                    + " smtp_same_sign_in, smtp_username, created_at, updated_at) VALUES (:name,"
                    + " :address, :enabled, :host, :port, :security, :folder, :auth, :username,"
                    + " :tokenUrl, :clientId, :oauthScope, :trusted, :smtpHost, :smtpPort,"
                    + " :smtpSecurity, :smtpSame, :smtpUser, :now, :now) RETURNING id"),
            v)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /**
   * Updates the settings. When the server, folder or account changes, the starting position is
   * cleared (the new folder's UIDs mean nothing against the old ones) and taken again from the new
   * folder's UIDNEXT ({@link #baseline}); the unique Message-ID still stops a mail being processed
   * twice.
   */
  public void update(AccessScope scope, long id, MailboxValues v, Instant now) {
    IntakeScopes.requireAdminOrSystem(scope);
    bindValues(
            jdbc.sql(
                "UPDATE mailbox SET uid_validity = CASE WHEN imap_host = :host AND imap_port ="
                    + " :port AND imap_folder = :folder AND username = :username THEN"
                    + " uid_validity END, last_uid = CASE WHEN imap_host = :host AND imap_port ="
                    + " :port AND imap_folder = :folder AND username = :username THEN last_uid"
                    + " ELSE 0 END, name = :name, address = :address, enabled = :enabled,"
                    + " imap_host = :host, imap_port = :port, imap_security = :security,"
                    + " imap_folder = :folder, auth_type = :auth, username = :username,"
                    + " oauth_token_url = :tokenUrl, oauth_client_id = :clientId, oauth_scope ="
                    + " :oauthScope, trusted_authserv_ids = :trusted, smtp_host = :smtpHost,"
                    + " smtp_port = :smtpPort, smtp_security = :smtpSecurity, smtp_same_sign_in ="
                    + " :smtpSame, smtp_username = :smtpUser, smtp_secret_ciphertext = CASE WHEN"
                    + " :smtpSame THEN NULL ELSE smtp_secret_ciphertext END, smtp_secret_key_id ="
                    + " CASE WHEN :smtpSame THEN NULL ELSE smtp_secret_key_id END,"
                    + " smtp_last_error = NULL, consecutive_failures = 0,"
                    + " next_poll_at = NULL, updated_at = :now WHERE id = :id"),
            v)
        .param("id", id)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static JdbcClient.StatementSpec bindValues(
      JdbcClient.StatementSpec spec, MailboxValues v) {
    return spec.param("name", v.name())
        .param("address", v.address())
        .param("enabled", v.enabled())
        .param("host", v.imapHost())
        .param("port", v.imapPort())
        .param("security", v.imapSecurity())
        .param("folder", v.imapFolder())
        .param("auth", v.authType())
        .param("username", v.username())
        .param("tokenUrl", v.oauthTokenUrl())
        .param("clientId", v.oauthClientId())
        .param("oauthScope", v.oauthScope())
        .param("trusted", v.trustedAuthservIds())
        .param("smtpHost", v.smtpHost())
        .param("smtpPort", v.smtpPort())
        .param("smtpSecurity", v.smtpSecurity())
        .param("smtpSame", v.smtpSameSignIn())
        .param("smtpUser", v.smtpUsername());
  }

  /**
   * The separate SMTP password, sealed with {@code MailboxService.smtpAad(id)}. Only for a mailbox
   * with a separate sign-in (the table refuses it otherwise).
   */
  public void setSmtpSecret(AccessScope scope, long id, DataCipher.Sealed sealed, Instant now) {
    IntakeScopes.requireAdminOrSystem(scope);
    jdbc.sql(
            "UPDATE mailbox SET smtp_secret_ciphertext = :c, smtp_secret_key_id = :k,"
                + " smtp_last_error = NULL, updated_at = :now WHERE id = :id")
        .param("c", sealed.ciphertext())
        .param("k", sealed.keyId())
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** The encrypted separate SMTP password, for the outbox sender and the test connection only. */
  public Optional<DataCipher.Sealed> smtpSecret(AccessScope scope, long id) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
            "SELECT smtp_secret_ciphertext, smtp_secret_key_id FROM mailbox WHERE id = :id AND"
                + " smtp_secret_ciphertext IS NOT NULL")
        .param("id", id)
        .query((rs, n) -> new DataCipher.Sealed(rs.getBytes(1), rs.getString(2)))
        .optional();
  }

  public void setSecret(AccessScope scope, long id, DataCipher.Sealed sealed, Instant now) {
    IntakeScopes.requireAdminOrSystem(scope);
    jdbc.sql(
            "UPDATE mailbox SET secret_ciphertext = :c, secret_key_id = :k, consecutive_failures"
                + " = 0, next_poll_at = NULL, updated_at = :now WHERE id = :id")
        .param("c", sealed.ciphertext())
        .param("k", sealed.keyId())
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** The encrypted secret, for the poller and the test connection only. */
  public Optional<DataCipher.Sealed> secret(AccessScope scope, long id) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
            "SELECT secret_ciphertext, secret_key_id FROM mailbox WHERE id = :id AND"
                + " secret_ciphertext IS NOT NULL")
        .param("id", id)
        .query((rs, n) -> new DataCipher.Sealed(rs.getBytes(1), rs.getString(2)))
        .optional();
  }

  public Optional<MailboxRow> find(AccessScope scope, long id) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql("SELECT " + COLUMNS + " FROM mailbox WHERE id = :id")
        .param("id", id)
        .query(MailboxRepository::map)
        .optional();
  }

  public List<MailboxRow> list(AccessScope scope) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql("SELECT " + COLUMNS + " FROM mailbox ORDER BY id")
        .query(MailboxRepository::map)
        .list();
  }

  public boolean delete(AccessScope scope, long id) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql("DELETE FROM mailbox WHERE id = :id").param("id", id).update() > 0;
  }

  /** Enabled mailboxes with a secret whose back-off (if any) has passed. */
  public List<MailboxRow> due(AccessScope scope, Instant now) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM mailbox WHERE enabled AND secret_ciphertext IS NOT NULL AND"
                + " (next_poll_at IS NULL OR next_poll_at <= :now) ORDER BY id")
        .param("now", Timestamp.from(now))
        .query(MailboxRepository::map)
        .list();
  }

  /**
   * Addresses of every configured mailbox (enabled first, then by id). The first is the support
   * address used for Reply-To on outbound ticket mail.
   */
  public List<String> addresses(AccessScope scope) {
    // Supporters too: the support address goes into the Reply-To of their notification
    // emails; it is printed in every client email anyway. Customers still get nothing.
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT address FROM mailbox" + SENDING_ORDER).query(String.class).list();
  }

  /**
   * The mailbox that sends outbound mail: enabled, with SMTP settings and a secret, the lowest id.
   * SYSTEM only (the outbox worker).
   */
  public Optional<MailboxRow> sender(AccessScope scope) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql("SELECT " + COLUMNS + " FROM mailbox WHERE " + SENDS + " ORDER BY id LIMIT 1")
        .query(MailboxRepository::map)
        .optional();
  }

  /**
   * The outcome of the last outbox round (or Test connection) through this mailbox: a short
   * category, or null.
   */
  public void recordSmtpResult(AccessScope scope, long id, String error) {
    IntakeScopes.requireAdminOrSystem(scope);
    jdbc.sql(
            "UPDATE mailbox SET smtp_last_error = CAST(:error AS VARCHAR) WHERE id = :id AND"
                + " smtp_last_error IS DISTINCT FROM CAST(:error AS VARCHAR)")
        .param(
            "error", error == null ? null : error.length() > 200 ? error.substring(0, 200) : error)
        .param("id", id)
        .update();
  }

  public void recordSuccess(AccessScope scope, long id, Instant now) {
    IntakeScopes.requireSystem(scope);
    jdbc.sql(
            "UPDATE mailbox SET last_poll_at = :now, last_success_at = :now, last_error = NULL,"
                + " consecutive_failures = 0, next_poll_at = NULL WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Records a failed poll with a short category and the time of the next attempt. */
  public int recordFailure(
      AccessScope scope, long id, String category, Instant now, Instant nextPoll) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
            "UPDATE mailbox SET last_poll_at = :now, last_error = :error, consecutive_failures ="
                + " consecutive_failures + 1, next_poll_at = :next WHERE id = :id RETURNING"
                + " consecutive_failures")
        .param("now", Timestamp.from(now))
        .param("error", category.length() > 200 ? category.substring(0, 200) : category)
        .param("next", Timestamp.from(nextPoll))
        .param("id", id)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  /**
   * Records the starting position of a mailbox that has none: the folder's UIDVALIDITY and UIDNEXT,
   * so only mail with a UID at or above {@code startUid} is processed. Only when the mailbox still
   * has no position and still points at the server, account and folder the values were read from
   * ({@code read}), so a late writer never overwrites a newer position or a changed folder.
   *
   * @return false when the mailbox already had a position or its folder changed meanwhile
   */
  public boolean baseline(AccessScope scope, MailboxRow read, long uidValidity, long startUid) {
    IntakeScopes.requireAdminOrSystem(scope);
    return jdbc.sql(
                "UPDATE mailbox SET uid_validity = :v, last_uid = :last WHERE id = :id AND"
                    + " uid_validity IS NULL AND imap_host = :host AND imap_port = :port AND"
                    + " imap_folder = :folder AND username = :username")
            .param("v", uidValidity)
            .param("last", Math.max(0, startUid - 1))
            .param("id", read.id())
            .param("host", read.imapHost())
            .param("port", read.imapPort())
            .param("folder", read.imapFolder())
            .param("username", read.username())
            .update()
        == 1;
  }

  /**
   * A new UIDVALIDITY: start again at the folder's current UIDNEXT instead of re-reading the
   * folder, only if the stored UIDVALIDITY is still {@code oldValidity}.
   *
   * @return false when another writer changed the position first
   */
  public boolean rebaseline(
      AccessScope scope, long id, long oldValidity, long newValidity, long startUid) {
    IntakeScopes.requireSystem(scope);
    return jdbc.sql(
                "UPDATE mailbox SET uid_validity = :v, last_uid = :last WHERE id = :id AND"
                    + " uid_validity = :old")
            .param("v", newValidity)
            .param("last", Math.max(0, startUid - 1))
            .param("id", id)
            .param("old", oldValidity)
            .update()
        == 1;
  }

  /**
   * Moves the processed-UID mark forward, in the transaction that processed the mail. Only for the
   * UIDVALIDITY it was read under, and never backwards.
   */
  public void advanceUid(AccessScope scope, long id, long uidValidity, long uid) {
    IntakeScopes.requireSystem(scope);
    jdbc.sql(
            "UPDATE mailbox SET last_uid = GREATEST(last_uid, :uid) WHERE id = :id AND"
                + " uid_validity = :v")
        .param("uid", uid)
        .param("id", id)
        .param("v", uidValidity)
        .update();
  }

  private static MailboxRow map(ResultSet rs, int row) throws SQLException {
    return new MailboxRow(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("address"),
        rs.getBoolean("enabled"),
        rs.getString("imap_host"),
        rs.getInt("imap_port"),
        rs.getString("imap_security"),
        rs.getString("imap_folder"),
        rs.getString("auth_type"),
        rs.getString("username"),
        rs.getBoolean("secret_set"),
        rs.getString("oauth_token_url"),
        rs.getString("oauth_client_id"),
        rs.getString("oauth_scope"),
        rs.getString("trusted_authserv_ids"),
        rs.getObject("uid_validity", Long.class),
        rs.getLong("last_uid"),
        instant(rs, "last_poll_at"),
        instant(rs, "last_success_at"),
        rs.getString("last_error"),
        rs.getInt("consecutive_failures"),
        instant(rs, "next_poll_at"),
        rs.getString("smtp_host"),
        rs.getObject("smtp_port", Integer.class),
        rs.getString("smtp_security"),
        rs.getString("smtp_last_error"),
        rs.getBoolean("smtp_same_sign_in"),
        rs.getString("smtp_username"),
        rs.getBoolean("smtp_secret_set"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
