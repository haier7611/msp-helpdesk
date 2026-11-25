package com.ticketsystem.api.auth.entra;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.crypto.DataCipher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin - Sign-in - Microsoft. Off by default. The admin enters the application (client) ID, the
 * client secret (encrypted at rest with {@link DataCipher}, write-only: no method returns it
 * outside this package), the secret's expiry date and the MSP's own tenant ID. The switch can be
 * turned on only after a passed Test sign-in of the current details.
 *
 * <p>Changing the client ID or the MSP tenant turns Microsoft sign-in off (it must be tested
 * again). A new secret alone keeps it on - so rotating a secret before it expires causes no outage
 * - but marks the details as untested, and the admin screen asks for a Test sign-in. Step-up is
 * enforced on the endpoints; every change is audit-logged without secret values.
 *
 * <p>Secret expiry: a warning is due from 30 days before the expiry date (UTC dates). {@link
 * #dueExpiryWarning} and {@link #markExpiryWarningSent} are the hooks for the notifier: a warning
 * is due when none was sent since the secret or its date last changed, or the last one is a week
 * old.
 */
@Service
public class EntraSettingsService {

  /** Warn this many days before the secret expires. */
  public static final int WARNING_DAYS = 30;

  /** Repeat the warning this often while it stays unresolved. */
  static final Duration WARNING_REPEAT = Duration.ofDays(7);

  /** Entra client secrets last at most 24 months; refuse dates far beyond that. */
  static final int MAX_EXPIRY_YEARS = 3;

  public static final String SECRET_AAD = "entra_settings:client_secret";

  /** Secret expiry state for the admin banner and the notifier. */
  public enum ExpiryState {
    /** No secret or no date. */
    NONE,
    OK,
    /** Expires within {@link #WARNING_DAYS} days. */
    SOON,
    EXPIRED
  }

  public record SecretExpiry(ExpiryState state, LocalDate expiresOn, Long daysLeft) {}

  /** What the admin screen shows. Never the secret. */
  public record SettingsView(
      boolean enabled,
      String clientId,
      String mspTenantId,
      boolean secretSet,
      LocalDate secretExpiresOn,
      boolean tested,
      Instant testedAt,
      SecretExpiry secretExpiry) {}

  /** Working details for a sign-in, link or test. Package-private: carries the secret. */
  record Config(
      boolean enabled, String clientId, UUID mspTenantId, int settingsVersion, String secret) {
    @Override
    public String toString() {
      return "EntraConfig[enabled=" + enabled + ", version=" + settingsVersion + "]";
    }
  }

  private final EntraSettingsRepository repo;
  private final DataCipher cipher;
  private final AuditService audit;
  private final Clock clock;

  public EntraSettingsService(
      EntraSettingsRepository repo, DataCipher cipher, AuditService audit, Clock clock) {
    this.repo = repo;
    this.cipher = cipher;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SettingsView view() {
    EntraSettingsRepository.Row row = repo.get();
    return new SettingsView(
        row.enabled(),
        row.clientId() == null ? null : row.clientId().toString(),
        row.mspTenantId() == null ? null : row.mspTenantId().toString(),
        row.secret() != null,
        row.secretExpiresOn(),
        row.tested(),
        row.testedAt(),
        expiry(row));
  }

  /** True while Microsoft sign-in is switched on (the sign-in page shows the button). */
  @Transactional(readOnly = true)
  public boolean enabled() {
    return repo.get().enabled();
  }

  /** The MSP's own tenant, when configured (supporters' allowed tenant, MFA-claim tenant). */
  @Transactional(readOnly = true)
  public Optional<UUID> mspTenant() {
    return Optional.ofNullable(repo.get().mspTenantId());
  }

  /**
   * Saves the app details. {@code secret} null or blank keeps the stored secret (it must exist
   * already). Returns the new view.
   */
  @Transactional
  public SettingsView update(
      long adminId,
      String rawClientId,
      String secret,
      LocalDate secretExpiresOn,
      String rawMspTenantId,
      String clientIp) {
    UUID clientId = guid(rawClientId, "The application (client) ID");
    UUID tenant = guid(rawMspTenantId, "The MSP tenant ID");
    boolean newSecret = secret != null && !secret.isBlank();
    if (newSecret) {
      requireSecretShape(secret);
    }
    EntraSettingsRepository.Row row = repo.getForUpdate();
    if (!newSecret && row.secret() == null) {
      throw ApiException.invalidRequest("Enter the client secret.");
    }
    if (secretExpiresOn == null) {
      throw ApiException.invalidRequest("Enter the date the client secret expires.");
    }
    LocalDate today = today();
    if (newSecret && secretExpiresOn.isBefore(today)) {
      throw ApiException.invalidRequest("That expiry date has already passed.");
    }
    if (secretExpiresOn.isAfter(today.plusYears(MAX_EXPIRY_YEARS))) {
      throw ApiException.invalidRequest(
          "Microsoft client secrets last at most 24 months. Check the expiry date.");
    }
    boolean clientChanged = !clientId.equals(row.clientId());
    boolean tenantChanged = !tenant.equals(row.mspTenantId());
    boolean expiryChanged = !Objects.equals(secretExpiresOn, row.secretExpiresOn());
    List<String> changed = new ArrayList<>();
    if (clientChanged) changed.add("client_id");
    if (newSecret) changed.add("secret");
    if (tenantChanged) changed.add("msp_tenant");
    if (expiryChanged) changed.add("secret_expiry");
    if (changed.isEmpty()) {
      return view();
    }
    DataCipher.Sealed sealed = null;
    if (newSecret) {
      byte[] plain = secret.strip().getBytes(StandardCharsets.UTF_8);
      try {
        sealed = cipher.encrypt(plain, SECRET_AAD);
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    }
    boolean disable = row.enabled() && (clientChanged || tenantChanged);
    repo.saveDetails(
        clientId,
        tenant,
        sealed,
        secretExpiresOn,
        newSecret || expiryChanged,
        clientChanged || tenantChanged || newSecret,
        disable,
        adminId,
        clock.instant());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("changed", String.join(",", changed));
    details.put("tenant", tenant.toString());
    audit.record(EntraAuditEvent.ENTRA_SETTINGS_CHANGED, adminId, null, clientIp, details);
    if (disable) {
      audit.record(
          EntraAuditEvent.ENTRA_DISABLED,
          adminId,
          null,
          clientIp,
          Map.of("reason", "settings_changed"));
    }
    return view();
  }

  /** The switch. Turning it on needs complete details that passed Test sign-in. */
  @Transactional
  public SettingsView setEnabled(long adminId, boolean enabled, String clientIp) {
    EntraSettingsRepository.Row row = repo.getForUpdate();
    if (enabled == row.enabled()) {
      return view();
    }
    if (enabled) {
      if (!row.complete()) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            "microsoft_incomplete",
            "Enter the application ID, client secret and MSP tenant ID first.");
      }
      if (!row.tested()) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            "microsoft_test_required",
            "Run Test sign-in with these details before turning Microsoft sign-in on.");
      }
    }
    repo.setEnabled(enabled, adminId, clock.instant());
    audit.record(
        enabled ? EntraAuditEvent.ENTRA_ENABLED : EntraAuditEvent.ENTRA_DISABLED,
        adminId,
        null,
        clientIp,
        Map.of("reason", "admin"));
    return view();
  }

  /** The live details, only while switched on. */
  @Transactional(readOnly = true)
  Optional<Config> activeConfig() {
    EntraSettingsRepository.Row row = repo.get();
    return row.enabled() && row.complete() ? Optional.of(config(row)) : Optional.empty();
  }

  /** The current details for Test sign-in, whether or not switched on. */
  @Transactional(readOnly = true)
  Optional<Config> configForTest() {
    EntraSettingsRepository.Row row = repo.get();
    return row.complete() ? Optional.of(config(row)) : Optional.empty();
  }

  /** A passed Test sign-in of {@code settingsVersion}; false if the details changed meanwhile. */
  @Transactional
  boolean recordTestPassed(int settingsVersion, long adminId, String clientIp) {
    boolean recorded = repo.markTested(settingsVersion, clock.instant());
    audit.record(
        recorded ? EntraAuditEvent.ENTRA_TEST_SUCCEEDED : EntraAuditEvent.ENTRA_TEST_FAILED,
        adminId,
        null,
        clientIp,
        recorded ? Map.of() : Map.of("reason", "settings_changed"));
    return recorded;
  }

  @Transactional
  void recordTestFailed(long adminId, String reason, String errorCode, String clientIp) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("reason", reason);
    details.put("error_code", errorCode);
    audit.record(EntraAuditEvent.ENTRA_TEST_FAILED, adminId, null, clientIp, details);
  }

  /** The current secret expiry state (admin banner). */
  @Transactional(readOnly = true)
  public SecretExpiry secretExpiry() {
    return expiry(repo.get());
  }

  /**
   * For the notifier: the expiry state when a warning should be sent to admins now, else empty.
   * Only while the details are complete (a secret exists).
   */
  @Transactional(readOnly = true)
  public Optional<SecretExpiry> dueExpiryWarning() {
    EntraSettingsRepository.Row row = repo.get();
    SecretExpiry e = expiry(row);
    if (!row.complete() || (e.state() != ExpiryState.SOON && e.state() != ExpiryState.EXPIRED)) {
      return Optional.empty();
    }
    Instant last = row.expiryWarningSentAt();
    if (last != null && last.plus(WARNING_REPEAT).isAfter(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(e);
  }

  /** The notifier calls this after queueing the warning. */
  @Transactional
  public void markExpiryWarningSent() {
    repo.markExpiryWarningSent(clock.instant());
  }

  private SecretExpiry expiry(EntraSettingsRepository.Row row) {
    if (row.secret() == null || row.secretExpiresOn() == null) {
      return new SecretExpiry(ExpiryState.NONE, row.secretExpiresOn(), null);
    }
    long days = ChronoUnit.DAYS.between(today(), row.secretExpiresOn());
    ExpiryState state =
        days < 0 ? ExpiryState.EXPIRED : days <= WARNING_DAYS ? ExpiryState.SOON : ExpiryState.OK;
    return new SecretExpiry(state, row.secretExpiresOn(), days);
  }

  private Config config(EntraSettingsRepository.Row row) {
    byte[] plain = cipher.decrypt(row.secret(), SECRET_AAD);
    try {
      return new Config(
          row.enabled(),
          row.clientId().toString(),
          row.mspTenantId(),
          row.settingsVersion(),
          new String(plain, StandardCharsets.UTF_8));
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  /** A tenant or client ID: a GUID. */
  static UUID guid(String raw, String what) {
    String s = raw == null ? "" : raw.strip();
    if (!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      throw ApiException.invalidRequest(
          what + " is a GUID such as 00000000-0000-0000-0000-000000000000.");
    }
    return UUID.fromString(s);
  }

  private static void requireSecretShape(String secret) {
    String s = secret.strip();
    if (s.length() < 8 || s.length() > 256 || !s.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
      // Never echo the value.
      throw ApiException.invalidRequest(
          "That does not look like a client secret. Copy the secret Value (not the Secret ID)"
              + " from the Entra portal.");
    }
  }
}
