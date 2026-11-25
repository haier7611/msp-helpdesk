package com.ticketsystem.api.supporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * The deactivation wizard's ticket hook contract (WP-11a): whichever {@link TicketReassignment}
 * bean is present (here a recording one marked primary over WP-04's real adapter) gives step 1 its
 * open-ticket count and is called by step 2 inside the deactivation transaction with the chosen
 * target; a failing hook rolls the deactivation back. The real adapter is tested in
 * DeactivationReassignmentIntegrationTest; {@link TicketReassignment#NONE} in its own unit test.
 */
@Import(TicketReassignmentHookIntegrationTest.RecordingHookConfig.class)
class TicketReassignmentHookIntegrationTest extends OrganisationTestBase {

  record Call(long from, Long to, long actor, boolean inTransaction) {}

  static class RecordingHook implements TicketReassignment {
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile boolean fail;

    @Override
    public int openTicketCount(AccessScope scope, long supporterId) {
      return 3;
    }

    @Override
    public int reassignOpenTickets(
        AccessScope scope, long fromSupporterId, Long toSupporterId, long actorUserId) {
      calls.add(
          new Call(
              fromSupporterId,
              toSupporterId,
              actorUserId,
              TransactionSynchronizationManager.isActualTransactionActive()));
      if (fail) {
        throw new IllegalStateException("synthetic hook failure");
      }
      return 3;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingHookConfig {
    @Bean
    @Primary
    RecordingHook recordingHook() {
      return new RecordingHook();
    }
  }

  @Autowired RecordingHook hook;
  @Autowired SupporterAdminService service;

  @BeforeEach
  void resetHook() {
    hook.calls.clear();
    hook.fail = false;
  }

  @Test
  void step1ShowsTheOpenTicketsAndStep2ReassignsThemInTheSameTransaction() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser leaving = activeUser(Role.AGENT);
    AppUser taker = activeUser(Role.AGENT);
    Browser b = signedIn(admin);
    String base = "/api/v1/admin/supporters/" + leaving.id();

    JsonNode plan = json(b.get(base + "/deactivation").getContentAsString());
    assertThat(plan.get("openTickets").asInt()).isEqualTo(3);
    assertThat(json(b.get(base).getContentAsString()).get("openTickets").asInt()).isEqualTo(3);

    assertThat(b.post(base + "/deactivate", "{\"reassignTo\":" + taker.id() + "}").getStatus())
        .isEqualTo(200);
    assertThat(hook.calls).containsExactly(new Call(leaving.id(), taker.id(), admin.id(), true));
    assertThat(auditEvents("SUPPORTER_DEACTIVATED", leaving.id()).get(0).get("details").toString())
        .contains("\"tickets\": 3")
        .contains("\"reassigned_to\": " + taker.id());
  }

  @Test
  void unassignedIsPassedAsNull() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser leaving = activeUser(Role.AGENT);
    Browser b = signedIn(admin);
    assertThat(b.post("/api/v1/admin/supporters/" + leaving.id() + "/deactivate", "{}").getStatus())
        .isEqualTo(200);
    assertThat(hook.calls).containsExactly(new Call(leaving.id(), null, admin.id(), true));
  }

  @Test
  void theNoOpHookReportsNoTicketsAndMovesNothing() {
    AccessScope scope = AccessScope.admin(1);
    assertThat(TicketReassignment.NONE.openTicketCount(scope, 2)).isZero();
    assertThat(TicketReassignment.NONE.reassignOpenTickets(scope, 2, null, 1)).isZero();
  }

  @Test
  void aFailingHookRollsTheDeactivationBack() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser leaving = activeUser(Role.AGENT);
    Browser leavingSession = signedIn(leaving);
    hook.fail = true;
    try {
      service.deactivate(AccessScope.admin(admin.id()), leaving.id(), null, null);
      throw new AssertionError("expected the hook failure");
    } catch (IllegalStateException expected) {
      // rolled back
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM app_user WHERE id = ?", String.class, leaving.id()))
        .isEqualTo("ACTIVE");
    assertThat(leavingSession.get("/api/v1/auth/me").getStatus()).isEqualTo(200);
  }
}
