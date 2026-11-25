package com.ticketsystem.api.contact;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.Emails;
import com.ticketsystem.api.auth.InvitationService;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.contact.clientdata.ContactRepository;
import com.ticketsystem.api.contact.clientdata.ContactRepository.AccountRow;
import com.ticketsystem.api.contact.clientdata.ContactRepository.ContactRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contacts for the agent UI: supporters search, create and invite customer contacts of the
 * organisations they work. Another organisation's contact answers 404; creating an organisation
 * stays admin-only.
 */
@Service
public class ContactService {

  public static final int MAX_PAGE = 100;

  /** A contact as the agent UI shows it. */
  public record ContactView(
      long id,
      String name,
      String email,
      long organisationId,
      String organisationName,
      String status) {}

  public record ContactPage(List<ContactView> items, long total) {}

  public record Invited(Instant expiresAt) {}

  private final ContactRepository contacts;
  private final InvitationService invitations;
  private final AuditService audit;
  private final Clock clock;

  public ContactService(
      ContactRepository contacts, InvitationService invitations, AuditService audit, Clock clock) {
    this.contacts = contacts;
    this.invitations = invitations;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ContactPage search(
      AccessScope scope, String search, Long organisationId, int page, int limit) {
    if (search != null && search.length() > 200) {
      throw ApiException.invalidRequest("Search text can be up to 200 characters.");
    }
    if (page < 0) {
      throw ApiException.invalidRequest("page starts at 0.");
    }
    int size = Math.max(1, Math.min(limit, MAX_PAGE));
    List<ContactView> items =
        contacts
            .search(scope, search, organisationId, size, Math.multiplyExact(page, size))
            .stream()
            .map(ContactService::view)
            .toList();
    return new ContactPage(items, contacts.count(scope, search, organisationId));
  }

  /** A new contact (INVITED, cannot sign in until invited); optionally sends the invitation. */
  @Transactional
  public ContactView create(
      AccessScope scope,
      Long organisationId,
      String rawName,
      String rawEmail,
      boolean invite,
      String clientIp) {
    requireSupporter(scope);
    if (organisationId == null) {
      throw ApiException.invalidRequest("Choose the contact's organisation.");
    }
    scope.require(organisationId);
    String email =
        Emails.normalise(rawEmail)
            .orElseThrow(() -> ApiException.invalidRequest("Enter a valid email address."));
    String name = rawName == null ? "" : rawName.strip();
    if (name.isEmpty() || name.length() > 200) {
      throw ApiException.invalidRequest("Enter a name of up to 200 characters.");
    }
    if (contacts.accountByEmail(scope, email).isPresent()) {
      throw contactExists();
    }
    long id = insert(scope, organisationId, email, name, "agent", clientIp);
    if (invite) {
      invitations.invite(actor(scope), email, name, Role.CUSTOMER, organisationId, clientIp);
    }
    return view(contacts.find(scope, id).orElseThrow(AccessScope::notFound));
  }

  /** (Re)sends the invitation to a contact who has not accepted one yet. */
  @Transactional
  public Invited invite(AccessScope scope, long contactId, String clientIp) {
    requireSupporter(scope);
    ContactRow c = contacts.find(scope, contactId).orElseThrow(AccessScope::notFound);
    if (!"INVITED".equals(c.status())) {
      throw new ApiException(
          HttpStatus.CONFLICT, "already_active", "This contact already has an account.");
    }
    InvitationService.Created created =
        invitations.invite(
            actor(scope), c.email(), c.name(), Role.CUSTOMER, c.organisationId(), clientIp);
    return new Invited(created.expiresAt());
  }

  /**
   * The triage "Save sender as a contact" tick: an existing customer of the same organisation is
   * reused; an address held by a supporter or another organisation's customer is refused (409);
   * otherwise a new INVITED contact is created, named after the address. Runs in the caller's
   * transaction.
   */
  @Transactional
  public long saveFromTriage(
      AccessScope scope, long organisationId, String rawEmail, String clientIp) {
    requireSupporter(scope);
    scope.require(organisationId);
    String email =
        Emails.normalise(rawEmail)
            .orElseThrow(
                () -> ApiException.invalidRequest("This ticket has no sender address to save."));
    Optional<AccountRow> existing = contacts.accountByEmail(scope, email);
    if (existing.isPresent()) {
      AccountRow a = existing.get();
      if ("CUSTOMER".equals(a.role())
          && a.organisationId() != null
          && a.organisationId() == organisationId
          && !"DEACTIVATED".equals(a.status())) {
        return a.id();
      }
      throw contactExists();
    }
    return insert(scope, organisationId, email, Emails.localPart(email), "triage", clientIp);
  }

  /** Is {@code rawEmail} already an active or invited contact of any visible organisation? */
  @Transactional(readOnly = true)
  public boolean isKnownAddress(AccessScope scope, String rawEmail) {
    return Emails.normalise(rawEmail).flatMap(e -> contacts.accountByEmail(scope, e)).isPresent();
  }

  private long insert(
      AccessScope scope,
      long organisationId,
      String email,
      String name,
      String reason,
      String clientIp) {
    long id;
    try {
      id = contacts.insert(scope, organisationId, email, name, clock.instant());
    } catch (DuplicateKeyException e) {
      throw contactExists();
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("organisation", organisationId);
    details.put("reason", reason);
    audit.record(ContactAuditEvent.CONTACT_CREATED, actor(scope), id, clientIp, details);
    return id;
  }

  private static ApiException contactExists() {
    return new ApiException(
        HttpStatus.CONFLICT, "contact_exists", "Someone with this email already has an account.");
  }

  private static Long actor(AccessScope scope) {
    return scope.principalId().orElseThrow(AccessScope::notFound);
  }

  private static void requireSupporter(AccessScope scope) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
  }

  private static ContactView view(ContactRow r) {
    return new ContactView(
        r.id(), r.name(), r.email(), r.organisationId(), r.organisationName(), r.status());
  }
}
