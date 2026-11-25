package com.ticketsystem.api.ticket.portal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.ticket.TicketTestBase;
import com.ticketsystem.api.ticket.attachment.TestImages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * WP-06 customer portal, raise-only (ADR-018): the route inventory, own-organisation tickets with
 * the confirmation email, mass-assignment, the duplicate-submit guard, field errors, Alert hidden,
 * attachment rules and My account. Synthetic organisations and people only.
 */
class PortalIntegrationTest extends TicketTestBase {

  /** The complete customer API (spec 12, ADR-018). Adding a portal route must change this list. */
  static final Set<String> PORTAL_ROUTES =
      Set.of(
          "POST /api/v1/portal/tickets",
          "GET /api/v1/portal/request-types",
          "POST /api/v1/portal/attachments",
          "GET /api/v1/portal/account",
          "PATCH /api/v1/portal/account");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private Set<String> portalMappings() {
    Set<String> out = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        if (p.startsWith("/api/v1/portal") || p.startsWith("/api/v1/portal/")) {
          Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
          if (methods.isEmpty()) {
            out.add("ANY " + p); // a mapping without a method would answer every verb
          }
          for (RequestMethod m : methods) {
            out.add(m.name() + " " + p);
          }
        }
      }
    }
    return out;
  }

  private String body(long requestType, String subject, String description, String extra) {
    return "{\"requestTypeId\":"
        + requestType
        + ",\"subject\":"
        + Browser.q(subject)
        + (description == null ? "" : ",\"descriptionHtml\":" + Browser.q(description))
        + ",\"impact\":\"A_FEW_PEOPLE\",\"urgency\":\"WORK_STOPPED\""
        + extra
        + "}";
  }

  private long ticketByReference(String reference) {
    return references.resolve(admin, reference).orElseThrow().ticketId();
  }

  private List<Map<String, Object>> outboxFor(long ticketId) {
    return jdbc.queryForList(
        "SELECT kind, to_addresses, subject, html_body, text_body FROM outbox_message"
            + " WHERE ticket_id = ? ORDER BY id",
        ticketId);
  }

  // ----------------------------------------------------------------------------------------------
  // Route inventory (spec 12, security test 1 customer part)

  @Test
  void thePortalTreeHasExactlyTheRaiseAndOwnAccountRoutes() {
    assertThat(portalMappings()).isEqualTo(new TreeSet<>(PORTAL_ROUTES));
  }

  @Test
  void noPortalResponseCarriesTicketCommentAttachmentOrSlaFields() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    List<String> answers = new ArrayList<>();
    answers.add(c.get("/api/v1/portal/request-types").getContentAsString());
    answers.add(
        uploadVia(c, "/api/v1/portal/attachments", "a.png", TestImages.png(10, 10))
            .getContentAsString());
    answers.add(
        c.post("/api/v1/portal/tickets", body(generalType, "Printer", "<p>Jammed</p>", ""))
            .getContentAsString());
    answers.add(c.get("/api/v1/portal/account").getContentAsString());
    answers.add(
        c.send(HttpMethod.PATCH, "/api/v1/portal/account", "{\"name\":\"Kim Example\"}")
            .getContentAsString());
    assertThat(answers).hasSize(PORTAL_ROUTES.size());
    for (String a : answers) {
      String lower = a.toLowerCase(Locale.ROOT);
      for (String banned :
          List.of(
              "status",
              "priority",
              "assignee",
              "sla",
              "due",
              "comment",
              "note",
              "body",
              "url",
              "organisationid",
              "ticketid")) {
        assertThat(lower).as(a).doesNotContain("\"" + banned);
      }
    }
  }

  @Test
  void supportersGet403AndAnonymousGets401OnEveryPortalRoute() throws Exception {
    Browser agent = signedIn(acmeAgentUser);
    Browser anonymous = browser();
    for (String route : PORTAL_ROUTES) {
      String[] parts = route.split(" ");
      HttpMethod m = HttpMethod.valueOf(parts[0]);
      String json = m == HttpMethod.GET ? null : "{}";
      assertThat(agent.send(m, parts[1], json).getStatus()).as(route).isEqualTo(403);
      assertThat(anonymous.send(m, parts[1], json).getStatus()).as(route).isEqualTo(401);
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Raising a ticket

  @Test
  void aCustomerRaisesATicketForTheirOwnOrganisationAndTheConfirmationIsQueued() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    MockHttpServletResponse r =
        c.post(
            "/api/v1/portal/tickets",
            body(generalType, "Cannot print", "<p>The printer shows error 42.</p>", ""));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    JsonNode receipt = json(r.getContentAsString());
    assertThat(receipt.size()).isEqualTo(1);
    String reference = receipt.get("reference").asString();
    assertThat(reference).startsWith(activePrefixOf(acme) + "-");

    long id = ticketByReference(reference);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT organisation_id, reporter_user_id, source, status, priority, impact, urgency"
                + " FROM ticket WHERE id = ?",
            id);
    assertThat(row)
        .containsEntry("organisation_id", acme)
        .containsEntry("reporter_user_id", acmeCustomerUser.id())
        .containsEntry("source", "PORTAL")
        .containsEntry("status", "NEW")
        .containsEntry("priority", "P3")
        .containsEntry("impact", "A_FEW_PEOPLE")
        .containsEntry("urgency", "WORK_STOPPED");

    List<Map<String, Object>> mails = outboxFor(id);
    assertThat(mails).hasSize(1);
    Map<String, Object> mail = mails.get(0);
    assertThat(mail.get("kind")).isEqualTo("client_facing");
    assertThat((String) mail.get("to_addresses")).containsIgnoringCase(acmeCustomerUser.email());
    assertThat((String) mail.get("subject")).contains(reference);
  }

  @Test
  void organisationReporterPriorityAndStatusInTheBodyAreIgnored() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    int betaBefore = count("SELECT count(*) FROM ticket WHERE organisation_id = ?", beta);
    String extra =
        ",\"organisationId\":"
            + beta
            + ",\"reporterUserId\":"
            + betaCustomerUser.id()
            + ",\"priority\":\"P1\",\"status\":\"RESOLVED\",\"source\":\"AGENT\"";
    MockHttpServletResponse r =
        c.post("/api/v1/portal/tickets", body(generalType, "Wi-Fi down", "<p>All day</p>", extra));
    assertThat(r.getStatus()).isIn(201, 400);
    if (r.getStatus() == 201) {
      long id = ticketByReference(json(r.getContentAsString()).get("reference").asString());
      assertThat(
              jdbc.queryForMap(
                  "SELECT organisation_id, reporter_user_id, priority, status, source"
                      + " FROM ticket WHERE id = ?",
                  id))
          .containsEntry("organisation_id", acme)
          .containsEntry("reporter_user_id", acmeCustomerUser.id())
          .containsEntry("priority", "P3")
          .containsEntry("status", "NEW")
          .containsEntry("source", "PORTAL");
    }
    assertThat(count("SELECT count(*) FROM ticket WHERE organisation_id = ?", beta))
        .isEqualTo(betaBefore);
  }

  @Test
  void theSameSubmissionTwiceGivesOneTicketAndOneEmail() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    UUID key = UUID.randomUUID();
    String b =
        body(
            generalType,
            "Screen flickers",
            "<p>Since Monday</p>",
            ",\"idempotencyKey\":\"" + key + "\"");
    MockHttpServletResponse first = c.post("/api/v1/portal/tickets", b);
    MockHttpServletResponse second = c.post("/api/v1/portal/tickets", b);
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(second.getStatus()).isEqualTo(201);
    String ref = json(first.getContentAsString()).get("reference").asString();
    assertThat(json(second.getContentAsString()).get("reference").asString()).isEqualTo(ref);
    long id = ticketByReference(ref);
    assertThat(
            count(
                "SELECT count(*) FROM ticket WHERE reporter_user_id = ? AND subject = ?",
                acmeCustomerUser.id(),
                "Screen flickers"))
        .isOne();
    assertThat(outboxFor(id)).hasSize(1);
    // A different key is a new ticket.
    MockHttpServletResponse third =
        c.post("/api/v1/portal/tickets", b.replace(key.toString(), UUID.randomUUID().toString()));
    assertThat(json(third.getContentAsString()).get("reference").asString()).isNotEqualTo(ref);
  }

  @Test
  void twoConcurrentSubmissionsWithOneKeyMakeOneTicket() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    c.get("/api/v1/auth/csrf");
    UUID key = UUID.randomUUID();
    String b =
        body(generalType, "Race subject", "<p>Twice</p>", ",\"idempotencyKey\":\"" + key + "\"");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<String> call = () -> c.post("/api/v1/portal/tickets", b).getContentAsString();
      Future<String> a = pool.submit(call);
      Future<String> d = pool.submit(call);
      assertThat(json(a.get()).get("reference").asString())
          .isEqualTo(json(d.get()).get("reference").asString());
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            count(
                "SELECT count(*) FROM ticket WHERE reporter_user_id = ? AND subject = ?",
                acmeCustomerUser.id(),
                "Race subject"))
        .isOne();
  }

  @Test
  void anotherCustomersKeyIsTheirOwn() throws Exception {
    UUID key = UUID.randomUUID();
    String extra = ",\"idempotencyKey\":\"" + key + "\"";
    String a =
        json(signedIn(acmeCustomerUser)
                .post("/api/v1/portal/tickets", body(generalType, "A", "<p>a</p>", extra))
                .getContentAsString())
            .get("reference")
            .asString();
    String b =
        json(signedIn(betaCustomerUser)
                .post("/api/v1/portal/tickets", body(generalType, "B", "<p>b</p>", extra))
                .getContentAsString())
            .get("reference")
            .asString();
    assertThat(b).isNotEqualTo(a).startsWith(activePrefixOf(beta) + "-");
  }

  @Test
  void missingSubjectOrDescriptionAreFieldErrors() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    MockHttpServletResponse noSubject =
        c.post("/api/v1/portal/tickets", body(generalType, "  ", "<p>x</p>", ""));
    assertThat(noSubject.getStatus()).isEqualTo(400);
    assertThat(json(noSubject.getContentAsString()).get("error").asString())
        .isEqualTo("subject_required");
    MockHttpServletResponse noDescription =
        c.post("/api/v1/portal/tickets", body(generalType, "Subject", null, ""));
    assertThat(json(noDescription.getContentAsString()).get("error").asString())
        .isEqualTo("description_required");
    // Only a script is no description at all.
    MockHttpServletResponse scriptOnly =
        c.post(
            "/api/v1/portal/tickets",
            body(generalType, "Subject", "<script>alert(1)</script>", ""));
    assertThat(scriptOnly.getStatus()).isEqualTo(400);
    String noImpact =
        "{\"requestTypeId\":"
            + generalType
            + ",\"subject\":\"s\",\"descriptionHtml\":\"<p>d</p>\"}";
    assertThat(c.post("/api/v1/portal/tickets", noImpact).getStatus()).isEqualTo(400);
    assertThat(c.post("/api/v1/portal/tickets", "{not json").getStatus()).isEqualTo(400);
    assertThat(
            count("SELECT count(*) FROM ticket WHERE reporter_user_id = ?", acmeCustomerUser.id()))
        .isZero();
  }

  @Test
  void aScriptInTheDescriptionIsStrippedOnSaveAndInTheEmail() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    String ref =
        json(c.post(
                    "/api/v1/portal/tickets",
                    body(
                        generalType,
                        "Odd page",
                        "<p onclick=\"steal()\">Look</p><script>alert(1)</script>"
                            + "<a href=\"javascript:alert(2)\">here</a>",
                        ""))
                .getContentAsString())
            .get("reference")
            .asString();
    long id = ticketByReference(ref);
    String stored =
        jdbc.queryForObject(
            "SELECT body_html FROM public_comment WHERE ticket_id = ? ORDER BY id LIMIT 1",
            String.class,
            id);
    assertThat(stored)
        .contains("Look")
        .doesNotContain("script")
        .doesNotContain("onclick")
        .doesNotContain("javascript");
    String html = (String) outboxFor(id).get(0).get("html_body");
    assertThat(html).doesNotContain("<script").doesNotContain("onclick").doesNotContain("alert(1)");
  }

  @Test
  void thePortalOffersExactlyTheEightDefaultTypesInOrderWithDescriptions() throws Exception {
    // ADR-027 (change 2026-11-18), seeded by V81. Alert is never offered.
    Browser c = signedIn(acmeCustomerUser);
    JsonNode types = json(c.get("/api/v1/portal/request-types").getContentAsString());
    List<String> offered = new ArrayList<>();
    types.forEach(
        t -> offered.add(t.get("name").asString() + " | " + t.get("description").asString()));
    assertThat(offered)
        .containsExactly(
            "Account and access | Passwords, locked accounts, permissions",
            "Email and Microsoft 365 | Outlook, Teams, OneDrive, SharePoint",
            "Computers and devices | Laptops, phones, printers, scanners",
            "Network and internet | Wi-Fi, VPN, internet down",
            "Software and applications | Installs, errors, licences",
            "New starter or leaver | Setting people up or removing them",
            "Security concern | Phishing, viruses, suspicious email",
            "Other or general question | Anything else");
    // id, name and description only - no priority (F6).
    types.forEach(
        t -> {
          assertThat(t.size()).isEqualTo(3);
          assertThat(t.has("id") && t.has("name") && t.has("description")).isTrue();
        });
  }

  @Test
  void theDefaultTypesKeepGeneralsIdAndAvoidP1() {
    // "General" (V21) was renamed in place: the type with the lowest id is now the catch-all.
    assertThat(
            jdbc.queryForObject("SELECT name FROM request_type ORDER BY id LIMIT 1", String.class))
        .isEqualTo("Other or general question");
    assertThat(count("SELECT count(*) FROM request_type WHERE lower(name) = 'general'")).isZero();
    assertThat(count("SELECT count(*) FROM request_type WHERE default_priority = 'P1'")).isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT default_priority, portal_visible FROM request_type WHERE name = 'Alert'"))
        .containsEntry("default_priority", "P2")
        .containsEntry("portal_visible", false);
    assertThat(
            jdbc.queryForObject(
                "SELECT default_priority FROM request_type WHERE name = 'Other or general question'",
                String.class))
        .isEqualTo("P4");
  }

  @Test
  void theAlertTypeIsHiddenAndRefused() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    JsonNode types = json(c.get("/api/v1/portal/request-types").getContentAsString());
    List<String> names = new ArrayList<>();
    types.forEach(t -> names.add(t.get("name").asString()));
    assertThat(names).isNotEmpty().doesNotContain("Alert");
    assertThat(
            c.post("/api/v1/portal/tickets", body(alertType, "Alert?", "<p>x</p>", "")).getStatus())
        .isEqualTo(400);
  }

  // ----------------------------------------------------------------------------------------------
  // Attachments

  @Test
  void attachmentRulesApplyToTheForm() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    assertThat(
            uploadVia(c, "/api/v1/portal/attachments", "big.bin", new byte[21 * 1024 * 1024])
                .getStatus())
        .isEqualTo(413);
    assertThat(uploadVia(c, "/api/v1/portal/attachments", "logo.svg", TestImages.svg()).getStatus())
        .isEqualTo(400);
    assertThat(uploadVia(c, "/api/v1/portal/attachments", "logo.png", TestImages.svg()).getStatus())
        .isEqualTo(400);
    // A renamed HTML page is never an image: it can only ever be a download (WP-04), and
    // customers have no download route at all.
    MockHttpServletResponse html =
        uploadVia(c, "/api/v1/portal/attachments", "photo.jpg", TestImages.html());
    if (html.getStatus() == 201) {
      UUID id = UUID.fromString(json(html.getContentAsString()).get("id").asString());
      assertThat(
              jdbc.queryForObject(
                  "SELECT is_image FROM attachment WHERE public_id = ?", Boolean.class, id))
          .isFalse();
    } else {
      assertThat(html.getStatus()).isEqualTo(400);
    }
    // An upload binds to the new ticket's description.
    String png =
        json(uploadVia(c, "/api/v1/portal/attachments", "error.png", TestImages.png(40, 40))
                .getContentAsString())
            .get("id")
            .asString();
    MockHttpServletResponse r =
        c.post(
            "/api/v1/portal/tickets",
            body(
                generalType,
                "With file",
                "<p>see file</p>",
                ",\"attachmentIds\":[\"" + png + "\"]"));
    assertThat(r.getStatus()).isEqualTo(201);
    long id = ticketByReference(json(r.getContentAsString()).get("reference").asString());
    assertThat(count("SELECT count(*) FROM attachment WHERE ticket_id = ?", id)).isOne();
    // ...and cannot be used twice.
    assertThat(
            c.post(
                    "/api/v1/portal/tickets",
                    body(generalType, "Again", "<p>x</p>", ",\"attachmentIds\":[\"" + png + "\"]"))
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void aCustomerCannotAttachAnotherCustomersUpload() throws Exception {
    Browser beta = signedIn(betaCustomerUser);
    String theirs =
        json(uploadVia(beta, "/api/v1/portal/attachments", "b.png", TestImages.png(10, 10))
                .getContentAsString())
            .get("id")
            .asString();
    Browser acmeBrowser = signedIn(acmeCustomerUser);
    assertThat(
            acmeBrowser
                .post(
                    "/api/v1/portal/tickets",
                    body(
                        generalType,
                        "Steal",
                        "<p>x</p>",
                        ",\"attachmentIds\":[\"" + theirs + "\"]"))
                .getStatus())
        .isEqualTo(400);
  }

  // ----------------------------------------------------------------------------------------------
  // My account

  @Test
  void myAccountShowsOnlyMyOwnDetailsAndRenames() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    JsonNode a = json(c.get("/api/v1/portal/account").getContentAsString());
    assertThat(a.get("email").asString()).isEqualTo(acmeCustomerUser.email());
    assertThat(a.get("organisationName").asString()).startsWith("Acme Dental");
    assertThat(a.get("microsoftSignInAvailable").asBoolean()).isFalse();

    MockHttpServletResponse renamed =
        c.send(HttpMethod.PATCH, "/api/v1/portal/account", "{\"name\":\"  Kim Example  \"}");
    assertThat(renamed.getStatus()).isEqualTo(200);
    assertThat(json(renamed.getContentAsString()).get("name").asString()).isEqualTo("Kim Example");
    assertThat(users.findById(acmeCustomerUser.id()).orElseThrow().name()).isEqualTo("Kim Example");
    // The other customer is untouched.
    assertThat(users.findById(betaCustomerUser.id()).orElseThrow().name()).isEqualTo("Sam Example");

    assertThat(c.send(HttpMethod.PATCH, "/api/v1/portal/account", "{\"name\":\"   \"}").getStatus())
        .isEqualTo(400);
    assertThat(
            c.send(
                    HttpMethod.PATCH,
                    "/api/v1/portal/account",
                    "{\"name\":\"" + "x".repeat(201) + "\"}")
                .getStatus())
        .isEqualTo(400);
    // The body cannot change anything else.
    c.send(
        HttpMethod.PATCH,
        "/api/v1/portal/account",
        "{\"name\":\"Kim Example\",\"email\":\"attacker@example.test\",\"role\":\"ADMIN\"}");
    var me = users.findById(acmeCustomerUser.id()).orElseThrow();
    assertThat(me.email()).isEqualTo(acmeCustomerUser.email());
    assertThat(me.role()).isEqualTo(Role.CUSTOMER);
  }

  @Test
  void aCustomerCanStillChangeTheirPasswordThroughTheSharedEndpoint() throws Exception {
    Browser c = signedIn(acmeCustomerUser);
    MockHttpServletResponse r =
        c.post(
            "/api/v1/auth/password/change",
            "{\"currentPassword\":"
                + Browser.q("Violet-Tractor-Sundial-42")
                + ",\"newPassword\":"
                + Browser.q("Copper-Lantern-Meadow-97")
                + "}");
    assertThat(r.getStatus()).isIn(200, 204);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }
}
