package com.ticketsystem.api.mail.intake;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.crypto.DataCipher;
import com.ticketsystem.api.mail.MailAuditEvent;
import com.ticketsystem.api.mail.MailKind;
import com.ticketsystem.api.mail.MailOutbox;
import com.ticketsystem.api.mail.MailSettings;
import com.ticketsystem.api.mail.OutboundEmail;
import com.ticketsystem.api.mail.SmtpAccount;
import com.ticketsystem.api.mail.SmtpConnectionCheck;
import com.ticketsystem.api.mail.inbound.AuthenticationResultsParser;
import com.ticketsystem.api.mail.intake.clientdata.IntakeStateRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository.MailboxRow;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository.MailboxValues;
import com.ticketsystem.api.mail.intake.imap.ImapMailboxClient;
import com.ticketsystem.api.mail.intake.imap.MailboxAccessException;
import com.ticketsystem.api.mail.intake.imap.MailboxAccessException.Category;
import com.ticketsystem.api.mail.intake.imap.OAuth2TokenClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin-configured mailboxes: any IMAP provider with password or OAuth2 client-credentials
 * (XOAUTH2) login, credentials encrypted at rest with {@link DataCipher} and write-only through the
 * API, and the test connection (log in, open the folder, then a probe mail to {@code
 * support+internal@} that must arrive).
 */
@Service
public class MailboxService {

  /** Header carrying a test-connection token (only its SHA-256 is stored). */
  public static final String PROBE_HEADER = "X-Ticket-System-Probe";

  static final Duration PROBE_LIFETIME = Duration.ofMinutes(30);
  static final String M365_SCOPE = "https://outlook.office365.com/.default";

  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]_-]{1,253}");
  private static final Pattern ADDRESS =
      Pattern.compile("[a-z0-9._%-]{1,64}@[a-z0-9.-]{1,253}\\.[a-z]{2,63}");
  private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9.-]{1,100}");
  private static final Pattern AUTHSERV = Pattern.compile("[A-Za-z0-9.()_-]{1,253}");
  private static final SecureRandom RANDOM = new SecureRandom();

  /** What the admin sends. {@code secret} null keeps the stored one; it is never returned. */
  public record MailboxRequest(
      String name,
      String address,
      boolean enabled,
      String imapHost,
      Integer imapPort,
      String imapSecurity,
      String imapFolder,
      String authType,
      String username,
      String secret,
      String oauthTenantId,
      String oauthTokenUrl,
      String oauthClientId,
      String oauthScope,
      List<String> trustedAuthservIds,
      String smtpHost,
      Integer smtpPort,
      String smtpSecurity,
      Boolean smtpSameSignIn,
      String smtpUsername,
      String smtpSecret) {

    /** SMTP with the same sign-in as incoming. */
    public MailboxRequest(
        String name,
        String address,
        boolean enabled,
        String imapHost,
        Integer imapPort,
        String imapSecurity,
        String imapFolder,
        String authType,
        String username,
        String secret,
        String oauthTenantId,
        String oauthTokenUrl,
        String oauthClientId,
        String oauthScope,
        List<String> trustedAuthservIds,
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
          secret,
          oauthTenantId,
          oauthTokenUrl,
          oauthClientId,
          oauthScope,
          trustedAuthservIds,
          smtpHost,
          smtpPort,
          smtpSecurity,
          null,
          null,
          null);
    }

    /** Without SMTP settings (receive only). */
    public MailboxRequest(
        String name,
        String address,
        boolean enabled,
        String imapHost,
        Integer imapPort,
        String imapSecurity,
        String imapFolder,
        String authType,
        String username,
        String secret,
        String oauthTenantId,
        String oauthTokenUrl,
        String oauthClientId,
        String oauthScope,
        List<String> trustedAuthservIds) {
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
          secret,
          oauthTenantId,
          oauthTokenUrl,
          oauthClientId,
          oauthScope,
          trustedAuthservIds,
          null,
          null,
          null);
    }

    /** Never prints a secret, only whether one was sent. */
    @Override
    public String toString() {
      return "MailboxRequest[secret="
          + (secret == null ? "unchanged" : "(set)")
          + ", smtpSecret="
          + (smtpSecret == null ? "unchanged" : "(set)")
          + "]";
    }
  }

  /** What the admin sees: settings, status, never the secret. */
  public record MailboxView(
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
      List<String> trustedAuthservIds,
      Instant lastPollAt,
      Instant lastSuccessAt,
      String lastError,
      int consecutiveFailures,
      Instant nextPollAt,
      String smtpHost,
      Integer smtpPort,
      String smtpSecurity,
      boolean sending,
      String smtpLastError,
      boolean smtpSameSignIn,
      String smtpUsername,
      boolean smtpSecretSet) {}

  /** Test connection result: {@code step} is where it stopped; {@code probeId} to poll. */
  public record TestResult(boolean loginOk, String error, Long probeId, String message) {}

  /** Probe status: {@code arrived} once the mail reached {@code support+internal@}. */
  public record ProbeStatus(boolean arrived, boolean expired, String message) {}

  private final MailboxRepository mailboxes;
  private final IntakeStateRepository state;
  private final DataCipher cipher;
  private final OAuth2TokenClient tokens;
  private final MailOutbox outbox;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final RateLimiter limiter;
  private final SmtpConnectionCheck smtpCheck;

  public MailboxService(
      MailboxRepository mailboxes,
      IntakeStateRepository state,
      DataCipher cipher,
      OAuth2TokenClient tokens,
      MailOutbox outbox,
      AuditService audit,
      Clock clock,
      TransactionTemplate tx,
      RateLimiter limiter,
      SmtpConnectionCheck smtpCheck) {
    this.tx = tx;
    this.smtpCheck = smtpCheck;
    this.limiter = limiter;
    this.mailboxes = mailboxes;
    this.state = state;
    this.cipher = cipher;
    this.tokens = tokens;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<MailboxView> list(AccessScope scope) {
    scope.requireAdmin();
    return mailboxes.list(scope).stream().map(MailboxService::view).toList();
  }

  @Transactional(readOnly = true)
  public MailboxView get(AccessScope scope, long id) {
    scope.requireAdmin();
    return mailboxes.find(scope, id).map(MailboxService::view).orElseThrow(AccessScope::notFound);
  }

  /**
   * Saves a new mailbox. Not {@code @Transactional}: the row commits first, then {@link
   * #recordStartingPosition} connects to the server outside any transaction.
   */
  public MailboxView create(AccessScope scope, MailboxRequest r, String clientIp) {
    scope.requireAdmin();
    MailboxValues v = validate(r);
    if (r.secret() == null || r.secret().isBlank()) {
      throw ApiException.invalidRequest("Enter the password or client secret.");
    }
    Instant now = clock.instant();
    Long id =
        tx.execute(
            status -> {
              long created;
              try {
                created = mailboxes.insert(scope, v, now);
              } catch (org.springframework.dao.DuplicateKeyException e) {
                throw ApiException.invalidRequest("A mailbox with this address exists already.");
              }
              mailboxes.setSecret(scope, created, seal(created, r.secret()), now);
              boolean smtpSecretChanged = saveSmtpSecret(scope, created, v, r, false, now);
              auditSaved(
                  scope,
                  created,
                  MailAuditEvent.MAILBOX_SAVED,
                  clientIp,
                  true,
                  "",
                  v.trustedAuthservIds());
              auditSmtp(scope, created, null, v, smtpSecretChanged, clientIp);
              return created;
            });
    recordStartingPosition(scope, id);
    return get(scope, id);
  }

  /**
   * Saves a mailbox's settings. Not {@code @Transactional}, like {@link #create}: a changed server,
   * account or folder clears the starting position, and polling switched on with no position yet
   * records one after the commit.
   */
  public MailboxView update(AccessScope scope, long id, MailboxRequest r, String clientIp) {
    scope.requireAdmin();
    MailboxValues v = validate(r);
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          MailboxRow before = mailboxes.find(scope, id).orElseThrow(AccessScope::notFound);
          try {
            mailboxes.update(scope, id, v, now);
          } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.invalidRequest("A mailbox with this address exists already.");
          }
          boolean secretChanged = r.secret() != null && !r.secret().isBlank();
          if (secretChanged) {
            mailboxes.setSecret(scope, id, seal(id, r.secret()), now);
          }
          boolean smtpSecretChanged =
              saveSmtpSecret(
                  scope, id, v, r, !before.smtpSameSignIn() && before.smtpSecretSet(), now);
          auditSaved(
              scope,
              id,
              MailAuditEvent.MAILBOX_SAVED,
              clientIp,
              secretChanged,
              before.trustedAuthservIds(),
              v.trustedAuthservIds());
          // The same sign-in follows the incoming secret: a new one changes the SMTP sign-in too.
          auditSmtp(
              scope,
              id,
              before,
              v,
              smtpSecretChanged || (v.smtpHost() != null && v.smtpSameSignIn() && secretChanged),
              clientIp);
        });
    recordStartingPosition(scope, id);
    return get(scope, id);
  }

  /**
   * An enabled mailbox with no starting position (just created, polling switched on for the first
   * time, or its server, account or folder changed) records the folder's UIDVALIDITY and UIDNEXT
   * now, so only mail that arrives after the save becomes a ticket. Best effort: when the server
   * cannot be reached or the login fails, the poller records the position on its first successful
   * connection instead. A disabled mailbox gets none, so switching polling on later starts from
   * that moment; once recorded, switching polling off and on keeps the position.
   *
   * @return true when a position was recorded now
   */
  boolean recordStartingPosition(AccessScope scope, long id) {
    MailboxRow m = mailboxes.find(scope, id).orElse(null);
    if (m == null || !m.enabled() || !m.secretSet() || m.uidValidity() != null) {
      return false;
    }
    try (ImapMailboxClient client = open(scope, m)) {
      return mailboxes.baseline(scope, m, client.uidValidity(), client.uidNext());
    } catch (MailboxAccessException | RuntimeException e) {
      // Left to the poller; the poll records the failure where the admin sees it.
      return false;
    }
  }

  @Transactional
  public void delete(AccessScope scope, long id, String clientIp) {
    scope.requireAdmin();
    if (!mailboxes.delete(scope, id)) {
      throw AccessScope.notFound();
    }
    auditSaved(scope, id, MailAuditEvent.MAILBOX_DELETED, clientIp, false, null, null);
  }

  /**
   * Test connection: logs in and opens the folder; on success queues a probe mail to {@code
   * support+internal@} whose arrival {@link #probeStatus} reports (plus-addressing check).
   *
   * <p>Deliberately not {@code @Transactional}: the IMAP connect can take 15 s plus a 60 s read
   * timeout and must not hold a database connection meanwhile. Only the probe row and its outbox
   * mail are written, together, in a short transaction afterwards.
   */
  public TestResult test(AccessScope scope, long id) {
    scope.requireAdmin();
    MailboxRow m = mailboxes.find(scope, id).orElseThrow(AccessScope::notFound);
    // M1: each test is an IMAP login to an admin-chosen host plus a queued probe
    // mail, so cap it per mailbox (1 per 60 s) and per admin (10 per hour) before any I/O; over
    // the cap answers 429 and nothing is contacted or queued.
    limiter.consume(RateLimiter.Scope.MAILBOX_TEST_MAILBOX, Long.toString(id));
    limiter.consume(
        RateLimiter.Scope.MAILBOX_TEST_ADMIN, String.valueOf(scope.principalId().orElse(null)));
    try (ImapMailboxClient client = open(scope, m)) {
      client.uidValidity();
    } catch (MailboxAccessException e) {
      return new TestResult(false, e.category().label(), null, message(e.category()));
    }
    // With outgoing settings, sign in to SMTP too (connect, TLS, AUTH, QUIT; no message).
    // The probe below then goes out through this mailbox, so a failed sign-in queues nothing.
    boolean smtp = m.smtpHost() != null;
    if (smtp) {
      SmtpConnectionCheck.Result check;
      try {
        check = smtpCheck.check(smtpAccount(scope, m));
      } catch (MailboxAccessException e) {
        mailboxes.recordSmtpResult(scope, id, e.category().label());
        return new TestResult(false, e.category().label(), null, message(e.category()));
      }
      mailboxes.recordSmtpResult(scope, id, check.error());
      if (!check.ok()) {
        return new TestResult(
            false,
            check.error(),
            null,
            check.signInFailed()
                ? "Signed in to IMAP, but the SMTP sign-in failed: check the outgoing user name"
                    + " and password (Gmail needs an app password)."
                : "Signed in to IMAP, but could not connect to the SMTP server: check the"
                    + " outgoing server, port and security.");
      }
    }
    byte[] raw = new byte[24];
    RANDOM.nextBytes(raw);
    String token = HexFormat.of().formatHex(raw);
    Instant now = clock.instant();
    int at = m.address().lastIndexOf('@');
    String internal = m.address().substring(0, at) + "+internal" + m.address().substring(at);
    Long probeId =
        tx.execute(
            status -> {
              long probe =
                  state.insertProbe(scope, id, sha256(token), now, now.plus(PROBE_LIFETIME));
              outbox.enqueue(
                  // Agent-only ticket mail: it goes through this mailbox's SMTP when
                  // set, which is what the probe must prove.
                  OutboundEmail.builder(MailKind.INTERNAL)
                      .to(internal)
                      .subject("Mailbox test")
                      .body(
                          "<p>This is a test of the support mailbox. It needs no action.</p>",
                          "This is a test of the support mailbox. It needs no action.")
                      .header(PROBE_HEADER, token)
                      .build());
              return probe;
            });
    return new TestResult(
        true,
        null,
        probeId,
        smtp
            ? "Signed in to IMAP and SMTP. A test email was sent through this mailbox to the"
                + " internal address; check back in a minute."
            : "Signed in. A test email was sent to the internal address; check back in a minute.");
  }

  @Transactional(readOnly = true)
  public ProbeStatus probeStatus(AccessScope scope, long id, long probeId) {
    scope.requireAdmin();
    IntakeStateRepository.ProbeRow p =
        state.findProbe(scope, id, probeId).orElseThrow(AccessScope::notFound);
    if (p.seenAt() != null) {
      return new ProbeStatus(true, false, "The test email arrived at the internal address.");
    }
    if (clock.instant().isAfter(p.expiresAt())) {
      return new ProbeStatus(
          false,
          true,
          "The test email did not arrive. Check that plus-addressing (support+internal@) is"
              + " enabled on the mailbox.");
    }
    return new ProbeStatus(false, false, "Waiting for the test email.");
  }

  /**
   * Opens an IMAP session with the stored secret (and an OAuth2 token if needed). A secret that
   * cannot be decrypted (e.g. its data key was retired) is a {@code SECRET_UNREADABLE} failure of
   * this mailbox only, never an exception that stops the poller.
   */
  public ImapMailboxClient open(AccessScope scope, MailboxRow m) throws MailboxAccessException {
    DataCipher.Sealed sealed =
        mailboxes
            .secret(scope, m.id())
            .orElseThrow(() -> new MailboxAccessException(Category.NOT_CONFIGURED));
    String secret;
    try {
      secret = new String(cipher.decrypt(sealed, aad(m.id())), StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw new MailboxAccessException(Category.SECRET_UNREADABLE);
    }
    ImapMailboxClient.Connection c =
        new ImapMailboxClient.Connection(
            m.imapHost(),
            m.imapPort(),
            m.imapSecurity(),
            m.imapFolder(),
            m.username(),
            "OAUTH2".equals(m.authType()));
    if (!c.oauth2()) {
      return ImapMailboxClient.open(c, secret);
    }
    String token = tokens.token(m.oauthTokenUrl(), m.oauthClientId(), secret, m.oauthScope());
    try {
      return ImapMailboxClient.open(c, token);
    } catch (MailboxAccessException e) {
      if (e.category() == Category.LOGIN_FAILED) {
        // An expired or revoked token: fetch a fresh one next time.
        tokens.invalidate(m.oauthTokenUrl(), m.oauthClientId(), secret, m.oauthScope());
      }
      throw e;
    }
  }

  /**
   * The SMTP account of a mailbox with outgoing settings: From is the support address; the sign-in
   * is the incoming one (password, or XOAUTH2) or the separate SMTP user name and password. Never
   * logged; the caller holds it for one outbox round or one test only.
   */
  public SmtpAccount smtpAccount(AccessScope scope, MailboxRow m) throws MailboxAccessException {
    if (m.smtpHost() == null) {
      throw new MailboxAccessException(Category.NOT_CONFIGURED);
    }
    MailSettings.Security security = MailSettings.Security.valueOf(m.smtpSecurity());
    if (!m.smtpSameSignIn()) {
      DataCipher.Sealed sealed =
          mailboxes
              .smtpSecret(scope, m.id())
              .orElseThrow(() -> new MailboxAccessException(Category.NOT_CONFIGURED));
      String password;
      try {
        password = new String(cipher.decrypt(sealed, smtpAad(m.id())), StandardCharsets.UTF_8);
      } catch (RuntimeException e) {
        throw new MailboxAccessException(Category.SECRET_UNREADABLE);
      }
      return new SmtpAccount(
          m.id(),
          m.address(),
          m.smtpHost(),
          m.smtpPort(),
          security,
          m.smtpUsername(),
          password,
          false);
    }
    return new SmtpAccount(
        m.id(),
        m.address(),
        m.smtpHost(),
        m.smtpPort(),
        security,
        m.username(),
        smtpSecret(scope, m),
        "OAUTH2".equals(m.authType()));
  }

  /**
   * What the SMTP login with the same sign-in as incoming uses: the decrypted password, or a fresh
   * OAuth2 access token for XOAUTH2 (client credentials, cached by {@link OAuth2TokenClient}).
   * Never logged.
   */
  private String smtpSecret(AccessScope scope, MailboxRow m) throws MailboxAccessException {
    DataCipher.Sealed sealed =
        mailboxes
            .secret(scope, m.id())
            .orElseThrow(() -> new MailboxAccessException(Category.NOT_CONFIGURED));
    String secret;
    try {
      secret = new String(cipher.decrypt(sealed, aad(m.id())), StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw new MailboxAccessException(Category.SECRET_UNREADABLE);
    }
    if (!"OAUTH2".equals(m.authType())) {
      return secret;
    }
    return tokens.token(m.oauthTokenUrl(), m.oauthClientId(), secret, m.oauthScope());
  }

  /**
   * The recommended trusted authserv-id for a well-known IMAP server, or null. Gmail stamps {@code
   * mx.google.com}; Microsoft 365 (Exchange Online) writes no authserv-id, which the parser matches
   * as {@link AuthenticationResultsParser#NO_AUTHSERV_ID}. Exact host names only; the web form
   * (mailboxDefaults.ts) suggests the same values.
   */
  static String defaultTrustedAuthservId(String imapHost) {
    if (imapHost == null) {
      return null;
    }
    String host = imapHost.strip().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return switch (host) {
      case "imap.gmail.com", "imap.googlemail.com" -> "mx.google.com";
      case "outlook.office365.com" -> AuthenticationResultsParser.NO_AUTHSERV_ID;
      default -> null;
    };
  }

  /** The trusted authserv-ids of a mailbox as a set. */
  public static Set<String> trustedIds(MailboxRow m) {
    return Arrays.stream(m.trustedAuthservIds().split(","))
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String message(Category c) {
    return switch (c) {
      case LOGIN_FAILED -> "Sign-in failed: check the user name and password or OAuth2 settings.";
      case TOKEN_REQUEST_FAILED ->
          "The OAuth2 token request failed: check the tenant, client id and client secret.";
      case FOLDER_NOT_FOUND -> "The folder was not found in the mailbox.";
      case NOT_CONFIGURED ->
          "The mailbox settings are incomplete, or plain-text IMAP was chosen for a remote server.";
      case SECRET_UNREADABLE ->
          "The stored password or client secret could not be read: enter it again.";
      default -> "Could not connect to the mail server: check the host, port and security.";
    };
  }

  private DataCipher.Sealed seal(long id, String secret) {
    return cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8), aad(id));
  }

  public static String aad(long id) {
    return "mailbox-secret:" + id;
  }

  /**
   * Associated data of the separate SMTP password: different from {@link #aad}, so one ciphertext
   * can never be swapped in for the other.
   */
  public static String smtpAad(long id) {
    return "mailbox-smtp-secret:" + id;
  }

  /**
   * Stores (or keeps) the separate SMTP password; returns whether a new one was stored. A separate
   * sign-in needs a password: on create, or when switching from the same sign-in, it must be sent.
   */
  private boolean saveSmtpSecret(
      AccessScope scope,
      long id,
      MailboxValues v,
      MailboxRequest r,
      boolean storedBefore,
      Instant now) {
    if (v.smtpHost() == null || v.smtpSameSignIn()) {
      return false; // the repository has cleared any separate password
    }
    boolean given = r.smtpSecret() != null && !r.smtpSecret().isBlank();
    if (!given && !storedBefore) {
      throw ApiException.invalidRequest("Enter the SMTP password.");
    }
    if (given) {
      mailboxes.setSmtpSecret(
          scope,
          id,
          cipher.encrypt(r.smtpSecret().getBytes(StandardCharsets.UTF_8), smtpAad(id)),
          now);
    }
    return given;
  }

  /**
   * Audit of the outgoing settings: set, changed (which parts) or removed. Details carry the
   * mailbox id, the security, same/separate sign-in and the names of what changed - never a host,
   * user name or secret.
   */
  private void auditSmtp(
      AccessScope scope,
      long id,
      MailboxRow before,
      MailboxValues after,
      boolean secretChanged,
      String clientIp) {
    boolean had = before != null && before.smtpHost() != null;
    boolean has = after.smtpHost() != null;
    if (!had && !has) {
      return;
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("mailbox", id);
    AuditEvent type;
    if (!had) {
      type = MailAuditEvent.MAILBOX_SMTP_SET;
      d.put("smtp_security", after.smtpSecurity());
      d.put("smtp_sign_in", after.smtpSameSignIn() ? "same" : "separate");
    } else if (!has) {
      type = MailAuditEvent.MAILBOX_SMTP_REMOVED;
    } else {
      List<String> changes = new ArrayList<>();
      if (!Objects.equals(before.smtpHost(), after.smtpHost())
          || !Objects.equals(before.smtpPort(), after.smtpPort())) {
        changes.add("server");
      }
      if (!Objects.equals(before.smtpSecurity(), after.smtpSecurity())) {
        changes.add("security");
      }
      if (before.smtpSameSignIn() != after.smtpSameSignIn()
          || !Objects.equals(before.smtpUsername(), after.smtpUsername())) {
        changes.add("sign-in");
      }
      if (secretChanged) {
        changes.add("password");
      }
      if (changes.isEmpty()) {
        return;
      }
      type = MailAuditEvent.MAILBOX_SMTP_CHANGED;
      d.put("smtp_security", after.smtpSecurity());
      d.put("smtp_sign_in", after.smtpSameSignIn() ? "same" : "separate");
      d.put("fields", String.join(",", changes));
    }
    audit.record(type, scope.principalId().orElse(null), null, clientIp, d);
  }

  /**
   * A changed trusted authserv-id list is recorded with its old and new values (receiving server
   * host names or "(none)", comma-separated; not secrets). Values over the audit value cap (200
   * characters) are cut there.
   */
  private void auditSaved(
      AccessScope scope,
      long id,
      AuditEvent type,
      String clientIp,
      boolean secretChanged,
      String trustedBefore,
      String trustedAfter) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("mailbox", id);
    if (secretChanged) {
      d.put("reason", "credentials changed");
    }
    String oldIds = trustedBefore == null ? "" : trustedBefore;
    String newIds = trustedAfter == null ? "" : trustedAfter;
    if (trustedAfter != null && !oldIds.equals(newIds)) {
      d.put("trusted_authserv_before", oldIds);
      d.put("trusted_authserv_after", newIds);
    }
    audit.record(type, scope.principalId().orElse(null), null, clientIp, d);
  }

  static MailboxValues validate(MailboxRequest r) {
    String name = trim(r.name());
    if (name == null || name.length() > 100) {
      throw ApiException.invalidRequest("Enter a name of up to 100 characters.");
    }
    String address = trim(r.address());
    address = address == null ? null : address.toLowerCase(Locale.ROOT);
    if (address == null || !ADDRESS.matcher(address).matches()) {
      throw ApiException.invalidRequest(
          "Enter the support address as name@domain, without a plus tag.");
    }
    String host = trim(r.imapHost());
    if (host == null || !HOST.matcher(host).matches()) {
      throw ApiException.invalidRequest("Enter the IMAP server name.");
    }
    String security =
        r.imapSecurity() == null ? "TLS" : r.imapSecurity().strip().toUpperCase(Locale.ROOT);
    if (!Set.of("TLS", "STARTTLS", "NONE").contains(security)) {
      throw ApiException.invalidRequest("Security must be TLS, STARTTLS or NONE.");
    }
    if ("NONE".equals(security)
        && !Set.of("127.0.0.1", "localhost", "::1").contains(host.toLowerCase(Locale.ROOT))) {
      throw ApiException.invalidRequest(
          "Plain-text IMAP is only allowed to this server itself. Use TLS or STARTTLS.");
    }
    int port = r.imapPort() == null ? ("TLS".equals(security) ? 993 : 143) : r.imapPort();
    if (port < 1 || port > 65535) {
      throw ApiException.invalidRequest("Enter a port between 1 and 65535.");
    }
    String folder = trim(r.imapFolder());
    folder = folder == null ? "INBOX" : folder;
    if (folder.length() > 200 || folder.chars().anyMatch(ch -> ch < 0x20)) {
      throw ApiException.invalidRequest("Enter a valid folder name.");
    }
    String auth = r.authType() == null ? "PASSWORD" : r.authType().strip().toUpperCase(Locale.ROOT);
    if (!Set.of("PASSWORD", "OAUTH2").contains(auth)) {
      throw ApiException.invalidRequest("Sign-in must be PASSWORD or OAUTH2.");
    }
    String username = trim(r.username());
    if (username == null || username.length() > 320) {
      throw ApiException.invalidRequest("Enter the user name.");
    }
    if (r.secret() != null && r.secret().length() > 4000) {
      throw ApiException.invalidRequest("The secret is too long.");
    }
    String tokenUrl = null;
    String clientId = null;
    String oauthScope = null;
    if ("OAUTH2".equals(auth)) {
      String tenant = trim(r.oauthTenantId());
      tokenUrl = trim(r.oauthTokenUrl());
      if (tokenUrl == null && tenant != null) {
        if (!TENANT.matcher(tenant).matches()) {
          throw ApiException.invalidRequest("Enter a valid tenant id.");
        }
        tokenUrl = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
      }
      if (tokenUrl == null || !safeTokenUrl(tokenUrl)) {
        throw ApiException.invalidRequest("Enter the tenant id, or an https token URL.");
      }
      clientId = trim(r.oauthClientId());
      if (clientId == null || clientId.length() > 200) {
        throw ApiException.invalidRequest("Enter the application (client) id.");
      }
      oauthScope = trim(r.oauthScope());
      oauthScope = oauthScope == null ? M365_SCOPE : oauthScope;
      if (oauthScope.length() > 500) {
        throw ApiException.invalidRequest("The OAuth2 scope is too long.");
      }
    }
    Set<String> trusted = new LinkedHashSet<>();
    if (r.trustedAuthservIds() != null) {
      for (String t : r.trustedAuthservIds()) {
        String v = trim(t);
        if (v == null) {
          continue;
        }
        if (!AUTHSERV.matcher(v).matches()) {
          throw ApiException.invalidRequest(
              "Enter valid authserv-ids (the receiving server name).");
        }
        trusted.add(
            v.equalsIgnoreCase(AuthenticationResultsParser.NO_AUTHSERV_ID)
                ? AuthenticationResultsParser.NO_AUTHSERV_ID
                : v.toLowerCase(Locale.ROOT));
      }
    }
    if (trusted.size() > 10) {
      throw ApiException.invalidRequest("At most 10 authserv-ids.");
    }
    // An empty list trusts nothing, so every mail would go to triage as "not
    // authenticated". For Gmail and Microsoft 365 the right value is known; save it explicitly (it
    // comes back in the response like a typed one). Other servers stay empty: the admin must say.
    if (trusted.isEmpty()) {
      String suggested = defaultTrustedAuthservId(host);
      if (suggested != null) {
        trusted.add(suggested);
      }
    }
    // Outbound: optional SMTP server; the same sign-in as incoming (default) or
    // a separate user name and password. No server = the server's own mail settings.
    String smtpHost = trim(r.smtpHost());
    Integer smtpPort = null;
    String smtpSecurity = null;
    boolean smtpSame = true;
    String smtpUsername = null;
    if (smtpHost != null) {
      smtpSame = r.smtpSameSignIn() == null || r.smtpSameSignIn();
      if (!smtpSame) {
        smtpUsername = trim(r.smtpUsername());
        if (smtpUsername == null || smtpUsername.length() > 320) {
          throw ApiException.invalidRequest("Enter the SMTP user name.");
        }
      }
      if (r.smtpSecret() != null && r.smtpSecret().length() > 4000) {
        throw ApiException.invalidRequest("The SMTP password is too long.");
      }
      if (!HOST.matcher(smtpHost).matches()) {
        throw ApiException.invalidRequest("Enter the SMTP server name.");
      }
      smtpSecurity =
          r.smtpSecurity() == null ? "STARTTLS" : r.smtpSecurity().strip().toUpperCase(Locale.ROOT);
      if (!Set.of("TLS", "STARTTLS", "NONE").contains(smtpSecurity)) {
        throw ApiException.invalidRequest("SMTP security must be TLS, STARTTLS or NONE.");
      }
      if ("NONE".equals(smtpSecurity)
          && !Set.of("127.0.0.1", "localhost", "::1").contains(smtpHost.toLowerCase(Locale.ROOT))) {
        // Every SMTP sign-in has credentials (same or separate), so plain text would send the
        // password unencrypted: only to a local test server on this machine.
        throw ApiException.invalidRequest(
            "Plain-text SMTP would send the password unencrypted, so it is only allowed to this"
                + " server itself. Use TLS or STARTTLS.");
      }
      smtpPort =
          r.smtpPort() == null
              ? switch (smtpSecurity) {
                case "TLS" -> 465;
                case "STARTTLS" -> 587;
                default -> 25;
              }
              : r.smtpPort();
      if (smtpPort < 1 || smtpPort > 65535) {
        throw ApiException.invalidRequest("Enter an SMTP port between 1 and 65535.");
      }
    }
    return new MailboxValues(
        name,
        address,
        r.enabled(),
        host,
        port,
        security,
        folder,
        auth,
        username,
        tokenUrl,
        clientId,
        oauthScope,
        String.join(",", trusted),
        smtpHost,
        smtpPort,
        smtpSecurity,
        smtpSame,
        smtpUsername);
  }

  /** https, or http to a loopback test server only; no user info, fragment or odd characters. */
  static boolean safeTokenUrl(String url) {
    if (url.length() > 500) {
      return false;
    }
    try {
      URI u = new URI(url);
      String host = u.getHost();
      if (host == null || u.getUserInfo() != null || u.getFragment() != null) {
        return false;
      }
      if ("https".equalsIgnoreCase(u.getScheme())) {
        return true;
      }
      return "http".equalsIgnoreCase(u.getScheme())
          && Set.of("127.0.0.1", "localhost").contains(host.toLowerCase(Locale.ROOT));
    } catch (Exception e) {
      return false;
    }
  }

  private static MailboxView view(MailboxRow m) {
    return new MailboxView(
        m.id(),
        m.name(),
        m.address(),
        m.enabled(),
        m.imapHost(),
        m.imapPort(),
        m.imapSecurity(),
        m.imapFolder(),
        m.authType(),
        m.username(),
        m.secretSet(),
        m.oauthTokenUrl(),
        m.oauthClientId(),
        m.oauthScope(),
        trustedIds(m).stream().sorted().toList(),
        m.lastPollAt(),
        m.lastSuccessAt(),
        m.lastError(),
        m.consecutiveFailures(),
        m.nextPollAt(),
        m.smtpHost(),
        m.smtpPort(),
        m.smtpSecurity(),
        m.sends(),
        m.smtpLastError(),
        m.smtpSameSignIn(),
        m.smtpUsername(),
        m.smtpSecretSet());
  }

  private static String trim(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
