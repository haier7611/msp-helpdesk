package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.PasswordPolicy;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.device.DeviceService;
import com.ticketsystem.api.auth.mail.SecurityNotice;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import com.ticketsystem.api.auth.throttle.SignInBackoff;
import com.ticketsystem.api.auth.throttle.ThrottledException;
import com.ticketsystem.api.crypto.DataCipher;
import com.ticketsystem.api.mail.MailProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TOTP MFA for supporters (C2/C3): enrolment forced at first sign-in, the sign-in code, 10 one-time
 * recovery codes, step-up codes, re-enrolment, regenerated codes and the admin / break-glass reset.
 * Customers have no MFA in v1.
 *
 * <p>Every code check goes through {@link SignInBackoff} under the key {@code mfa:<user id>}: per
 * (user, address) and account-wide unless the browser is a known device of the user. A code is
 * accepted only for a time step later than the last accepted one (no replay), recorded with a
 * conditional UPDATE. Secrets are encrypted with {@link DataCipher} bound to the user id.
 */
@Service
public class MfaService {

  /** An unconfirmed enrolment must be confirmed within this time. */
  static final Duration PENDING_ENROLMENT_VALIDITY = Duration.ofMinutes(15);

  /** What the enrolment page shows: QR code and the same secret as a manual key. */
  public record Enrolment(String manualKey, String otpauthUri, QrCode qr) {
    @Override
    public String toString() {
      return "Enrolment[]";
    }
  }

  /** MFA state for My settings - Security. */
  public record Status(boolean enrolled, Instant enrolledAt, int recoveryCodesLeft) {}

  /**
   * Where the first factor (password or Microsoft) leaves a user. {@code viaExemption} is true when
   * the session is FULL only because of a two-step exemption: such a session is marked and ends on
   * its next request once the exemption has ended or been revoked.
   */
  public record FirstFactorLevel(AuthLevel level, boolean viaExemption) {}

  private final MfaRepository repo;
  private final UserRepository users;
  private final DataCipher cipher;
  private final SignInBackoff backoff;
  private final SecurityVersionService securityVersion;
  private final DeviceService devices;
  private final SystemMailer mailer;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String issuer;
  private final MfaExemptionService exemptions;
  private final PasswordEncoder encoder;

  public MfaService(
      MfaRepository repo,
      UserRepository users,
      DataCipher cipher,
      SignInBackoff backoff,
      SecurityVersionService securityVersion,
      DeviceService devices,
      SystemMailer mailer,
      AuditService audit,
      TransactionTemplate tx,
      Clock clock,
      MailProperties mail,
      MfaExemptionService exemptions,
      PasswordEncoder encoder) {
    this.repo = repo;
    this.users = users;
    this.cipher = cipher;
    this.backoff = backoff;
    this.securityVersion = securityVersion;
    this.devices = devices;
    this.mailer = mailer;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
    this.issuer = mail.supportName();
    this.exemptions = exemptions;
    this.encoder = encoder;
  }

  /**
   * Where a correct password leaves a user (see {@link #levelAfterFirstFactor}). {@code trustToken}
   * is the browser's trusted-device cookie ({@link DeviceService#presentedTrustToken}).
   */
  public FirstFactorLevel levelAfterPassword(AppUser user, String trustToken, String clientIp) {
    return levelAfterFirstFactor(user, trustToken, false, clientIp);
  }

  /**
   * Where a correct first factor leaves a user. Customers are fully signed in. A supporter whose
   * Microsoft token counts as MFA ({@code externalMfa}, MSP tenant only) or whose browser is a
   * trusted device (enrolled only) is fully signed in. Otherwise an engineer with a two-step
   * exemption that applies now is fully signed in, marked {@code viaExemption}. Everyone else must
   * set up TOTP (not enrolled) or owes a code. Called once per sign-in (it writes the {@code
   * MFA_SIGN_IN_SUCCEEDED} event with method {@code exemption} when the exemption applies).
   */
  public FirstFactorLevel levelAfterFirstFactor(
      AppUser user, String trustToken, boolean externalMfa, String clientIp) {
    if (!user.role().isSupporter()) {
      return new FirstFactorLevel(AuthLevel.FULL, false);
    }
    boolean enrolled = repo.isEnrolled(user.id());
    if (enrolled && (externalMfa || devices.isTrusted(user.id(), trustToken))) {
      return new FirstFactorLevel(AuthLevel.FULL, false);
    }
    if (exemptions.activeUntil(user).isPresent()) {
      // The code step was skipped because of the exemption: say so in the log.
      audit.record(
          MfaAuditEvent.MFA_SIGN_IN_SUCCEEDED,
          user.id(),
          user.id(),
          clientIp,
          Map.of("method", "exemption"));
      return new FirstFactorLevel(AuthLevel.FULL, true);
    }
    return new FirstFactorLevel(
        enrolled ? AuthLevel.MFA_REQUIRED : AuthLevel.MFA_ENROLMENT_REQUIRED, false);
  }

  public boolean isEnrolled(long userId) {
    return repo.isEnrolled(userId);
  }

  public Status status(long userId) {
    Optional<MfaRepository.Row> row = repo.find(userId).filter(MfaRepository.Row::enrolled);
    return new Status(
        row.isPresent(),
        row.map(MfaRepository.Row::confirmedAt).orElse(null),
        row.isPresent() ? repo.unusedRecoveryCodes(userId) : 0);
  }

  /** Starts (or restarts) enrolment with a new secret. The caller enforces step-up if enrolled. */
  public Enrolment startEnrolment(AppUser user, String clientIp) {
    requireSupporter(user);
    byte[] secret = Totp.newSecret();
    try {
      repo.savePending(user.id(), cipher.encrypt(secret, aad(user.id())), clock.instant());
      audit.record(MfaAuditEvent.MFA_ENROLMENT_STARTED, user.id(), user.id(), clientIp, Map.of());
      String uri = Totp.otpauthUri(issuer, user.email(), secret);
      return new Enrolment(Totp.base32(secret), uri, QrCode.of(uri));
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  /**
   * Confirms the pending enrolment with a code from the new authenticator. The new secret replaces
   * any old one, 10 new recovery codes replace any old ones (returned once, never stored in plain),
   * security_version is bumped (other sessions end, trusted devices are revoked) and the "two-step
   * sign-in set up" notice is queued. Returns the recovery codes.
   */
  public List<String> confirmEnrolment(
      AppUser user, String code, String keepSessionId, boolean knownDevice, String clientIp) {
    requireSupporter(user);
    return guarded(
        user.id(),
        clientIp,
        knownDevice,
        "enrolment",
        () ->
            tx.execute(
                status -> {
                  MfaRepository.Row row = repo.findForUpdate(user.id()).orElse(null);
                  if (row == null
                      || row.pending() == null
                      || row.pendingCreatedAt()
                          .plus(PENDING_ENROLMENT_VALIDITY)
                          .isBefore(clock.instant())) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "enrolment_expired",
                        "This set-up has expired. Start again to get a new QR code.");
                  }
                  OptionalLong step = matching(row.pending(), user.id(), code);
                  if (step.isEmpty()) {
                    return Optional.<List<String>>empty();
                  }
                  repo.confirmPending(user.id(), step.getAsLong(), clock.instant());
                  List<String> codes = RecoveryCodes.generate();
                  repo.replaceRecoveryCodes(user.id(), hashes(user.id(), codes), clock.instant());
                  securityVersion.bump(
                      user.id(),
                      SecurityVersionService.Reason.MFA,
                      keepSessionId,
                      user.id(),
                      clientIp);
                  audit.record(
                      MfaAuditEvent.MFA_ENROLLED, user.id(), user.id(), clientIp, Map.of());
                  mailer.securityNotice(user.email(), user.name(), SecurityNotice.MFA_ENROLLED);
                  return Optional.of(codes);
                }));
  }

  /** The sign-in TOTP check; throws 400 {@code invalid_code} or 429. */
  public void verifyCode(AppUser user, String code, boolean knownDevice, String clientIp) {
    requireSupporter(user);
    guarded(user.id(), clientIp, knownDevice, "totp", () -> acceptTotp(user.id(), code));
    audit.record(
        MfaAuditEvent.MFA_SIGN_IN_SUCCEEDED,
        user.id(),
        user.id(),
        clientIp,
        Map.of("method", "totp"));
  }

  /** The sign-in recovery-code check; the code is used up. Returns the codes left. */
  public int verifyRecoveryCode(
      AppUser user, String recoveryCode, boolean knownDevice, String clientIp) {
    requireSupporter(user);
    int left =
        guarded(
            user.id(),
            clientIp,
            knownDevice,
            "recovery_code",
            () -> spendRecoveryCode(user.id(), recoveryCode));
    audit.record(
        MfaAuditEvent.MFA_SIGN_IN_SUCCEEDED,
        user.id(),
        user.id(),
        clientIp,
        Map.of("method", "recovery_code"));
    return left;
  }

  /**
   * Step-up with a fresh TOTP or a recovery code (accepted and used up). Exactly one of the two is
   * given. Returns the recovery codes left when one was used, else -1.
   */
  public int verifyStepUp(
      AppUser user, String code, String recoveryCode, boolean knownDevice, String clientIp) {
    if (!user.role().isSupporter() || !repo.isEnrolled(user.id())) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "mfa_not_enrolled", "Two-step sign-in is not set up.");
    }
    int left;
    if (recoveryCode != null && !recoveryCode.isBlank()) {
      left =
          guarded(
              user.id(),
              clientIp,
              knownDevice,
              "step_up_recovery_code",
              () -> spendRecoveryCode(user.id(), recoveryCode));
    } else {
      guarded(user.id(), clientIp, knownDevice, "step_up_totp", () -> acceptTotp(user.id(), code));
      left = -1;
    }
    audit.record(
        MfaAuditEvent.STEP_UP_SUCCEEDED,
        user.id(),
        user.id(),
        clientIp,
        Map.of("method", left < 0 ? "totp" : "recovery_code"));
    return left;
  }

  /**
   * Step-up for an engineer with a two-step exemption that applies now: password re-entry, behind
   * the same back-off as codes. Anyone else is refused (403 {@code password_step_up_not_allowed})
   * and must use a code.
   */
  public void verifyPasswordStepUp(
      AppUser user, String password, boolean knownDevice, String clientIp) {
    if (exemptions.activeUntil(user).isEmpty()) {
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "password_step_up_not_allowed",
          "Confirm with a code from your authenticator app.");
    }
    guarded(
        user.id(),
        clientIp,
        knownDevice,
        "step_up_password",
        "That password is not right.",
        () ->
            password != null
                    && password.length() <= PasswordPolicy.MAX_LENGTH
                    && user.passwordHash() != null
                    && encoder.matches(password, user.passwordHash())
                ? Optional.of(Boolean.TRUE)
                : Optional.empty());
    audit.record(
        MfaAuditEvent.STEP_UP_SUCCEEDED,
        user.id(),
        user.id(),
        clientIp,
        Map.of("method", "password"));
  }

  /** New set of 10 recovery codes; the old ones stop working. Caller enforces step-up. */
  public List<String> regenerateRecoveryCodes(AppUser user, String clientIp) {
    requireSupporter(user);
    if (!repo.isEnrolled(user.id())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "mfa_not_enrolled", "Two-step sign-in is not set up.");
    }
    List<String> codes = RecoveryCodes.generate();
    tx.executeWithoutResult(
        status -> {
          repo.replaceRecoveryCodes(user.id(), hashes(user.id(), codes), clock.instant());
          audit.record(
              MfaAuditEvent.RECOVERY_CODES_REGENERATED, user.id(), user.id(), clientIp, Map.of());
        });
    return codes;
  }

  /**
   * Admin "reset MFA" and the break-glass command: forgets the secret and recovery codes, bumps
   * security_version (every session ends, trusted devices are revoked) and queues the notice. The
   * user enrols again at their next sign-in. {@code actorUserId} is null for break-glass.
   */
  public void reset(long targetUserId, Long actorUserId, String clientIp, String method) {
    tx.executeWithoutResult(
        status -> {
          AppUser target =
              users
                  .findByIdForUpdate(targetUserId)
                  .filter(u -> u.role().isSupporter())
                  .orElseThrow(
                      () -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such agent."));
          repo.delete(target.id());
          securityVersion.bump(
              target.id(), SecurityVersionService.Reason.MFA, null, actorUserId, clientIp);
          audit.record(
              MfaAuditEvent.MFA_RESET,
              actorUserId,
              target.id(),
              clientIp,
              Map.of("method", method));
          mailer.securityNotice(target.email(), target.name(), SecurityNotice.MFA_RESET);
        });
  }

  private Optional<Boolean> acceptTotp(long userId, String code) {
    MfaRepository.Row row =
        repo.find(userId)
            .filter(MfaRepository.Row::enrolled)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.FORBIDDEN,
                        "mfa_not_enrolled",
                        "Two-step sign-in is not set up."));
    OptionalLong step = matching(row.secret(), userId, code);
    // The conditional UPDATE refuses a step that was already used (replay, or two parallel
    // requests with the same code).
    if (step.isEmpty() || !repo.acceptStep(userId, step.getAsLong())) {
      return Optional.empty();
    }
    return Optional.of(Boolean.TRUE);
  }

  private Optional<Integer> spendRecoveryCode(long userId, String input) {
    Optional<String> normalised = RecoveryCodes.normalise(input);
    if (normalised.isEmpty()
        || !repo.useRecoveryCode(
            userId, RecoveryCodes.hash(userId, normalised.get()), clock.instant())) {
      return Optional.empty();
    }
    int left = repo.unusedRecoveryCodes(userId);
    audit.record(MfaAuditEvent.RECOVERY_CODE_USED, userId, userId, null, Map.of("remaining", left));
    return Optional.of(left);
  }

  private OptionalLong matching(DataCipher.Sealed sealed, long userId, String code) {
    byte[] secret = cipher.decrypt(sealed, aad(userId));
    try {
      return Totp.matchingStep(secret, code, clock.instant());
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  /**
   * Runs one code check behind the back-off. A miss records a failure (audited) and answers 400
   * {@code invalid_code}; a refused attempt inside the delay is audited and answers 429.
   */
  private <T> T guarded(
      long userId,
      String clientIp,
      boolean knownDevice,
      String method,
      Supplier<Optional<T>> attempt) {
    return guarded(
        userId,
        clientIp,
        knownDevice,
        method,
        "That code is not right. Check your authenticator app and try again.",
        attempt);
  }

  private <T> T guarded(
      long userId,
      String clientIp,
      boolean knownDevice,
      String method,
      String wrongMessage,
      Supplier<Optional<T>> attempt) {
    String account = "mfa:" + userId;
    try {
      backoff.check(account, clientIp, knownDevice);
    } catch (ThrottledException e) {
      audit.record(MfaAuditEvent.MFA_THROTTLED, null, userId, clientIp, Map.of("method", method));
      throw e;
    }
    Optional<T> result = attempt.get();
    if (result.isEmpty()) {
      int failures = backoff.recordFailure(account, clientIp, knownDevice);
      audit.record(
          MfaAuditEvent.MFA_FAILED,
          null,
          userId,
          clientIp,
          Map.of("method", method, "attempts", failures));
      throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_code", wrongMessage);
    }
    backoff.recordSuccess(account, clientIp);
    return result.get();
  }

  private static void requireSupporter(AppUser user) {
    if (!user.role().isSupporter()) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "forbidden", "Two-step sign-in is for agents only.");
    }
  }

  private static List<String> hashes(long userId, List<String> codes) {
    return codes.stream()
        .map(c -> RecoveryCodes.hash(userId, RecoveryCodes.normalise(c).orElseThrow()))
        .toList();
  }

  public static String aad(long userId) {
    return "user_mfa:" + userId;
  }
}
