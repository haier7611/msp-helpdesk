package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.notification.NotificationKind;
import com.ticketsystem.api.ticket.assignment.AutoAssignmentService.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Auto-assignment (spec 4.6): request-type default beats organisation default; an out-of-office
 * default leaves the ticket Unassigned with an internal system entry; an inactive or no-access
 * default is ignored; runs once per ticket; clearing a deactivated supporter. Also the
 * out-of-office API.
 */
class AutoAssignmentIntegrationTest extends SlaTestBase {

  @org.springframework.beans.factory.annotation.Autowired
  com.ticketsystem.api.ticket.DeactivationTicketReassignment reassignment;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  private long org;
  private AppUser customer;
  private long typeId;

  @BeforeEach
  void setUp() {
    org = londonOrganisation("Acme Dental");
    customer = activeUser(Role.CUSTOMER, org);
    typeId =
        jdbc.queryForObject(
            "INSERT INTO request_type (name, default_priority, portal_visible, created_at) VALUES"
                + " (?, 'P3', FALSE, now()) RETURNING id",
            Long.class,
            "Hardware " + java.util.UUID.randomUUID().toString().replace("-", ""));
    clock.reset();
  }

  private long newTicket(long requestType) {
    return tickets
        .create(
            admin,
            new com.ticketsystem.api.ticket.TicketService.NewTicket(
                org,
                customer.id(),
                null,
                requestType,
                null,
                null,
                null,
                com.ticketsystem.api.ticket.TicketSource.PORTAL,
                "Monitor flickers",
                "<p>Since Monday.</p>",
                List.of(),
                null,
                null,
                null,
                null))
        .ticketId();
  }

  private Long assigneeOf(long id) {
    return jdbc.queryForObject("SELECT assignee_user_id FROM ticket WHERE id = ?", Long.class, id);
  }

  @Test
  void theRequestTypeDefaultWinsOverTheOrganisationDefault() {
    AppUser orgDefault = restrictedAgent(org);
    AppUser typeDefault = restrictedAgent(org);
    slaConfig.setDefaultAssignee(admin, org, orgDefault.id(), null);
    slaConfig.setRequestTypeDefaultAssignee(admin, typeId, typeDefault.id(), null);

    long typed = newTicket(typeId);
    long general = newTicket(generalType);
    assertThat(assigneeOf(typed)).isEqualTo(typeDefault.id());
    assertThat(assigneeOf(general)).isEqualTo(orgDefault.id());
    // Assigned by the system: the ticket stays New, and the assignee is told.
    assertThat(statusOf(typed)).isEqualTo(com.ticketsystem.api.sla.TicketStatus.NEW);
    assertThat(notifications(typed, NotificationKind.TICKET_ASSIGNED, typeDefault.id()))
        .isEqualTo(1);
    // Runs once: a second attempt changes nothing.
    assertThat(autoAssignment.applyInNewTransaction(SYSTEM, typed))
        .isEqualTo(Result.ALREADY_CHECKED);
  }

  /** WP-16: an auto-assignment is one timeline line naming the rule, by the system. */
  @Test
  void autoAssignmentAddsOneTimelineLineNamingTheRule() {
    clock.set(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    AppUser orgDefault = restrictedAgent(org);
    AppUser typeDefault = restrictedAgent(org);
    jdbc.update("UPDATE app_user SET name = 'Alex Example' WHERE id = ?", orgDefault.id());
    jdbc.update("UPDATE app_user SET name = 'Jo Example' WHERE id = ?", typeDefault.id());
    slaConfig.setDefaultAssignee(admin, org, orgDefault.id(), null);
    slaConfig.setRequestTypeDefaultAssignee(admin, typeId, typeDefault.id(), null);
    String orgName =
        jdbc.queryForObject("SELECT name FROM organisation WHERE id = ?", String.class, org);
    String typeName =
        jdbc.queryForObject("SELECT name FROM request_type WHERE id = ?", String.class, typeId);

    long typed = newTicket(typeId);
    long general = newTicket(generalType);

    var typedLines = queries.detail(admin, typed).assignmentEntries();
    assertThat(typedLines).hasSize(1);
    assertThat(typedLines.get(0).actorUserId()).isNull();
    assertThat(typedLines.get(0).toUserId()).isEqualTo(typeDefault.id());
    assertThat(typedLines.get(0).reason()).isEqualTo("DEFAULT_REQUEST_TYPE");
    assertThat(typedLines.get(0).createdAt()).isEqualTo(clock.instant());
    assertThat(typedLines.get(0).text())
        .isEqualTo("Assigned to Jo Example automatically (default for " + typeName + ")");

    var generalLines = queries.detail(admin, general).assignmentEntries();
    assertThat(generalLines).hasSize(1);
    assertThat(generalLines.get(0).reason()).isEqualTo("DEFAULT_ORGANISATION");
    assertThat(generalLines.get(0).text())
        .isEqualTo("Assigned to Alex Example automatically (default for " + orgName + ")");
  }

  @Test
  void anOutOfOfficeDefaultLeavesTheTicketUnassignedWithASystemEntry() {
    AppUser away = restrictedAgent(org);
    slaConfig.setDefaultAssignee(admin, org, away.id(), null);
    java.time.LocalDate today = clock.instant().atZone(LONDON).toLocalDate();
    java.time.LocalDate later = today.plusDays(5);
    preferences(away.id(), false, "Asia/Ho_Chi_Minh", true, today.toString(), later.toString());

    long id = newTicket(generalType);
    assertThat(assigneeOf(id)).isNull();
    List<Map<String, Object>> entries =
        jdbc.queryForList("SELECT kind, body_text FROM system_entry WHERE ticket_id = ?", id);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("kind")).isEqualTo("AUTO_ASSIGN_SKIPPED");
    assertThat(entries.get(0).get("body_text").toString())
        .isEqualTo(
            "Not assigned to Sam Example: out of office until "
                + later.format(
                    java.time.format.DateTimeFormatter.ofPattern(
                        "d MMMM yyyy", java.util.Locale.UK)));

    // Open-ended out of office (no last day).
    preferences(away.id(), false, "Asia/Ho_Chi_Minh", true, null, null);
    long open = newTicket(generalType);
    assertThat(
            jdbc.queryForObject(
                "SELECT body_text FROM system_entry WHERE ticket_id = ?", String.class, open))
        .isEqualTo("Not assigned to Sam Example: out of office");

    // Out of office that starts tomorrow does not count yet; switched off: assigned again.
    preferences(away.id(), false, "Asia/Ho_Chi_Minh", true, today.plusDays(1).toString(), null);
    assertThat(assigneeOf(newTicket(generalType))).isEqualTo(away.id());
    preferences(away.id(), false, "Asia/Ho_Chi_Minh", false, today.toString(), later.toString());
    assertThat(assigneeOf(newTicket(generalType))).isEqualTo(away.id());
  }

  @Test
  void anInactiveOrNoAccessDefaultIsIgnoredAndTheNextOneTried() {
    AppUser noAccess = restrictedAgent(beta);
    AppUser orgDefault = restrictedAgent(org);
    slaConfig.setDefaultAssignee(admin, org, orgDefault.id(), null);
    slaConfig.setRequestTypeDefaultAssignee(admin, typeId, noAccess.id(), null);
    assertThat(assigneeOf(newTicket(typeId))).isEqualTo(orgDefault.id());

    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", orgDefault.id());
    assertThat(assigneeOf(newTicket(generalType))).isNull();
  }

  @Test
  void deactivatingASupporterClearsThemFromEveryDefault() throws Exception {
    AppUser leaver = restrictedAgent(org);
    slaConfig.setDefaultAssignee(admin, org, leaver.id(), null);
    slaConfig.setRequestTypeDefaultAssignee(admin, typeId, leaver.id(), null);
    // As WP-11a's deactivation does: inside its transaction.
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> reassignment.reassignOpenTickets(admin, leaver.id(), null, adminUser.id()));
    assertThat(slaConfig.settings(admin, org).defaultAssigneeUserId()).isNull();
    assertThat(slaConfig.requestTypeDefaultAssignee(admin, typeId)).isEmpty();
  }

  @Test
  void clearingADeactivatedSupporterRemovesThemFromEveryDefault() {
    AppUser leaver = restrictedAgent(org);
    slaConfig.setDefaultAssignee(admin, org, leaver.id(), null);
    slaConfig.setRequestTypeDefaultAssignee(admin, typeId, leaver.id(), null);
    assertThat(slaConfig.clearDefaultAssignee(admin, leaver.id())).isEqualTo(2);
    assertThat(slaConfig.settings(admin, org).defaultAssigneeUserId()).isNull();
    assertThat(slaConfig.requestTypeDefaultAssignee(admin, typeId)).isEmpty();
  }

  @Test
  void anAgentCreatingATicketForSomeoneElseNotifiesThemButNotThemselves() {
    AppUser other = restrictedAgent(acme);
    long id =
        tickets
            .create(
                acmeAgent,
                new com.ticketsystem.api.ticket.TicketService.NewTicket(
                    acme,
                    acmeCustomerUser.id(),
                    null,
                    generalType,
                    null,
                    null,
                    null,
                    com.ticketsystem.api.ticket.TicketSource.AGENT,
                    "Phone call: VPN down",
                    "<p>Called in.</p>",
                    List.of(),
                    null,
                    other.id(),
                    null,
                    null))
            .ticketId();
    assertThat(notifications(id, NotificationKind.TICKET_ASSIGNED, other.id())).isEqualTo(1);
    assertThat(notifications(id, NotificationKind.TICKET_ASSIGNED, acmeAgentUser.id())).isZero();
  }
}
