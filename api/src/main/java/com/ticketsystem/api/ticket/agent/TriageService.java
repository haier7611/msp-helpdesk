package com.ticketsystem.api.ticket.agent;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.Emails;
import com.ticketsystem.api.contact.ContactService;
import com.ticketsystem.api.organisation.OrganisationAuditEvent;
import com.ticketsystem.api.organisation.VerifiedDomains;
import com.ticketsystem.api.organisation.clientdata.OrganisationRepository;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.ticket.TicketService;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.Extra;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ReporterReason;
import com.ticketsystem.api.ticket.clientdata.TicketRepository;
import com.ticketsystem.api.ticket.clientdata.TicketRepository.TicketRow;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigning an Email triage ticket to an organisation with the two optional "remember" ticks: save
 * the sender as a contact of that organisation, and add the sender's domain as a verified domain
 * (never a public email domain). Everything happens in one transaction, and every refusal is
 * checked before anything changes.
 */
@Service
public class TriageService {

  /** What the triage dialog may offer. */
  public record TriageOptions(
      String senderEmail, String senderDomain, boolean domainOffered, boolean contactExists) {}

  public record TriageResult(String reference, Long contactId) {}

  private final TicketRepository tickets;
  private final TicketService ticketService;
  private final ContactService contacts;
  private final OrganisationRepository organisations;
  private final VerifiedDomains domains;
  private final AuditService audit;
  private final TicketHistoryRepository history;
  private final Clock clock;

  public TriageService(
      TicketRepository tickets,
      TicketService ticketService,
      ContactService contacts,
      OrganisationRepository organisations,
      VerifiedDomains domains,
      AuditService audit,
      TicketHistoryRepository history,
      Clock clock) {
    this.tickets = tickets;
    this.ticketService = ticketService;
    this.contacts = contacts;
    this.organisations = organisations;
    this.domains = domains;
    this.audit = audit;
    this.history = history;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public TriageOptions options(AccessScope scope, long ticketId) {
    TicketRow t = triageTicket(scope, ticketId);
    String email = Emails.normalise(t.reporterEmail()).orElse(null);
    String domain = domainOf(email);
    boolean offered =
        domain != null
            && domains.check(domain) instanceof VerifiedDomains.Check.Valid
            && !organisations.domainTaken(scope, domain);
    boolean known = email != null && contacts.isKnownAddress(scope, email);
    return new TriageOptions(email, domain, offered, known);
  }

  @Transactional
  public TriageResult assign(
      AccessScope scope,
      long ticketId,
      Long organisationId,
      boolean saveContact,
      boolean addDomain,
      String clientIp) {
    if (organisationId == null) {
      throw ApiException.invalidRequest("Choose an organisation.");
    }
    scope.require(organisationId);
    TicketRow t = triageTicket(scope, ticketId);
    String email = Emails.normalise(t.reporterEmail()).orElse(null);
    String domain = null;
    if (addDomain) {
      String senderDomain = domainOf(email);
      if (senderDomain == null) {
        throw ApiException.invalidRequest("This ticket has no sender domain to add.");
      }
      VerifiedDomains.Check check = domains.check(senderDomain);
      if (check instanceof VerifiedDomains.Check.Invalid invalid) {
        throw new ApiException(HttpStatus.BAD_REQUEST, invalid.code(), invalid.message());
      }
      domain = ((VerifiedDomains.Check.Valid) check).domain();
      if (organisations.domainTaken(scope, domain)) {
        throw domainTaken(domain);
      }
    }
    if (saveContact && email == null) {
      throw ApiException.invalidRequest("This ticket has no sender address to save.");
    }

    TicketReference reference = ticketService.changeOrganisation(scope, ticketId, organisationId);
    Long contactId = null;
    if (saveContact) {
      contactId = contacts.saveFromTriage(scope, organisationId, email, clientIp);
      TicketRow moved = tickets.lock(scope, ticketId).orElseThrow(AccessScope::notFound);
      if (!contactId.equals(moved.reporterUserId())) {
        tickets.updateReporter(scope, moved, contactId, clock.instant());
        // The reporter change is a timeline line (user ids, names resolved when read).
        history.record(
            scope,
            organisationId,
            ticketId,
            HistoryType.REPORTER,
            scope.principalId().orElse(null),
            moved.reporterUserId() == null ? null : moved.reporterUserId().toString(),
            contactId.toString(),
            clock.instant(),
            Extra.reason(ReporterReason.TRIAGE_CONTACT));
      }
    }
    if (domain != null) {
      try {
        organisations.insertDomainFromTriage(scope, organisationId, domain, clock.instant());
      } catch (DuplicateKeyException e) {
        throw domainTaken(domain);
      }
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("organisation", organisationId);
      details.put("domain", domain);
      details.put("reason", "triage");
      details.put("ticket", ticketId);
      audit.record(
          OrganisationAuditEvent.ORGANISATION_DOMAIN_ADDED,
          scope.principalId().orElse(null),
          null,
          clientIp,
          details);
    }
    return new TriageResult(reference.toString(), contactId);
  }

  private TicketRow triageTicket(AccessScope scope, long ticketId) {
    if (!scope.isSupporter()) {
      throw AccessScope.notFound();
    }
    TicketRow t = tickets.find(scope, ticketId).orElseThrow(AccessScope::notFound);
    if (t.organisationId() != null) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_in_triage", "This ticket already belongs to an organisation.");
    }
    return t;
  }

  private static ApiException domainTaken(String domain) {
    return new ApiException(
        HttpStatus.CONFLICT,
        "domain_taken",
        domain + " is already a verified domain of an organisation.");
  }

  private static String domainOf(String email) {
    if (email == null) {
      return null;
    }
    int at = email.lastIndexOf('@');
    return at < 0 || at == email.length() - 1 ? null : email.substring(at + 1);
  }
}
