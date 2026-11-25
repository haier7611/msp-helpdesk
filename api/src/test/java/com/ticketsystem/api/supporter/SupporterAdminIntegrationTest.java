package com.ticketsystem.api.supporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.RecordingSystemMailer;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.security.SecurityGoLive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Admin - Manage supporters (WP-11a, spec section 7 and 12, C18): every action through the real
 * security chain, the role gate, step-up on role change only, the self and last-admin guards
 * (including two admins acting on each other at once), session end on role/access change and
 * deactivation (security test 4), invitations (no code, ADR-046), and the activity trail.
 */
@SecurityGoLive({4})
class SupporterAdminIntegrationTest extends OrganisationTestBase {

  @Autowired SupporterAdminService service;
  @Autowired TransactionTemplate tx;

  /** Admins this test deactivated to control the active-admin count; restored afterwards. */
  private final List<Long> parkedAdmins = new ArrayList<>();

  @AfterEach
  void restoreParkedAdmins() {
    for (Long id : parkedAdmins) {
      jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", id);
    }
    parkedAdmins.clear();
  }

  /** Makes {@code keep} the only active admins in the shared database for this test. */
  private void onlyActiveAdmins(AppUser... keep) {
    List<Long> ids = new ArrayList<>();
    for (AppUser u : keep) {
      ids.add(u.id());
    }
    List<Long> others =
        jdbc.queryForList(
            "SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE'", Long.class);
    for (Long id : others) {
      if (!ids.contains(id)) {
        jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", id);
        parkedAdmins.add(id);
      }
    }
  }

  private static String base(long id) {
    return "/api/v1/admin/supporters/" + id;
  }

  private static boolean ended(Browser b) throws Exception {
    return b.get("/api/v1/auth/me").getStatus() == 401;
  }

  private String role(AppUser u) {
    return jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, u.id());
  }

  private String status(AppUser u) {
    return jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, u.id());
  }

  /** Moves the clock past the 10-minute step-up window that the sign-in TOTP opened. */
  private void staleStepUp() {
    clock.advance(Duration.ofMinutes(11));
  }

  private void stepUp(Browser b, AppUser who) throws Exception {
    assertThat(
            b.post("/api/v1/auth/step-up", "{\"code\":" + Browser.q(totp(who)) + "}").getStatus())
        .isEqualTo(200);
  }

  @Test
  void agentsAndCustomersGet403OnEveryManageSupportersEndpoint() throws Exception {
    AppUser target = activeUser(Role.AGENT);
    for (Role r : new Role[] {Role.AGENT, Role.CUSTOMER}) {
      Browser b = signedIn(activeUser(r));
      String id = base(target.id());
      List<MockHttpServletResponse> responses =
          List.of(
              b.get("/api/v1/admin/supporters"),
              b.get(id),
              b.get(id + "/activity"),
              b.get(id + "/deactivation"),
              b.post(
                  "/api/v1/admin/supporters/invitations",
                  "{\"email\":\"x@example.test\",\"name\":\"X\",\"role\":\"AGENT\","
                      + "\"allOrganisations\":true}"),
              b.put(id, "{\"name\":\"X\"}"),
              b.put(id + "/role", "{\"role\":\"ADMIN\"}"),
              b.post(id + "/deactivate", "{}"),
              b.post(id + "/reactivate", "{}"),
              b.post(id + "/invitation/resend", "{}"),
              b.post(id + "/invitation/revoke", "{}"),
              b.post(id + "/security/password-reset", "{}"));
      assertThat(responses).allSatisfy(res -> assertThat(res.getStatus()).isEqualTo(403));
    }
    assertThat(role(target)).isEqualTo("AGENT");
    assertThat(status(target)).isEqualTo("ACTIVE");
  }

  @Test
  void listFiltersByRoleAndStatusAndShowsGuardReasons() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    AppUser off = activeUser(Role.AGENT);
    jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", off.id());
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser b = signedIn(admin);

    JsonNode all = json(b.get("/api/v1/admin/supporters").getContentAsString());
    List<Long> ids = new ArrayList<>();
    all.forEach(n -> ids.add(n.get("id").asLong()));
    assertThat(ids).contains(admin.id(), agent.id(), off.id()).doesNotContain(customer.id());

    JsonNode agents =
        json(b.get("/api/v1/admin/supporters?role=AGENT&status=ACTIVE").getContentAsString());
    agents.forEach(
        n -> {
          assertThat(n.get("role").asString()).isEqualTo("AGENT");
          assertThat(n.get("status").asString()).isEqualTo("ACTIVE");
        });
    assertThat(b.get("/api/v1/admin/supporters?role=CUSTOMER").getStatus()).isEqualTo(400);

    JsonNode me = json(b.get(base(admin.id())).getContentAsString());
    assertThat(me.get("self").asBoolean()).isTrue();
    assertThat(me.get("roleChangeBlockedReason").asString())
        .isEqualTo("You cannot change your own role.");
    assertThat(me.get("deactivateBlockedReason").asString())
        .isEqualTo("You cannot deactivate yourself.");
    assertThat(me.get("mfaEnrolled").asBoolean()).isTrue();
    assertThat(me.get("lastSignInAt").isNull()).isFalse();
    JsonNode other = json(b.get(base(agent.id())).getContentAsString());
    assertThat(other.get("roleChangeBlockedReason").isNull()).isTrue();
    assertThat(other.get("openTickets").asInt()).isZero();
    assertThat(b.get(base(customer.id())).getStatus()).isEqualTo(404);
  }

  @Test
  void lastActiveAdminShowsTheReasonToOthersInTheList() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    onlyActiveAdmins(admin);
    // Seen through another admin's scope (e.g. one just demoted), the only active admin is the
    // last one; the self reason wins for the admin themselves.
    SupporterView v = service.get(AccessScope.admin(activeUser(Role.AGENT).id()), admin.id());
    assertThat(v.roleChangeBlockedReason()).isEqualTo(SupporterAdminService.LAST_ADMIN_ROLE);
    assertThat(v.deactivateBlockedReason()).isEqualTo(SupporterAdminService.LAST_ADMIN_DEACTIVATE);
  }

  @Test
  void roleChangeNeedsAFreshStepUpEndsSessionsAndNotifies() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser agentSession = signedIn(agent);
    Browser b = signedIn(admin);
    staleStepUp();

    MockHttpServletResponse refused = b.put(base(agent.id()) + "/role", "{\"role\":\"ADMIN\"}");
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("step_up_required");
    assertThat(role(agent)).isEqualTo("AGENT");
    assertThat(ended(agentSession)).isFalse();

    stepUp(b, admin);
    MockHttpServletResponse ok = b.put(base(agent.id()) + "/role", "{\"role\":\"ADMIN\"}");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(json(ok.getContentAsString()).get("role").asString()).isEqualTo("ADMIN");
    assertThat(role(agent)).isEqualTo("ADMIN");
    // Security test 4 (role part): the old session is refused at once.
    assertThat(ended(agentSession)).isTrue();
    assertThat(mailer.sentTo(agent.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .contains("notice-ROLE_CHANGED");
    List<Map<String, Object>> audit = auditEvents("SUPPORTER_ROLE_CHANGED", agent.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).get("details").toString())
        .contains("\"role\": \"ADMIN\"")
        .contains("\"old_role\": \"AGENT\"");
  }

  @Test
  void organisationAccessAndNameChangesDoNotNeedStepUp() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    long org = newOrganisation("Acme Dental");
    Browser agentSession = signedIn(agent);
    Browser b = signedIn(admin);
    staleStepUp();

    assertThat(
            b.put(
                    "/api/v1/admin/users/" + agent.id() + "/supporter-access",
                    "{\"allOrganisations\":false,\"organisationIds\":[" + org + "]}")
                .getStatus())
        .isEqualTo(200);
    // Security test 4 (organisation part).
    assertThat(ended(agentSession)).isTrue();
    JsonNode v = json(b.get(base(agent.id())).getContentAsString());
    assertThat(v.get("allOrganisations").asBoolean()).isFalse();
    assertThat(v.get("organisationIds").get(0).asLong()).isEqualTo(org);

    MockHttpServletResponse edited =
        b.put(base(agent.id()), "{\"name\":\"Robin Example\",\"jobTitle\":\"Field Engineer\"}");
    assertThat(edited.getStatus()).isEqualTo(200);
    assertThat(json(edited.getContentAsString()).get("jobTitle").asString())
        .isEqualTo("Field Engineer");
    assertThat(b.put(base(agent.id()), "{\"name\":\"  \"}").getStatus()).isEqualTo(400);
    assertThat(auditEvents("SUPPORTER_UPDATED", agent.id())).hasSize(1);
  }

  @Test
  void anAdminCannotChangeTheirOwnRoleOrDeactivateThemselvesViaTheApi() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    activeUser(Role.ADMIN);
    Browser b = signedIn(admin);
    MockHttpServletResponse demote = b.put(base(admin.id()) + "/role", "{\"role\":\"AGENT\"}");
    assertThat(demote.getStatus()).isEqualTo(409);
    assertThat(demote.getContentAsString()).contains("own_account");
    MockHttpServletResponse deactivate = b.post(base(admin.id()) + "/deactivate", "{}");
    assertThat(deactivate.getStatus()).isEqualTo(409);
    assertThat(deactivate.getContentAsString()).contains("own_account");
    assertThat(role(admin)).isEqualTo("ADMIN");
    assertThat(status(admin)).isEqualTo("ACTIVE");
  }

  @Test
  void theLastActiveAdminCannotBeDemotedOrDeactivated() {
    AppUser last = activeUser(Role.ADMIN);
    onlyActiveAdmins(last);
    // An admin whose own row has just been demoted (request in flight) acting on the last one.
    AccessScope stale = AccessScope.admin(activeUser(Role.AGENT).id());
    assertThatApi(() -> service.changeRole(stale, last.id(), Role.AGENT, null), "last_admin");
    assertThatApi(() -> service.deactivate(stale, last.id(), null, null), "last_admin");
    assertThat(role(last)).isEqualTo("ADMIN");
    assertThat(status(last)).isEqualTo("ACTIVE");
  }

  @Test
  void twoAdminsDemotingEachOtherAtOnceLeaveOneAdmin() throws Exception {
    raceTwoAdmins(
        (a, b) -> service.changeRole(AccessScope.admin(a.id()), b.id(), Role.AGENT, null));
  }

  @Test
  void twoAdminsDeactivatingEachOtherAtOnceLeaveOneAdmin() throws Exception {
    raceTwoAdmins((a, b) -> service.deactivate(AccessScope.admin(a.id()), b.id(), null, null));
  }

  interface AdminAction {
    void run(AppUser actor, AppUser target);
  }

  /**
   * Admin A acts on B in a transaction that holds its locks until released; meanwhile B acts on A.
   * B's request must wait on a lock held by A's own transaction (matched by backend pid in
   * pg_stat_activity, so another test's lock wait cannot satisfy it), and once A commits it must be
   * refused - so exactly one admin is left.
   */
  private void raceTwoAdmins(AdminAction action) throws Exception {
    AppUser a = activeUser(Role.ADMIN);
    AppUser b = activeUser(Role.ADMIN);
    onlyActiveAdmins(a, b);
    CountDownLatch acted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicLong firstPid = new AtomicLong();
    AtomicLong secondPid = new AtomicLong();
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      firstPid.set(backendPid());
                      action.run(a, b);
                      acted.countDown();
                      await(release);
                    }));
    assertThat(acted.await(20, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      secondPid.set(backendPid());
                      action.run(b, a);
                    }));
    waitUntilBlockedBy(secondPid, firstPid.get());
    assertThat(second).isNotDone();
    release.countDown();
    first.get(20, TimeUnit.SECONDS);
    try {
      second.get(20, TimeUnit.SECONDS);
      throw new AssertionError("the second admin's action should have been refused");
    } catch (ExecutionException e) {
      assertThat(e.getCause()).isInstanceOf(ApiException.class);
      assertThat(((ApiException) e.getCause()).code()).isIn("last_admin", "forbidden");
    }
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE'", Long.class))
        .containsExactly(a.id());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /** The database backend of the current transaction's connection. */
  private long backendPid() {
    Long pid = jdbc.queryForObject("SELECT pg_backend_pid()", Long.class);
    if (pid == null) {
      throw new AssertionError("no backend pid");
    }
    return pid;
  }

  /**
   * Waits until the backend {@code waiter} (once known) is waiting on a lock held by the backend
   * {@code holder} - only this race's two transactions can satisfy it.
   */
  private static void waitUntilBlockedBy(AtomicLong waiter, long holder) {
    var owner = TestcontainersConfiguration.ownerJdbc();
    Awaitility.await("the second request waits on the first one's guard lock")
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              long pid = waiter.get();
              if (pid == 0) {
                return false;
              }
              Integer blocked =
                  owner.queryForObject(
                      "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type ="
                          + " 'Lock' AND ? = ANY (pg_blocking_pids(pid))",
                      Integer.class,
                      (int) pid,
                      (int) holder);
              return blocked != null && blocked > 0;
            });
  }

  private static void assertThatApi(Runnable call, String code) {
    try {
      call.run();
      throw new AssertionError("expected " + code);
    } catch (ApiException e) {
      assertThat(e.code()).isEqualTo(code);
    }
  }

  @Test
  void deactivateEndsSessionsBlocksSignInRevokesTokensAndCanBeReactivated() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser agentSession = signedIn(agent);
    Browser b = signedIn(admin);
    // A pending password reset link must stop working too.
    assertThat(b.post(base(agent.id()) + "/security/password-reset", "{}").getStatus())
        .isEqualTo(204);
    String resetToken = mailer.last(agent.email(), "password-reset").token();

    JsonNode plan = json(b.get(base(agent.id()) + "/deactivation").getContentAsString());
    assertThat(plan.get("openTickets").asInt()).isZero();
    assertThat(plan.get("blockedReason").isNull()).isTrue();
    List<Long> targets = new ArrayList<>();
    plan.get("reassignTo").forEach(n -> targets.add(n.get("id").asLong()));
    assertThat(targets).contains(admin.id()).doesNotContain(agent.id());

    MockHttpServletResponse done =
        b.post(base(agent.id()) + "/deactivate", "{\"reassignTo\":null}");
    assertThat(done.getStatus()).isEqualTo(200);
    assertThat(json(done.getContentAsString()).get("status").asString()).isEqualTo("DEACTIVATED");
    // Security test 4 (deactivation part).
    assertThat(ended(agentSession)).isTrue();
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(agent.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(401);
    assertThat(
            browser()
                .post(
                    "/api/v1/auth/password-reset/confirm",
                    "{\"token\":"
                        + Browser.q(resetToken)
                        + ",\"password\":\"Copper-Meadow-Lantern-58\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(mailer.sentTo(agent.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .contains("notice-ACCOUNT_DEACTIVATED");
    assertThat(auditEvents("SUPPORTER_DEACTIVATED", agent.id())).hasSize(1);
    assertThat(auditEvents("SECURITY_VERSION_BUMPED", agent.id()))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains("DEACTIVATION"));
    // Deactivating again, or deactivating with a bad target, is refused.
    assertThat(b.post(base(agent.id()) + "/deactivate", "{}").getStatus()).isEqualTo(409);

    assertThat(b.post(base(agent.id()) + "/reactivate", "{}").getStatus()).isEqualTo(200);
    assertThat(status(agent)).isEqualTo("ACTIVE");
    assertThat(auditEvents("SUPPORTER_REACTIVATED", agent.id())).hasSize(1);
    signedIn(agent);
    assertThat(b.post(base(agent.id()) + "/reactivate", "{}").getStatus()).isEqualTo(409);
  }

  @Test
  void deactivationRefusesAnInvalidReassignmentTarget() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser b = signedIn(admin);
    for (long bad : new long[] {agent.id(), customer.id(), 999_999_999L}) {
      assertThat(
              b.post(base(agent.id()) + "/deactivate", "{\"reassignTo\":" + bad + "}").getStatus())
          .isEqualTo(400);
    }
    assertThat(status(agent)).isEqualTo("ACTIVE");
  }

  @Test
  void inviteSetsOrganisationsHasNoCodeAndCanBeResentAndRevoked() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    long org = newOrganisation("Beta Clinic");
    Browser b = signedIn(admin);
    String email = uniqueEmail("invitee");

    MockHttpServletResponse created =
        b.post(
            "/api/v1/admin/supporters/invitations",
            "{\"email\":"
                + Browser.q(email)
                + ",\"name\":\"Jamie Example\",\"role\":\"AGENT\",\"allOrganisations\":false,"
                + "\"organisationIds\":["
                + org
                + "]}");
    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode inv = json(created.getContentAsString());
    long id = inv.get("userId").asLong();
    // ADR-046: no code in the response; the emailed link is enough.
    assertThat(inv.has("code")).isFalse();
    RecordingSystemMailer.Sent first = mailer.last(email, "supporter-invitation");

    JsonNode listed = json(b.get(base(id)).getContentAsString());
    assertThat(listed.get("status").asString()).isEqualTo("INVITED");
    assertThat(listed.get("allOrganisations").asBoolean()).isFalse();
    assertThat(listed.get("organisationIds").get(0).asLong()).isEqualTo(org);
    assertThat(listed.get("invitationExpiresAt").isNull()).isFalse();

    // Resend: a new link; the old link stops working.
    JsonNode resent = json(b.post(base(id) + "/invitation/resend", "{}").getContentAsString());
    assertThat(resent.has("code")).isFalse();
    String token2 = mailer.last(email, "supporter-invitation").token();
    assertThat(token2).isNotEqualTo(first.token());
    assertThat(
            browser()
                .post(
                    "/api/v1/auth/invitations/inspect",
                    "{\"token\":" + Browser.q(first.token()) + "}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            browser()
                .post("/api/v1/auth/invitations/inspect", "{\"token\":" + Browser.q(token2) + "}")
                .getStatus())
        .isEqualTo(200);

    // Revoke: the link stops working and the address can be invited again.
    assertThat(b.post(base(id) + "/invitation/revoke", "{}").getStatus()).isEqualTo(204);
    assertThat(
            browser()
                .post("/api/v1/auth/invitations/inspect", "{\"token\":" + Browser.q(token2) + "}")
                .getStatus())
        .isEqualTo(400);
    assertThat(b.get(base(id)).getStatus()).isEqualTo(404);
    assertThat(auditEventsForEmail("INVITATION_REVOKED", email)).hasSize(1);
    assertThat(
            b.post(
                    "/api/v1/admin/supporters/invitations",
                    "{\"email\":"
                        + Browser.q(email)
                        + ",\"name\":\"Jamie Example\",\"role\":\"ADMIN\",\"allOrganisations\":true}")
                .getStatus())
        .isEqualTo(201);
  }

  /**
   * Audit PR #11 M1: an unattended or hijacked admin session whose last code is older than 10
   * minutes must not be able to create a second admin by invitation, nor re-send an admin invite.
   */
  @Test
  void invitingOrReinvitingAnAdminNeedsAFreshStepUp() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    Browser b = signedIn(admin);
    String email = uniqueEmail("admin-invitee");
    String body =
        "{\"email\":"
            + Browser.q(email)
            + ",\"name\":\"Casey Example\",\"role\":\"ADMIN\",\"allOrganisations\":true}";
    staleStepUp();

    MockHttpServletResponse refused = b.post("/api/v1/admin/supporters/invitations", body);
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("step_up_required");
    assertThat(usersWithEmail(email)).isZero();
    assertThat(mailer.sentTo(email)).isEmpty();

    stepUp(b, admin);
    MockHttpServletResponse created = b.post("/api/v1/admin/supporters/invitations", body);
    assertThat(created.getStatus()).isEqualTo(201);
    long id = json(created.getContentAsString()).get("userId").asLong();
    String firstToken = mailer.last(email, "supporter-invitation").token();

    staleStepUp();
    MockHttpServletResponse resendRefused = b.post(base(id) + "/invitation/resend", "{}");
    assertThat(resendRefused.getStatus()).isEqualTo(403);
    assertThat(resendRefused.getContentAsString()).contains("step_up_required");
    // The refused resend issued nothing: the first link is still the latest one.
    assertThat(mailer.last(email, "supporter-invitation").token()).isEqualTo(firstToken);

    stepUp(b, admin);
    MockHttpServletResponse resent = b.post(base(id) + "/invitation/resend", "{}");
    assertThat(resent.getStatus()).isEqualTo(200);
    assertThat(json(resent.getContentAsString()).has("code")).isFalse();
    assertThat(mailer.last(email, "supporter-invitation").token()).isNotEqualTo(firstToken);
  }

  /** Invitations of any role need a fresh step-up (the simpler of the two options in M1). */
  @Test
  void invitingAnAgentAlsoNeedsAFreshStepUp() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    Browser b = signedIn(admin);
    String email = uniqueEmail("agent-invitee");
    String body =
        "{\"email\":"
            + Browser.q(email)
            + ",\"name\":\"Drew Example\",\"role\":\"AGENT\",\"allOrganisations\":true}";
    staleStepUp();
    MockHttpServletResponse refused = b.post("/api/v1/admin/supporters/invitations", body);
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("step_up_required");
    assertThat(usersWithEmail(email)).isZero();
    stepUp(b, admin);
    assertThat(b.post("/api/v1/admin/supporters/invitations", body).getStatus()).isEqualTo(201);
  }

  private int usersWithEmail(String email) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE lower(email) = lower(?)", Integer.class, email);
    return n == null ? 0 : n;
  }

  @Test
  void anAcceptedInvitationCannotBeResentOrRevokedAndCustomersCannotBeInvitedHere()
      throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(admin);
    assertThat(b.post(base(agent.id()) + "/invitation/resend", "{}").getStatus()).isEqualTo(409);
    assertThat(b.post(base(agent.id()) + "/invitation/revoke", "{}").getStatus()).isEqualTo(409);
    assertThat(
            b.post(
                    "/api/v1/admin/supporters/invitations",
                    "{\"email\":\"c@example.test\",\"name\":\"C\",\"role\":\"CUSTOMER\"}")
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void activityListsAuditEventsAndReturnsAttackerChosenTextAsData() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(admin);
    // A device label is derived from the User-Agent, which an attacker chooses.
    jdbc.update(
        "INSERT INTO audit_event (occurred_at, type, actor_user_id, target_user_id, client_ip,"
            + " details) VALUES (?, 'NEW_DEVICE_SIGN_IN', ?, ?, '203.0.113.9',"
            + " '{\"device\":\"<img src=x onerror=alert(1)>\"}'::jsonb)",
        java.sql.Timestamp.from(clock.instant().minusSeconds(60)),
        agent.id(),
        agent.id());
    b.put(base(agent.id()), "{\"name\":\"Robin Example\",\"jobTitle\":\"\"}");

    MockHttpServletResponse res = b.get(base(agent.id()) + "/activity");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).startsWith("application/json");
    JsonNode events = json(res.getContentAsString());
    List<String> types = new ArrayList<>();
    events.forEach(e -> types.add(e.get("type").asString()));
    assertThat(types).contains("SUPPORTER_UPDATED", "NEW_DEVICE_SIGN_IN");
    // Newest first.
    assertThat(types.get(0)).isEqualTo("SUPPORTER_UPDATED");
    JsonNode device = events.get(1).get("details").get("device");
    assertThat(device.asString()).isEqualTo("<img src=x onerror=alert(1)>");
    assertThat(events.get(0).get("actorName").asString()).isEqualTo("Sam Example");
  }

  @Test
  void passwordResetLinkIsEmailedNeverShownAndNotForYourself() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(admin);
    MockHttpServletResponse res = b.post(base(agent.id()) + "/security/password-reset", "{}");
    assertThat(res.getStatus()).isEqualTo(204);
    assertThat(res.getContentAsString()).isEmpty();
    assertThat(mailer.last(agent.email(), "password-reset").link()).contains("/reset-password");
    assertThat(b.post(base(admin.id()) + "/security/password-reset", "{}").getStatus())
        .isEqualTo(409);
    Consumer<Map<String, Object>> admin1 =
        e -> assertThat(e.get("actor_user_id")).isEqualTo(admin.id());
    assertThat(auditEvents("PASSWORD_RESET_REQUESTED", agent.id())).allSatisfy(admin1);
  }
}
