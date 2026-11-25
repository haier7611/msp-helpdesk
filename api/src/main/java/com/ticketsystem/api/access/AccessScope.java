package com.ticketsystem.api.access;

import com.ticketsystem.api.auth.ApiException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Whose data a piece of code may touch (C13). Built once per request by {@link AccessScopeFilter}
 * from the signed-in user's current database state and passed as the first parameter to every
 * repository method on client-owned data (enforced by the ArchUnit rule in {@code
 * ArchitectureTest}). Immutable.
 *
 * <ul>
 *   <li>{@link Kind#CUSTOMER}: exactly one organisation.
 *   <li>{@link Kind#SUPPORTER}: an agent with every organisation or a listed set; an admin always
 *       every organisation.
 *   <li>{@link Kind#API_KEY}: the one organisation the key is bound to.
 *   <li>{@link Kind#SYSTEM}: scheduled jobs and the mail poller; every organisation, created only
 *       through {@link #system(String)}, which logs the reason.
 * </ul>
 *
 * <p>{@link #require(long)} answers 404, never 403, so another organisation's ids look exactly like
 * ids that do not exist.
 */
public final class AccessScope {

  /** Request attribute under which the filter stores the scope. */
  public static final String REQUEST_ATTRIBUTE = AccessScope.class.getName();

  private static final Logger log = LoggerFactory.getLogger(AccessScope.class);

  public enum Kind {
    CUSTOMER,
    SUPPORTER,
    API_KEY,
    SYSTEM
  }

  private final Kind kind;
  private final Long principalId;
  private final boolean admin;
  private final boolean allOrganisations;
  private final Set<Long> organisationIds;
  private final String reason;

  private AccessScope(
      Kind kind,
      Long principalId,
      boolean admin,
      boolean allOrganisations,
      Set<Long> organisationIds,
      String reason) {
    this.kind = kind;
    this.principalId = principalId;
    this.admin = admin;
    this.allOrganisations = allOrganisations;
    this.organisationIds = Set.copyOf(organisationIds);
    this.reason = reason;
  }

  public static AccessScope customer(long userId, long organisationId) {
    return new AccessScope(Kind.CUSTOMER, userId, false, false, Set.of(organisationId), null);
  }

  /** An admin: every organisation, plus configuration rights. */
  public static AccessScope admin(long userId) {
    return new AccessScope(Kind.SUPPORTER, userId, true, true, Set.of(), null);
  }

  /** An agent with every organisation ({@code all}) or only {@code organisationIds}. */
  public static AccessScope agent(long userId, boolean all, Set<Long> organisationIds) {
    Objects.requireNonNull(organisationIds, "organisationIds");
    return new AccessScope(
        Kind.SUPPORTER, userId, false, all, all ? Set.of() : organisationIds, null);
  }

  /** An API key bound to one organisation. */
  public static AccessScope apiKey(long keyId, long organisationId) {
    return new AccessScope(Kind.API_KEY, keyId, false, false, Set.of(organisationId), null);
  }

  /**
   * Every organisation, for scheduled jobs and the mail poller only. The reason is logged so each
   * use of unscoped access can be traced; it must not contain personal data. ArchitectureTest fails
   * the build if a class outside the access module and the job/poller packages calls this.
   */
  public static AccessScope system(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("a SYSTEM scope needs a reason");
    }
    log.info("SYSTEM access scope created: {}", reason);
    return new AccessScope(Kind.SYSTEM, null, false, true, Set.of(), reason);
  }

  public Kind kind() {
    return kind;
  }

  /** User id (customer, supporter) or key id (API key); empty for SYSTEM. */
  public Optional<Long> principalId() {
    return Optional.ofNullable(principalId);
  }

  public boolean isAdmin() {
    return admin;
  }

  public boolean isSupporter() {
    return kind == Kind.SUPPORTER;
  }

  public boolean isSystem() {
    return kind == Kind.SYSTEM;
  }

  /** May this scope see rows of {@code organisationId}? */
  public boolean canSee(long organisationId) {
    return allOrganisations || organisationIds.contains(organisationId);
  }

  /**
   * May this scope see rows that belong to no organisation yet (the triage queue)? Every supporter
   * in v1 and system jobs; never customers or API keys.
   */
  public boolean canSeeTriage() {
    return kind == Kind.SUPPORTER || kind == Kind.SYSTEM;
  }

  /** Empty means every organisation; otherwise the exact set (possibly empty = none). */
  public Optional<Set<Long>> organisationIdsOrAll() {
    return allOrganisations ? Optional.empty() : Optional.of(organisationIds);
  }

  /** Throws 404 when this scope may not see {@code organisationId}. */
  public void require(long organisationId) {
    if (!canSee(organisationId)) {
      throw notFound();
    }
  }

  /** Throws 403 unless this is an admin (defence in depth behind the /api/v1/admin gate). */
  public void requireAdmin() {
    if (!admin) {
      throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this.");
    }
  }

  /** Throws 403 unless this is a supporter or a system job. */
  public void requireSupporterOrSystem() {
    if (kind != Kind.SUPPORTER && kind != Kind.SYSTEM) {
      throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this.");
    }
  }

  /** The one answer for an id this scope may not see or that does not exist. */
  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", "We could not find that.");
  }

  @Override
  public String toString() {
    return "AccessScope["
        + kind
        + (principalId == null ? "" : ", id=" + principalId)
        + (admin ? ", admin" : "")
        + (allOrganisations ? ", all" : ", orgs=" + organisationIds)
        + (reason == null ? "" : ", reason=" + reason)
        + "]";
  }
}
