package com.ticketsystem.api.erasure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.TestcontainersConfiguration;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.ticket.TicketService.Created;
import com.ticketsystem.api.ticket.TicketTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * WP-12 personal-data erasure (spec sections 10 and 12 "Data handling"): an admin with a fresh
 * step-up pseudonymises a user everywhere, ticket history still reads correctly, the account can
 * never come back, and the guards (self, active supporter, already erased, role gate) hold.
 */
class UserErasureIntegrationTest extends TicketTestBase {

  private final JdbcTemplate owner = TestcontainersConfiguration.ownerJdbc();

  private static String erasePath(long id) {
    return "/api/v1/admin/users/" + id + "/erase";
  }

  private Map<String, Object> userRow(long id) {
    return owner.queryForMap(
        "SELECT name, email, status, password_hash, erased_at FROM app_user WHERE id = ?", id);
  }

  private int count(String sql, Object... args) {
    return owner.queryForObject(sql, Integer.class, args);
  }

  @Test
  void anAdminErasesACustomerAndTheHistoryStillReads() throws Exception {
    Created t = acmeTicket();
    String email = acmeCustomerUser.email();
    jdbc.update(
        "INSERT INTO public_comment (ticket_id, author_kind, author_user_id, author_email,"
            + " body_html, created_at) VALUES (?, 'CLIENT', ?, ?, '<p>Kept body</p>', now())",
        t.ticketId(),
        acmeCustomerUser.id(),
        email);
    jdbc.update(
        "INSERT INTO ticket_participant (ticket_id, user_id, email, created_at) VALUES (?, ?, ?,"
            + " now())",
        acmeTicket().ticketId(),
        acmeCustomerUser.id(),
        email);
    owner.update(
        "INSERT INTO audit_event (occurred_at, type, target_user_id, details) VALUES (now(),"
            + " 'MAGIC_LINK_REQUESTED', ?, jsonb_build_object('email', ?::text, 'status',"
            + " 'sent'))",
        acmeCustomerUser.id(),
        email);
    Browser customer = signedIn(acmeCustomerUser);
    Browser b = signedIn(adminUser);

    MockHttpServletResponse r = b.post(erasePath(acmeCustomerUser.id()), "{}");

    assertThat(r.getStatus()).isEqualTo(204);
    Map<String, Object> row = userRow(acmeCustomerUser.id());
    assertThat(row.get("name")).isEqualTo("Former user");
    assertThat(row.get("email")).isNull();
    assertThat(row.get("password_hash")).isNull();
    assertThat(row.get("status")).isEqualTo("DEACTIVATED");
    assertThat(row.get("erased_at")).isNotNull();
    // The ticket and the comment body stay; only the address goes.
    assertThat(
            owner.queryForObject(
                "SELECT reporter_user_id FROM ticket WHERE id = ?", Long.class, t.ticketId()))
        .isEqualTo(acmeCustomerUser.id());
    assertThat(
            count(
                "SELECT count(*) FROM public_comment WHERE ticket_id = ? AND body_html = '<p>Kept"
                    + " body</p>' AND author_email IS NULL",
                t.ticketId()))
        .isOne();
    assertThat(count("SELECT count(*) FROM public_comment WHERE lower(author_email) = ?", email))
        .isZero();
    assertThat(count("SELECT count(*) FROM ticket WHERE lower(reporter_email) = ?", email))
        .isZero();
    assertThat(count("SELECT count(*) FROM ticket_participant WHERE email = ?", email)).isZero();
    assertThat(count("SELECT count(*) FROM audit_event WHERE details->>'email' = ?", email))
        .isZero();
    // Sessions end at once; the old address no longer signs in.
    assertThat(customer.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(browser().post("/api/v1/auth/sign-in", signInJson(email, GOOD_PASSWORD)).getStatus())
        .isEqualTo(401);
    // Logged: erasure log (ids only) and an audit event without the name or address.
    assertThat(
            count(
                "SELECT count(*) FROM user_erasure WHERE user_id = ? AND actor_user_id = ?",
                acmeCustomerUser.id(),
                adminUser.id()))
        .isOne();
    assertThat(
            owner.queryForObject(
                "SELECT details::text FROM audit_event WHERE type = 'USER_ERASED' AND"
                    + " target_user_id = ?",
                String.class,
                acmeCustomerUser.id()))
        .contains("CUSTOMER")
        .doesNotContain(email)
        .doesNotContain("Sam Example");
    // The address is free again for a new contact.
    AppUser again =
        users
            .findById(
                users.insertActive(
                    email, "Sam Example", Role.CUSTOMER, acme, "{argon2}x", Instant.now()))
            .orElseThrow();
    assertThat(again.id()).isNotEqualTo(acmeCustomerUser.id());
    // A second erase is refused.
    assertThat(b.post(erasePath(acmeCustomerUser.id()), "{}").getStatus()).isEqualTo(409);
  }

  @Test
  void eraseNeedsAFreshStepUp() throws Exception {
    Browser b = signedIn(adminUser);
    clock.advance(Duration.ofMinutes(11));
    MockHttpServletResponse r = b.post(erasePath(acmeCustomerUser.id()), "{}");
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(r.getContentAsString()).contains("step_up_required");
    assertThat(userRow(acmeCustomerUser.id()).get("erased_at")).isNull();
  }

  @Test
  void agentsAndCustomersGet403AndNothingChanges() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    assertThat(signedIn(agent).post(erasePath(betaCustomerUser.id()), "{}").getStatus())
        .isEqualTo(403);
    assertThat(signedIn(acmeCustomerUser).post(erasePath(betaCustomerUser.id()), "{}").getStatus())
        .isEqualTo(403);
    assertThat(userRow(betaCustomerUser.id()).get("erased_at")).isNull();
  }

  @Test
  void supportersMustBeDeactivatedFirstAndNeverComeBack() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = signedIn(adminUser);
    MockHttpServletResponse active = b.post(erasePath(agent.id()), "{}");
    assertThat(active.getStatus()).isEqualTo(409);
    assertThat(active.getContentAsString()).contains("deactivate_first");

    assertThat(
            b.post(
                    "/api/v1/admin/supporters/" + agent.id() + "/deactivate",
                    "{\"reassignTo\":null}")
                .getStatus())
        .isEqualTo(200);
    assertThat(b.post(erasePath(agent.id()), "{}").getStatus()).isEqualTo(204);
    MockHttpServletResponse back =
        b.post("/api/v1/admin/supporters/" + agent.id() + "/reactivate", "{}");
    assertThat(back.getStatus()).isEqualTo(409);
    assertThat(back.getContentAsString()).contains("erased");
    assertThat(userRow(agent.id()).get("status")).isEqualTo("DEACTIVATED");
    assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ?", agent.id())).isZero();
  }

  @Test
  void anAdminCannotEraseThemselvesAndUnknownIdsAre404() throws Exception {
    Browser b = signedIn(adminUser);
    assertThat(b.post(erasePath(adminUser.id()), "{}").getStatus()).isEqualTo(400);
    assertThat(b.post(erasePath(999_999_999L), "{}").getStatus()).isEqualTo(404);
    assertThat(userRow(adminUser.id()).get("erased_at")).isNull();
  }

  /** AUD-38: the erasure's participant-address UPDATE is served by V94's partial index. */
  @Test
  void theParticipantAddressUpdateCanUseItsIndex() {
    String def =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'ticket_event_participant_value_ix'",
            String.class);
    assertThat(def).contains("lower((new_value)").contains("PARTICIPANT_ADDED");
    String plan =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<String>)
                c -> {
                  boolean auto = c.getAutoCommit();
                  c.setAutoCommit(false);
                  try (var st = c.createStatement()) {
                    st.execute("SET LOCAL enable_seqscan = off");
                    StringBuilder out = new StringBuilder();
                    try (var rs =
                        st.executeQuery(
                            "EXPLAIN UPDATE ticket_event SET new_value = NULL WHERE type IN"
                                + " ('PARTICIPANT_ADDED', 'PARTICIPANT_REMOVED') AND"
                                + " lower(new_value) = 'someone@example.com'")) {
                      while (rs.next()) {
                        out.append(rs.getString(1)).append('\n');
                      }
                    }
                    return out.toString();
                  } finally {
                    c.rollback();
                    c.setAutoCommit(auto);
                  }
                });
    assertThat(plan).contains("ticket_event_participant_value_ix");
  }

  @Test
  void theDatabaseRefusesToReactivateAnErasedRow() {
    Created t = acmeTicket();
    assertThat(t).isNotNull();
    owner.queryForObject("SELECT ts_erase_user(?, now())", Boolean.class, betaCustomerUser.id());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", betaCustomerUser.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private long auditWith(String key, String value) {
    return owner.queryForObject(
        "INSERT INTO audit_event (occurred_at, type, details) VALUES (now(), 'SIGN_IN_FAILED',"
            + " jsonb_build_object(?::text, ?::text, 'reason', 'bad_password')) RETURNING id",
        Long.class,
        key,
        value);
  }

  private String auditDetails(long id) {
    return owner.queryForObject(
        "SELECT details::text FROM audit_event WHERE id = ?", String.class, id);
  }

  private int scrubEvents(long userId) {
    return count(
        "SELECT count(*) FROM audit_event WHERE type = 'AUDIT_EMAIL_SCRUBBED' AND target_user_id"
            + " = ?",
        userId);
  }

  @Test
  void theScrubRefusesAUserWhoIsNotErasedAndChangesNothing() {
    String email = betaCustomerUser.email();
    long event = auditWith("email", email);

    // PR #22 audit M1: called by the app role for a live user, it raises and nothing changes.
    assertThatThrownBy(
            () ->
                jdbc.queryForObject(
                    "SELECT scrub_audit_email(?)", Integer.class, betaCustomerUser.id()))
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            e ->
                assertThat(NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                    .contains("is not erased"));
    assertThatThrownBy(
            () -> jdbc.queryForObject("SELECT scrub_audit_email(?)", Integer.class, 999_999_999L))
        .isInstanceOf(DataAccessException.class);
    assertThat(auditDetails(event)).contains(email);
    assertThat(scrubEvents(betaCustomerUser.id())).isZero();
    // The old address-taking signature is gone, and the app role can neither read nor plant a
    // captured address.
    assertThatThrownBy(
            () -> jdbc.queryForObject("SELECT scrub_audit_email(?::text)", Integer.class, email))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.queryForObject("SELECT count(*) FROM user_erasure_address", Integer.class))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO user_erasure_address (user_id, email) VALUES (?, ?)",
                    adminUser.id(),
                    email))
        .isInstanceOf(DataAccessException.class);
    // The app role still has no UPDATE on the audit log itself.
    assertThatThrownBy(
            () -> jdbc.update("UPDATE audit_event SET details = '{}'::jsonb WHERE id = ?", event))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void erasingScrubsTheAddressFromAuditAndMailDataAndAuditsTheScrub() {
    Created t = acmeTicket();
    String email = betaCustomerUser.email();
    long byEmail = auditWith("email", email);
    long byRule = auditWith("rule", email);
    long other = auditWith("email", "someone-else@example.test");
    long queued =
        owner.queryForObject(
            "INSERT INTO outbox_message (message_id, kind, to_addresses, from_name, subject,"
                + " html_body, text_body, status, next_attempt_at) VALUES (?, 'system', ?,"
                + " 'Example', 'Synthetic', '<p>x</p>', 'x', 'queued', now() + interval '1 day')"
                + " RETURNING id",
            Long.class,
            "<" + UUID.randomUUID() + "@example.test>",
            email);
    long note =
        owner.queryForObject(
            "INSERT INTO internal_note (ticket_id, author_kind, author_user_id, body_html,"
                + " received_by_email, auth_results, created_at) VALUES (?, 'SUPPORTER', ?,"
                + " '<p>Kept note</p>', TRUE, ?, now()) RETURNING id",
            Long.class,
            t.ticketId(),
            adminUser.id(),
            "mx.example.test; dmarc=pass header.from=example.test; smtp.mailfrom=" + email);
    long mail =
        owner.queryForObject(
            "INSERT INTO inbound_mail (message_id, raw_sha256, raw_size, raw_purge_after, subject,"
                + " received_at, outcome) VALUES (?, repeat('a', 64), 1, now(), ?, now(),"
                + " 'CREATED') RETURNING id",
            Long.class,
            "<" + UUID.randomUUID() + "@example.test>",
            "Fwd: printer (from " + email.toUpperCase(Locale.ROOT) + ")");

    // Run as the application role, exactly as the admin action does.
    assertThat(
            jdbc.queryForObject(
                "SELECT ts_erase_user(?, now())", Boolean.class, betaCustomerUser.id()))
        .isTrue();

    assertThat(auditDetails(byEmail)).doesNotContain(email).contains("bad_password");
    assertThat(auditDetails(byRule)).doesNotContain(email).contains("bad_password");
    assertThat(auditDetails(other)).contains("someone-else@example.test");
    assertThat(
            owner.queryForObject(
                "SELECT details::text FROM audit_event WHERE type = 'AUDIT_EMAIL_SCRUBBED' AND"
                    + " target_user_id = ?",
                String.class,
                betaCustomerUser.id()))
        .contains("\"rows\": ")
        .doesNotContain(email);
    // The captured address does not outlive the erase.
    assertThat(
            count(
                "SELECT count(*) FROM user_erasure_address WHERE user_id = ?",
                betaCustomerUser.id()))
        .isZero();
    // Lows: queued mail to them is cancelled; auth results and logged subjects lose the address.
    assertThat(count("SELECT count(*) FROM outbox_message WHERE id = ?", queued)).isZero();
    assertThat(
            owner.queryForObject(
                "SELECT auth_results FROM internal_note WHERE id = ?", String.class, note))
        .isNull();
    assertThat(
            owner.queryForObject(
                "SELECT subject FROM inbound_mail WHERE id = ?", String.class, mail))
        .isEqualTo("Fwd: printer (from [removed])");

    // Called again for the now-erased user it is allowed, finds nothing and still audits.
    assertThat(
            jdbc.queryForObject(
                "SELECT scrub_audit_email(?)", Integer.class, betaCustomerUser.id()))
        .isZero();
    assertThat(scrubEvents(betaCustomerUser.id())).isEqualTo(2);
  }
}
