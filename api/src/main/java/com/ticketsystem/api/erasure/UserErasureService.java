package com.ticketsystem.api.erasure;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.erasure.clientdata.ErasureRepository;
import com.ticketsystem.api.erasure.clientdata.ErasureRepository.Target;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin "Erase personal data". Pseudonymises a user: name becomes "Former user", the email address
 * is removed everywhere it is stored, every session, device, link, photo and profile line goes, and
 * the account can never be reactivated. Ticket history, comment bodies and attachments stay, so
 * tickets still read correctly. The erasure log (ids only) is replayed after any backup restore.
 *
 * <p>Guards: an admin cannot erase themselves; a supporter must be deactivated first (that flow
 * reassigns their open tickets and keeps the last-admin guard); a customer can be erased in any
 * state. Step-up is enforced on the endpoint, and every erasure is audit-logged without the erased
 * name or address.
 */
@Service
public class UserErasureService {

  private final ErasureRepository repo;
  private final AuditService audit;
  private final Clock clock;

  public UserErasureService(ErasureRepository repo, AuditService audit, Clock clock) {
    this.repo = repo;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public void erase(AccessScope scope, long userId, String clientIp) {
    if (!scope.isAdmin()) {
      throw AccessScope.notFound();
    }
    long actor = scope.principalId().orElseThrow(AccessScope::notFound);
    if (actor == userId) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "own_account", "You cannot erase your own account.");
    }
    Target t = repo.lock(scope, userId).orElseThrow(AccessScope::notFound);
    if (t.erased()) {
      throw new ApiException(
          HttpStatus.CONFLICT, "already_erased", "This person's data has already been erased.");
    }
    if (!"CUSTOMER".equals(t.role()) && !"DEACTIVATED".equals(t.status())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "deactivate_first",
          "Deactivate this agent first, so their open tickets are reassigned.");
    }
    repo.erase(scope, userId, actor, clock.instant());
    audit.record(ErasureAuditEvent.USER_ERASED, actor, userId, clientIp, Map.of("role", t.role()));
  }
}
