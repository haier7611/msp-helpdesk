package com.ticketsystem.api.portal;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * My account in the customer portal: the signed-in customer reads their own name, email and
 * organisation and changes their name. Password change is the shared {@code
 * /api/v1/auth/password/change} endpoint; magic-link sign-in is separate. Microsoft sign-in is
 * reported as unavailable (off by default), so the page hides it.
 */
@Service
public class PortalAccountService {

  static final int MAX_NAME = 200;

  /** What My account shows. No ticket, SLA or other organisation data. */
  public record Account(
      String name,
      String email,
      String organisationName,
      boolean microsoftSignInAvailable,
      boolean microsoftLinked) {}

  private final UserRepository users;
  private final OrganisationRepository organisations;
  private final Clock clock;

  public PortalAccountService(
      UserRepository users, OrganisationRepository organisations, Clock clock) {
    this.users = users;
    this.organisations = organisations;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Account get(AccessScope scope) {
    AppUser me = me(scope);
    long org = organisationOf(scope);
    String orgName = organisations.find(scope, org).orElseThrow(AccessScope::notFound).name();
    // Placeholders until the real switch and the user's (tid, oid) link exist.
    return new Account(me.name(), me.email(), orgName, false, false);
  }

  @Transactional
  public Account rename(AccessScope scope, String name) {
    AppUser me = me(scope);
    String clean = name == null ? "" : name.strip().replaceAll("\\p{Cntrl}", " ");
    if (clean.isEmpty()) {
      throw ApiException.invalidRequest("Enter your name.");
    }
    if (clean.length() > MAX_NAME) {
      throw ApiException.invalidRequest("Your name can be at most 200 characters.");
    }
    users.updateName(me.id(), clean, clock.instant());
    return get(scope);
  }

  private AppUser me(AccessScope scope) {
    if (scope.kind() != AccessScope.Kind.CUSTOMER) {
      throw AccessScope.notFound();
    }
    long id = scope.principalId().orElseThrow(AccessScope::notFound);
    return users.findById(id).orElseThrow(AccessScope::notFound);
  }

  private static long organisationOf(AccessScope scope) {
    return scope.organisationIdsOrAll().orElseThrow(AccessScope::notFound).iterator().next();
  }
}
