package com.ticketsystem.api.auth.mfa;

import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.mail.SystemMailer;
import com.ticketsystem.api.auth.session.SecurityVersionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The time-limited two-step sign-in exemption for one named engineer (see docs/10-decision-log.md
 * ADR-031).
 *
 * <ul>
 *   <li><b>Who</b>: only an active supporter with role {@code AGENT}; never an admin (refused here,
 *       whatever the UI shows). Granted and revoked only from the admin tree, with step-up (the
 *       controller), never on yourself.
 *   <li><b>How long</b>: a reason (1-200 characters, one line) and an end time later than now and
 *       at most 30 days from now are required. There is no "never ends".
 *   <li><b>Ending</b>: no job is needed for the check itself. {@link #activeUntil} - called at
 *       sign-in and, for sessions that skipped two-step because of the exemption, on every request
 *       - treats a passed end time as no exemption, deletes the row and writes {@code
 *       MFA_EXEMPTION_EXPIRED} once. A revoke deletes the row and bumps security_version (every
 *       session of the engineer ends at once). Role change to admin and deactivation delete it too
 *       ({@link #clear}; plus a database trigger as a safety net). The nightly retention job
 *       removes ended rows nobody noticed ({@link #sweepEnded}), so the free-text reason does not
 *       outlive the exemption.
 *   <li><b>While exempt</b>: password or Microsoft sign-in completes without the set-up or code
 *       step ({@link MfaService#levelAfterFirstFactor}); step-up is password re-entry ({@link
 *       MfaService#verifyPasswordStepUp}).
 * </ul>
 */
@Service
public class MfaExemptionService {

  public static final Duration MAX_PERIOD = Duration.ofDays(30);
  public static final Duration DEFAULT_PERIOD = Duration.ofDays(7);
  public static final int MAX_REASON_LENGTH = 200;

  /** Rows per batch of {@link #sweepEnded}. */
  static final int SWEEP_BATCH = 500;

  /** What an admin sees about an exemption. */
  public record Exemption(String reason, Instant endsAt, Instant grantedAt) {
    @Override
    public String toString() {
      return "Exemption[endsAt=" + endsAt + "]";
    }
  }

  private final MfaExemptionRepository repo;
  private final UserRepository users;
  private final SecurityVersionService securityVersion;
  private final SystemMailer mailer;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MfaExemptionService(
      MfaExemptionRepository repo,
      UserRepository users,
      SecurityVersionService securityVersion,
      SystemMailer mailer,
      AuditService audit,
      TransactionTemplate tx,
      Clock clock) {
    this.repo = repo;
    this.users = users;
    this.securityVersion = securityVersion;
    this.mailer = mailer;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * The end time of the user's exemption when it applies now: an active engineer with an exemption
   * that has not ended. Anything else is empty. An exemption whose end time has passed is deleted
   * here and audited as expired (once, whoever notices first).
   */
  public Optional<Instant> activeUntil(AppUser user) {
    if (user == null || user.role() != Role.AGENT || !user.isActive()) {
      return Optional.empty();
    }
    Optional<MfaExemptionRepository.Row> row = repo.find(user.id());
    if (row.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (row.get().endsAt().isAfter(now)) {
      return Optional.of(row.get().endsAt());
    }
    tx.executeWithoutResult(
        status -> {
          if (repo.deleteIfEnded(user.id(), now)) {
            audit.record(
                MfaExemptionAuditEvent.MFA_EXEMPTION_EXPIRED,
                null,
                user.id(),
                null,
                Map.of("exempt_until", row.get().endsAt().toString()));
          }
        });
    return Optional.empty();
  }

  /**
   * Deletes every exemption whose end time has passed (reason, end time and granting admin go with
   * the row) and writes {@code MFA_EXEMPTION_EXPIRED} for each, exactly once even if a sign-in
   * notices the same row at the same moment. Called by the nightly retention job, so a reason does
   * not stay in the database when the engineer never signs in again. Returns the number deleted.
   */
  public int sweepEnded() {
    Instant now = clock.instant();
    int total = 0;
    while (true) {
      List<MfaExemptionRepository.Row> ended = repo.ended(now, SWEEP_BATCH);
      for (MfaExemptionRepository.Row row : ended) {
        Boolean deleted =
            tx.execute(
                status -> {
                  if (!repo.deleteIfEnded(row.userId(), now)) {
                    return false;
                  }
                  audit.record(
                      MfaExemptionAuditEvent.MFA_EXEMPTION_EXPIRED,
                      null,
                      row.userId(),
                      null,
                      Map.of("exempt_until", row.endsAt().toString()));
                  return true;
                });
        if (Boolean.TRUE.equals(deleted)) {
          total++;
        }
      }
      if (ended.size() < SWEEP_BATCH) {
        return total;
      }
    }
  }

  /** The exemption as Admin - Supporters shows it, or empty when none applies now. */
  public Optional<Exemption> find(long userId) {
    Instant now = clock.instant();
    return repo.find(userId)
        .filter(r -> r.endsAt().isAfter(now))
        .map(r -> new Exemption(r.reason(), r.endsAt(), r.grantedAt()));
  }

  /**
   * Exempts {@code targetId} until {@code endsAt} (or replaces their current exemption). The caller
   * has checked the admin role and step-up. Refuses yourself, admins, customers, inactive accounts,
   * a missing or too long reason and an end time not in (now, now + 30 days]. Sends the engineer a
   * security notice. No session ends: the next sign-in simply skips the two-step step.
   */
  public Exemption grant(
      long actorId, long targetId, String rawReason, Instant endsAt, String clientIp) {
    String reason = validReason(rawReason);
    Instant now = clock.instant();
    if (endsAt == null) {
      throw ApiException.invalidRequest("Choose an end date.");
    }
    if (!endsAt.isAfter(now)) {
      throw ApiException.invalidRequest("The end date must be in the future.");
    }
    if (endsAt.isAfter(now.plus(MAX_PERIOD))) {
      throw ApiException.invalidRequest("The end date can be at most 30 days from now.");
    }
    if (actorId == targetId) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "own_account",
          "Use My settings - Security for your own account.");
    }
    return tx.execute(
        status -> {
          AppUser target = lockSupporter(targetId);
          if (target.role() != Role.AGENT) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "exemption_not_allowed",
                "Only engineers (role Agent) can be exempt from two-step sign-in. Admins always"
                    + " use it.");
          }
          if (!target.isActive()) {
            throw new ApiException(
                HttpStatus.CONFLICT, "not_active", "Only active agents can be exempt.");
          }
          boolean replaced = repo.upsert(target.id(), reason, endsAt, actorId, now);
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("exempt_until", endsAt.toString());
          details.put("replaced", replaced);
          audit.record(
              MfaExemptionAuditEvent.MFA_EXEMPTION_GRANTED,
              actorId,
              target.id(),
              clientIp,
              details);
          mailer.mfaExemptionGranted(target.email(), target.name(), endsAt);
          return new Exemption(reason, endsAt, now);
        });
  }

  /**
   * Ends the exemption now (the caller has checked the admin role and step-up). Bumps
   * security_version, so every session of the engineer ends at once and the next sign-in asks for
   * two-step again (set-up first if they never set it up). Trusted devices stay. 404 when there is
   * no exemption that applies now.
   */
  public void revoke(long actorId, long targetId, String clientIp) {
    if (actorId == targetId) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "own_account",
          "Use My settings - Security for your own account.");
    }
    tx.executeWithoutResult(
        status -> {
          AppUser target = lockSupporter(targetId);
          Optional<MfaExemptionRepository.Row> row = repo.find(target.id());
          if (row.isEmpty() || !row.get().endsAt().isAfter(clock.instant())) {
            throw new ApiException(
                HttpStatus.NOT_FOUND,
                "not_exempt",
                "This agent is not exempt from two-step sign-in.");
          }
          repo.delete(target.id());
          audit.record(
              MfaExemptionAuditEvent.MFA_EXEMPTION_REVOKED,
              actorId,
              target.id(),
              clientIp,
              Map.of("ended_by", "admin", "exempt_until", row.get().endsAt().toString()));
          securityVersion.bump(
              target.id(), SecurityVersionService.Reason.MFA_EXEMPTION, null, actorId, clientIp);
        });
  }

  /**
   * Removes the exemption because the engineer is becoming an admin or being deactivated ({@code
   * endedBy} = {@code role_change} or {@code deactivation}). Joins the caller's transaction; the
   * caller bumps security_version for its own change. Call it before the role or status update (the
   * database trigger would otherwise delete the row without an audit event).
   */
  public void clear(long actorId, long targetId, String endedBy, String clientIp) {
    tx.executeWithoutResult(
        status -> {
          Optional<MfaExemptionRepository.Row> row = repo.find(targetId);
          if (row.isPresent() && repo.delete(targetId)) {
            audit.record(
                MfaExemptionAuditEvent.MFA_EXEMPTION_REVOKED,
                actorId,
                targetId,
                clientIp,
                Map.of("ended_by", endedBy, "exempt_until", row.get().endsAt().toString()));
          }
        });
  }

  private AppUser lockSupporter(long id) {
    return users
        .findByIdForUpdate(id)
        .filter(u -> u.role().isSupporter())
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such agent."));
  }

  /** One line of text, 1-200 characters after trimming. */
  static String validReason(String raw) {
    String reason = raw == null ? "" : raw.strip();
    if (reason.isEmpty()) {
      throw ApiException.invalidRequest("Give a reason.");
    }
    if (reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
      throw ApiException.invalidRequest("Keep the reason to 200 characters or fewer.");
    }
    if (reason.codePoints().anyMatch(Character::isISOControl)) {
      throw ApiException.invalidRequest("The reason must be one line of text.");
    }
    return reason;
  }
}
