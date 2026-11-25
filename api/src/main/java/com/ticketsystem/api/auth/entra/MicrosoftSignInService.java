package com.ticketsystem.api.auth.entra;

import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.access.PrincipalAccessReader.PrincipalAccess;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.AuthAuditEvent;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.auth.Emails;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.SecureTokens;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.mfa.MfaService;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.organisation.OrganisationService;
import com.ticketsystem.api.organisation.OrganisationService.OrganisationView;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Sign in with Microsoft" (C1/C2).
 *
 * <ul>
 *   <li>Users are found <b>only</b> by the (tid, oid) of a validated ID token, never by email.
 *   <li>Tenant allow list: a supporter's link must be in the MSP tenant (B2B guests refused); a
 *       customer's in the tenant set on their organisation. Checked at link time and again at every
 *       sign-in, so changing an organisation's tenant stops its old links working.
 *   <li>Supporters link only from a signed-in session (My settings - Security, with step-up) and
 *       never at sign-in. A customer with no link is linked at sign-in only when all of these hold:
 *       the token's tid is their organisation's tenant, Microsoft says the email's domain is
 *       verified by that tenant ({@code xms_edov}), the account is not a guest, the email equals an
 *       active customer of that organisation, and its domain is one of the organisation's verified
 *       domains. Otherwise "sign in with your password, then link Microsoft".
 *   <li>MFA: {@code amr} containing {@code mfa} counts only when tid is the MSP tenant; otherwise a
 *       supporter still owes the TOTP step (or its set-up). It never counts as step-up.
 *   <li>Each flow keeps state, nonce and a PKCE verifier in the server-side session for 10 minutes;
 *       the callback spends them once. Test sign-in records a pass for the exact settings version
 *       it started with and signs nobody in.
 * </ul>
 */
@Service
public class MicrosoftSignInService {

  /** The callback path; the redirect URI is the public base URL plus this. */
  public static final String CALLBACK_PATH = "/api/v1/auth/microsoft/callback";

  static final String PENDING_ATTRIBUTE = "ts.entraPending";
  static final Duration PENDING_VALIDITY = Duration.ofMinutes(10);
  private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_]{1,64}");

  public enum Mode {
    SIGN_IN,
    LINK,
    TEST
  }

  /** One flow in progress, kept in the session (Spring Session JDBC serialises it). */
  record Pending(
      Mode mode,
      String state,
      String nonce,
      String verifier,
      Long userId,
      String tenantSegment,
      int settingsVersion,
      long createdAtMillis)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
      return "EntraPending[" + mode + "]";
    }
  }

  /** What the callback ended with; the controller turns it into a redirect. */
  public sealed interface Outcome {
    /**
     * Signed in (sign-in mode). The controller starts the session at {@code level}; {@code
     * viaExemption} marks a session that is FULL only because of a two-step exemption.
     */
    record SignedIn(AppUser user, AuthLevel level, boolean viaExemption) implements Outcome {}

    record Linked(Role role) implements Outcome {}

    record TestPassed() implements Outcome {}

    /**
     * Failed; {@code code} is a short word for the web page ({@code failed}, {@code not_linked},
     * {@code unavailable}, ...), or an {@code AADSTS} code in test mode. Never token content.
     */
    record Failed(Mode mode, Role role, String code) implements Outcome {}
  }

  /** My settings - Security: the caller's Microsoft account. */
  public record LinkStatus(boolean available, boolean linked, String tenantId, Instant linkedAt) {}

  private final EntraSettingsService settings;
  private final EntraClient entra;
  private final ExternalIdentityRepository identities;
  private final UserRepository users;
  private final PrincipalAccessReader access;
  private final OrganisationService organisations;
  private final MfaService mfa;
  private final RateLimiter rateLimiter;
  private final AuditService audit;
  private final AuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MicrosoftSignInService(
      EntraSettingsService settings,
      EntraClient entra,
      ExternalIdentityRepository identities,
      UserRepository users,
      PrincipalAccessReader access,
      OrganisationService organisations,
      MfaService mfa,
      RateLimiter rateLimiter,
      AuditService audit,
      AuthProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.settings = settings;
    this.entra = entra;
    this.identities = identities;
    this.users = users;
    this.access = access;
    this.organisations = organisations;
    this.mfa = mfa;
    this.rateLimiter = rateLimiter;
    this.audit = audit;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** The redirect URI to register in Entra (shown on the admin screen). */
  public String redirectUri() {
    return props.page(CALLBACK_PATH);
  }

  // ---------------------------------------------------------------------------------- starting

  /** Starts a sign-in; 409 while switched off. Returns the Microsoft URL for the browser. */
  public URI startSignIn(HttpSession session, String clientIp) {
    rateLimiter.consume(RateLimiter.Scope.SIGN_IN_IP, clientIp);
    EntraSettingsService.Config config = settings.activeConfig().orElseThrow(this::disabled);
    return start(session, Mode.SIGN_IN, null, "organizations", config);
  }

  /** Starts linking for a signed-in user (the controller enforces step-up for supporters). */
  public URI startLink(AppUser user, HttpSession session) {
    EntraSettingsService.Config config = settings.activeConfig().orElseThrow(this::disabled);
    if (identities.findByUser(user.id()).isPresent()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "microsoft_already_linked",
          "A Microsoft account is already linked. Unlink it first.");
    }
    UUID tenant =
        allowedTenant(user, config)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "microsoft_not_available",
                        "Microsoft sign-in is not set up for your organisation."));
    return start(session, Mode.LINK, user.id(), tenant.toString(), config);
  }

  /** Starts Test sign-in for an admin with the saved (possibly not yet enabled) details. */
  public URI startTest(long adminId, HttpSession session) {
    EntraSettingsService.Config config =
        settings
            .configForTest()
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "microsoft_incomplete",
                        "Enter the application ID, client secret and MSP tenant ID first."));
    return start(session, Mode.TEST, adminId, config.mspTenantId().toString(), config);
  }

  private URI start(
      HttpSession session,
      Mode mode,
      Long userId,
      String tenantSegment,
      EntraSettingsService.Config config) {
    String state = SecureTokens.newToken();
    String nonce = SecureTokens.newToken();
    String verifier = SecureTokens.newToken();
    session.setAttribute(
        PENDING_ATTRIBUTE,
        new Pending(
            mode,
            state,
            nonce,
            verifier,
            userId,
            tenantSegment,
            config.settingsVersion(),
            clock.millis()));
    return entra.authorizationUrl(
        tenantSegment, config.clientId(), redirectUri(), state, nonce, challenge(verifier));
  }

  // ---------------------------------------------------------------------------------- callback

  /**
   * Finishes the flow from the callback's query parameters. {@code principalId} is the signed-in
   * user of this session (link and test must come back to the same user). Never throws for a
   * Microsoft or token problem: every failure is an audited {@link Outcome.Failed}, except a
   * throttled callback, which is refused before anything is written.
   */
  public Outcome complete(
      HttpSession session,
      Long principalId,
      String state,
      String code,
      String error,
      String trustToken,
      String clientIp) {
    // The per-IP sign-in cap comes first, for every mode, before any audit write or other work:
    // the callback is unauthenticated, so an unthrottled failure path would let anyone fill the
    // audit log. A throttled callback writes nothing; the pending request (if any) is spent.
    if (!rateLimiter.tryConsume(RateLimiter.Scope.SIGN_IN_IP, clientIp)) {
      Pending spent = session == null ? null : take(session);
      Mode mode = spent == null ? Mode.SIGN_IN : spent.mode();
      Role role = spent == null || spent.userId() == null ? null : roleOf(spent.userId());
      return new Outcome.Failed(mode, role, "throttled");
    }
    Pending pending = session == null ? null : take(session);
    if (pending == null) {
      return fail(Mode.SIGN_IN, null, null, "failed", "no_request", null, clientIp);
    }
    Mode mode = pending.mode();
    Role role = pending.userId() == null ? null : roleOf(pending.userId());
    if (clock.millis() - pending.createdAtMillis() > PENDING_VALIDITY.toMillis()
        || clock.millis() < pending.createdAtMillis()) {
      return fail(mode, role, pending.userId(), "failed", "request_expired", null, clientIp);
    }
    if (!SecureTokens.constantTimeEquals(pending.state(), state)) {
      return fail(mode, role, pending.userId(), "failed", "state", null, clientIp);
    }
    if (error != null) {
      String safe = SAFE_CODE.matcher(error).matches() ? error : "unknown";
      String shown = "access_denied".equals(safe) ? "cancelled" : "failed";
      return fail(mode, role, pending.userId(), shown, "microsoft_error", safe, clientIp);
    }
    if (code == null || code.isBlank() || code.length() > 4096) {
      return fail(mode, role, pending.userId(), "failed", "no_code", null, clientIp);
    }
    Optional<EntraSettingsService.Config> maybeConfig =
        mode == Mode.TEST ? settings.configForTest() : settings.activeConfig();
    if (maybeConfig.isEmpty()) {
      return fail(mode, role, pending.userId(), "disabled", "disabled", null, clientIp);
    }
    EntraSettingsService.Config config = maybeConfig.get();
    if (config.settingsVersion() != pending.settingsVersion()) {
      return fail(mode, role, pending.userId(), "failed", "settings_changed", null, clientIp);
    }
    if (mode != Mode.SIGN_IN && !pending.userId().equals(principalId)) {
      return fail(mode, role, pending.userId(), "failed", "session_changed", null, clientIp);
    }

    EntraClient.IdToken token;
    try {
      String raw =
          entra.redeem(
              pending.tenantSegment(),
              config.clientId(),
              config.secret(),
              code,
              redirectUri(),
              pending.verifier());
      token = entra.validate(raw, config.clientId(), pending.nonce());
    } catch (EntraClient.Unavailable e) {
      return fail(mode, role, pending.userId(), "unavailable", "unavailable", null, clientIp);
    } catch (EntraClient.Rejected e) {
      String shown = mode == Mode.TEST ? e.errorCode() : "failed";
      return fail(mode, role, pending.userId(), shown, "rejected", e.errorCode(), clientIp);
    } catch (EntraClient.InvalidToken e) {
      return fail(mode, role, pending.userId(), "failed", "token_" + e.reason(), null, clientIp);
    }

    return switch (mode) {
      case SIGN_IN -> signIn(token, config, trustToken, clientIp);
      case LINK -> link(pending.userId(), token, config, clientIp);
      case TEST -> test(pending.userId(), token, config, clientIp);
    };
  }

  private Outcome signIn(
      EntraClient.IdToken token,
      EntraSettingsService.Config config,
      String trustToken,
      String clientIp) {
    Optional<ExternalIdentityRepository.Link> link =
        identities.findByIdentity(token.tenantId(), token.objectId());
    AppUser user;
    boolean autoLinked = false;
    if (link.isPresent()) {
      user = users.findById(link.get().userId()).orElse(null);
      if (user == null || !user.isActive()) {
        return signInFailed(user, token, "not_active", "failed", clientIp);
      }
      if (!tenantAllowed(user, token, config)) {
        return signInFailed(user, token, "tenant_not_allowed", "failed", clientIp);
      }
    } else {
      Optional<AppUser> candidate = autoLinkCandidate(token);
      if (candidate.isEmpty()) {
        return signInFailed(null, token, "not_linked", "not_linked", clientIp);
      }
      user = candidate.get();
      Boolean inserted =
          tx.execute(
              s -> {
                boolean ok =
                    identities.insert(
                        candidate.get().id(), token.tenantId(), token.objectId(), clock.instant());
                if (ok) {
                  recordLinked(candidate.get().id(), token, "auto", clientIp);
                }
                return ok;
              });
      if (!Boolean.TRUE.equals(inserted)) {
        return signInFailed(user, token, "link_conflict", "failed", clientIp);
      }
      autoLinked = true;
    }

    MfaService.FirstFactorLevel first = levelFor(user, token, config, trustToken, clientIp);
    AuthLevel level = first.level();
    identities.touchSignIn(user.id(), clock.instant());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("method", "microsoft");
    details.put("role", user.role());
    details.put("tenant", token.tenantId().toString());
    if (autoLinked) {
      details.put("reason", "auto_linked");
    } else if (level == AuthLevel.FULL
        && user.role().isSupporter()
        && !first.viaExemption()
        && entraMfaCounts(token, config)) {
      details.put("reason", "entra_mfa");
    }
    audit.record(AuthAuditEvent.SIGN_IN_SUCCEEDED, user.id(), user.id(), clientIp, details);
    return new Outcome.SignedIn(user, level, first.viaExemption());
  }

  /**
   * Supporters: FULL when enrolled and the MSP tenant says MFA was done, or on a trusted device;
   * else FULL for an engineer with a two-step exemption that applies now; else TOTP set-up (not
   * enrolled) or the TOTP step. Customers: FULL. {@code trustToken} is the trust cookie.
   */
  private MfaService.FirstFactorLevel levelFor(
      AppUser user,
      EntraClient.IdToken token,
      EntraSettingsService.Config config,
      String trustToken,
      String clientIp) {
    return mfa.levelAfterFirstFactor(user, trustToken, entraMfaCounts(token, config), clientIp);
  }

  /** Entra's MFA claim counts only from the MSP's own tenant. Visible for tests. */
  static boolean entraMfaCounts(EntraClient.IdToken token, EntraSettingsService.Config config) {
    return token.mfaClaimed() && token.tenantId().equals(config.mspTenantId());
  }

  private Outcome link(
      long userId, EntraClient.IdToken token, EntraSettingsService.Config config, String clientIp) {
    AppUser user = users.findById(userId).filter(AppUser::isActive).orElse(null);
    Role role = user == null ? null : user.role();
    if (user == null) {
      return fail(Mode.LINK, role, userId, "failed", "not_active", null, clientIp);
    }
    if (!tenantAllowed(user, token, config)) {
      return fail(Mode.LINK, role, userId, "wrong_tenant", "tenant_not_allowed", null, clientIp);
    }
    if (identities.findByUser(userId).isPresent()) {
      return fail(Mode.LINK, role, userId, "already_linked", "already_linked", null, clientIp);
    }
    Boolean inserted =
        tx.execute(
            s -> {
              boolean ok =
                  identities.insert(userId, token.tenantId(), token.objectId(), clock.instant());
              if (ok) {
                recordLinked(userId, token, "settings", clientIp);
              }
              return ok;
            });
    if (!Boolean.TRUE.equals(inserted)) {
      return fail(Mode.LINK, role, userId, "linked_elsewhere", "link_conflict", null, clientIp);
    }
    return new Outcome.Linked(role);
  }

  private Outcome test(
      long adminId,
      EntraClient.IdToken token,
      EntraSettingsService.Config config,
      String clientIp) {
    if (!token.tenantId().equals(config.mspTenantId())) {
      return fail(Mode.TEST, Role.ADMIN, adminId, "wrong_tenant", "wrong_tenant", null, clientIp);
    }
    if (!settings.recordTestPassed(config.settingsVersion(), adminId, clientIp)) {
      return new Outcome.Failed(Mode.TEST, Role.ADMIN, "settings_changed");
    }
    return new Outcome.TestPassed();
  }

  // ------------------------------------------------------------------------------- link admin

  public LinkStatus status(AppUser user) {
    Optional<ExternalIdentityRepository.Link> link = identities.findByUser(user.id());
    boolean available =
        settings.activeConfig().flatMap(config -> allowedTenant(user, config)).isPresent();
    return new LinkStatus(
        available,
        link.isPresent(),
        link.map(l -> l.tenantId().toString()).orElse(null),
        link.map(ExternalIdentityRepository.Link::linkedAt).orElse(null));
  }

  /**
   * Unlinks the caller's Microsoft account (the controller enforces step-up for supporters). An
   * active account always has a password (database rule), so this never removes the last way in.
   */
  public void unlink(AppUser user, String clientIp) {
    Optional<ExternalIdentityRepository.Link> link = identities.findByUser(user.id());
    if (link.isEmpty() || !identities.deleteByUser(user.id())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No Microsoft account is linked.");
    }
    audit.record(
        EntraAuditEvent.MICROSOFT_UNLINKED,
        user.id(),
        user.id(),
        clientIp,
        Map.of("tenant", link.get().tenantId().toString()));
  }

  // ---------------------------------------------------------------------------------- helpers

  /** The one tenant this user may link or sign in from, if any. */
  private Optional<UUID> allowedTenant(AppUser user, EntraSettingsService.Config config) {
    if (user.role().isSupporter()) {
      return Optional.of(config.mspTenantId());
    }
    return organisationOf(user.id()).map(OrganisationView::entraTenantId);
  }

  private boolean tenantAllowed(
      AppUser user, EntraClient.IdToken token, EntraSettingsService.Config config) {
    if (user.role().isSupporter() && token.guest()) {
      return false;
    }
    return allowedTenant(user, config).map(t -> t.equals(token.tenantId())).orElse(false);
  }

  /** The customer's organisation, read with that customer's own scope. */
  private Optional<OrganisationView> organisationOf(long userId) {
    return access
        .read(userId)
        .filter(p -> p.role() == Role.CUSTOMER && p.organisationId() != null)
        .map(p -> organisations.get(p.toScope(), p.organisationId()));
  }

  /** Customer auto-link at sign-in (see the class comment). Never a supporter. */
  private Optional<AppUser> autoLinkCandidate(EntraClient.IdToken token) {
    if (!token.emailDomainVerified() || token.guest()) {
      return Optional.empty();
    }
    Optional<String> email = Emails.normalise(token.email());
    if (email.isEmpty()) {
      return Optional.empty();
    }
    Optional<AppUser> user =
        users
            .findByEmail(email.get())
            .filter(AppUser::isActive)
            .filter(u -> u.role() == Role.CUSTOMER);
    if (user.isEmpty() || identities.findByUser(user.get().id()).isPresent()) {
      return Optional.empty();
    }
    Optional<PrincipalAccess> principal = access.read(user.get().id());
    if (principal.isEmpty() || principal.get().organisationId() == null) {
      return Optional.empty();
    }
    OrganisationView org =
        organisations.get(principal.get().toScope(), principal.get().organisationId());
    String domain = email.get().substring(email.get().indexOf('@') + 1);
    if (!token.tenantId().equals(org.entraTenantId()) || !org.verifiedDomains().contains(domain)) {
      return Optional.empty();
    }
    return user;
  }

  private void recordLinked(long userId, EntraClient.IdToken token, String how, String ip) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("tenant", token.tenantId().toString());
    details.put("reason", how);
    audit.record(EntraAuditEvent.MICROSOFT_LINKED, userId, userId, ip, details);
  }

  private Outcome signInFailed(
      AppUser user, EntraClient.IdToken token, String reason, String shown, String clientIp) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("method", "microsoft");
    details.put("reason", reason);
    details.put("tenant", token.tenantId().toString());
    audit.record(
        AuthAuditEvent.SIGN_IN_FAILED, null, user == null ? null : user.id(), clientIp, details);
    return new Outcome.Failed(Mode.SIGN_IN, null, shown);
  }

  private Outcome fail(
      Mode mode,
      Role role,
      Long userId,
      String shown,
      String reason,
      String errorCode,
      String clientIp) {
    if (mode == Mode.TEST && userId != null) {
      settings.recordTestFailed(userId, reason, errorCode, clientIp);
    } else {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("method", "microsoft");
      details.put("reason", reason);
      details.put("error_code", errorCode);
      audit.record(
          mode == Mode.LINK ? EntraAuditEvent.MICROSOFT_LINK_FAILED : AuthAuditEvent.SIGN_IN_FAILED,
          mode == Mode.LINK ? userId : null,
          userId,
          clientIp,
          details);
    }
    return new Outcome.Failed(mode, role, shown);
  }

  private Pending take(HttpSession session) {
    Object value = session.getAttribute(PENDING_ATTRIBUTE);
    session.removeAttribute(PENDING_ATTRIBUTE);
    return value instanceof Pending p ? p : null;
  }

  private Role roleOf(long userId) {
    return users.findById(userId).map(AppUser::role).orElse(null);
  }

  private ApiException disabled() {
    return new ApiException(
        HttpStatus.CONFLICT, "microsoft_disabled", "Microsoft sign-in is switched off.");
  }

  /** PKCE S256 challenge. Visible for tests. */
  static String challenge(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
