package com.ticketsystem.api.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketService.NewTicket;
import com.ticketsystem.api.ticket.agent.AgentTicketQueryService;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import com.ticketsystem.api.ticket.attachment.AttachmentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Helpers for the WP-04 integration tests: two synthetic organisations (Acme Dental, Beta Clinic)
 * with a customer each, an agent limited to Acme, an admin, and ticket shortcuts through the real
 * services.
 */
public abstract class TicketTestBase extends OrganisationTestBase {

  @Autowired protected TicketService tickets;
  @Autowired protected InternalNoteService notes;
  @Autowired protected AgentTicketQueryService queries;
  @Autowired protected ReferenceService references;
  @Autowired protected AttachmentService attachments;
  @Autowired protected TicketEventRecorder recorder;

  protected long acme;
  protected long beta;
  protected AppUser acmeCustomerUser;
  protected AppUser betaCustomerUser;
  protected AppUser acmeAgentUser;
  protected AppUser adminUser;
  protected AccessScope acmeCustomer;
  protected AccessScope betaCustomer;
  protected AccessScope acmeAgent;
  protected AccessScope admin;
  protected long generalType;
  protected long alertType;

  @BeforeEach
  void ticketData() {
    recorder.clear();
    acme = newOrganisation("Acme Dental");
    beta = newOrganisation("Beta Clinic");
    acmeCustomerUser = activeUser(Role.CUSTOMER, acme);
    betaCustomerUser = activeUser(Role.CUSTOMER, beta);
    acmeAgentUser = restrictedAgent(acme);
    adminUser = activeUser(Role.ADMIN);
    acmeCustomer = AccessScope.customer(acmeCustomerUser.id(), acme);
    betaCustomer = AccessScope.customer(betaCustomerUser.id(), beta);
    acmeAgent = restrictedScope(acmeAgentUser, acme);
    admin = AccessScope.admin(adminUser.id());
    generalType =
        jdbc.queryForObject(
            "SELECT id FROM request_type WHERE name = 'Software and applications'", Long.class);
    alertType = jdbc.queryForObject("SELECT id FROM request_type WHERE name = 'Alert'", Long.class);
  }

  /** A ticket raised by an agent for {@code reporter} in {@code org}. */
  protected Created ticket(AccessScope scope, long org, AppUser reporter) {
    return tickets.create(
        scope,
        new NewTicket(
            org,
            reporter.id(),
            null,
            generalType,
            null,
            Impact.JUST_ME,
            Urgency.PARTLY,
            TicketSource.AGENT,
            "Outlook keeps asking for a password",
            "<p>Since this morning.</p>",
            List.of(),
            null,
            null,
            null,
            null));
  }

  protected Created acmeTicket() {
    return ticket(admin, acme, acmeCustomerUser);
  }

  protected Created betaTicket() {
    return ticket(admin, beta, betaCustomerUser);
  }

  /** A triage ticket from an unknown sender (as WP-07 will create it). */
  protected Created triageTicket() {
    return tickets.create(
        admin,
        new NewTicket(
            null,
            null,
            "unknown.sender@public-mail.example",
            generalType,
            null,
            null,
            null,
            TicketSource.EMAIL,
            "Printer on fire",
            "<p>Help</p>",
            List.of(),
            null,
            null,
            null,
            null));
  }

  protected TicketStatus statusOf(long ticketId) {
    return TicketStatus.valueOf(
        jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, ticketId));
  }

  /** Forces a status straight in the database (to set up a starting point). */
  protected void forceStatus(long ticketId, TicketStatus status) {
    jdbc.update("UPDATE ticket SET status = ? WHERE id = ?", status.name(), ticketId);
  }

  /** Uploads through the real service as {@code scope}'s user. */
  protected UUID upload(AccessScope scope, String name, byte[] bytes) {
    return attachments.upload(scope, name, bytes).id();
  }

  /** A multipart upload through MockMvc with the browser's cookies and CSRF header. */
  protected MockHttpServletResponse uploadVia(Browser b, String path, String name, byte[] bytes)
      throws Exception {
    if (b.cookie(Browser.CSRF_COOKIE) == null) {
      b.get("/api/v1/auth/csrf");
    }
    return b.perform(
        MockMvcRequestBuilders.multipart(path)
            .file(new MockMultipartFile("file", name, "application/octet-stream", bytes)),
        HttpMethod.POST);
  }
}
