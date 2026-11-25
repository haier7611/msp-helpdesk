package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.ticket.TicketService.Created;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * WP-05 saved replies: shared plus personal visibility, another supporter's personal reply is not
 * found, bodies are sanitised, and placeholders are filled with HTML-escaped values (spec 12,
 * security test 3 "HTML in placeholders").
 */
@SecurityGoLive({3})
class SavedReplyIntegrationTest extends TicketTestBase {

  long shared;
  String sharedTitle;

  @BeforeEach
  void sharedReply() {
    sharedTitle = "Shared " + System.nanoTime();
    shared =
        jdbc.queryForObject(
            "INSERT INTO saved_reply (owner_user_id, title, body_html, created_at, updated_at)"
                + " VALUES (NULL, ?, ?, now(), now()) RETURNING id",
            Long.class,
            sharedTitle,
            "<p>Hi {client_first_name}, about {ticket_ref} for {organisation_name}. {unknown}"
                + " - {agent_name}</p>");
  }

  private static String replyJson(String title, String body) {
    return "{\"title\":" + Browser.q(title) + ",\"bodyHtml\":" + Browser.q(body) + "}";
  }

  private static List<Long> ids(String body) {
    List<Long> out = new ArrayList<>();
    json(body).forEach(n -> out.add(n.get("id").asLong()));
    return out;
  }

  @Test
  void personalRepliesArePrivateAndSharedOnesAreForEveryone() throws Exception {
    AppUser otherAgent = restrictedAgent(acme);
    Browser a = signedIn(acmeAgentUser);
    Browser b = signedIn(otherAgent);

    MockHttpServletResponse created =
        a.post("/api/v1/agent/saved-replies", replyJson("Mine", "<p>Thanks {client_name}</p>"));
    assertThat(created.getStatus()).isEqualTo(201);
    JsonNode mine = json(created.getContentAsString());
    long mineId = mine.get("id").asLong();
    assertThat(mine.get("shared").asBoolean()).isFalse();

    String listA = a.get("/api/v1/agent/saved-replies").getContentAsString();
    assertThat(ids(listA)).contains(shared, mineId);
    for (JsonNode n : json(listA)) {
      if (n.get("id").asLong() == shared) {
        assertThat(n.get("shared").asBoolean()).isTrue();
      }
    }
    assertThat(ids(b.get("/api/v1/agent/saved-replies").getContentAsString()))
        .contains(shared)
        .doesNotContain(mineId);

    Created t = acmeTicket();
    assertThat(
            b.put("/api/v1/agent/saved-replies/" + mineId, replyJson("X", "<p>x</p>")).getStatus())
        .isEqualTo(404);
    assertThat(b.delete("/api/v1/agent/saved-replies/" + mineId).getStatus()).isEqualTo(404);
    assertThat(
            b.post(
                    "/api/v1/agent/tickets/"
                        + t.ticketId()
                        + "/saved-replies/"
                        + mineId
                        + "/render",
                    "{}")
                .getStatus())
        .isEqualTo(404);
    // Shared replies cannot be edited or deleted from the agent tree (WP-11b).
    assertThat(
            a.put("/api/v1/agent/saved-replies/" + shared, replyJson("X", "<p>x</p>")).getStatus())
        .isEqualTo(404);
    assertThat(a.delete("/api/v1/agent/saved-replies/" + shared).getStatus()).isEqualTo(404);

    MockHttpServletResponse updated =
        a.put("/api/v1/agent/saved-replies/" + mineId, replyJson("Mine v2", "<p>v2</p>"));
    assertThat(updated.getStatus()).isEqualTo(200);
    assertThat(json(updated.getContentAsString()).get("title").asString()).isEqualTo("Mine v2");
    assertThat(a.delete("/api/v1/agent/saved-replies/" + mineId).getStatus()).isEqualTo(204);
    assertThat(ids(a.get("/api/v1/agent/saved-replies").getContentAsString()))
        .doesNotContain(mineId);
  }

  @Test
  void bodiesAreSanitisedAndValidated() throws Exception {
    Browser a = signedIn(acmeAgentUser);
    MockHttpServletResponse r =
        a.post(
            "/api/v1/agent/saved-replies",
            replyJson(
                "Unsafe",
                "<p onclick=\"x()\">Hello</p><script>alert(1)</script><img src=\"https://evil.example/x.png\">"));
    assertThat(r.getStatus()).isEqualTo(201);
    String body = json(r.getContentAsString()).get("bodyHtml").asString();
    assertThat(body).contains("Hello").doesNotContain("script").doesNotContain("onclick");
    assertThat(body).doesNotContain("evil.example");
    assertThat(a.post("/api/v1/agent/saved-replies", replyJson("", "<p>x</p>")).getStatus())
        .isEqualTo(400);
    assertThat(
            a.post("/api/v1/agent/saved-replies", replyJson("T".repeat(121), "<p>x</p>"))
                .getStatus())
        .isEqualTo(400);
    assertThat(a.post("/api/v1/agent/saved-replies", replyJson("Empty", "")).getStatus())
        .isEqualTo(400);
  }

  @Test
  void renderFillsPlaceholdersWithEscapedValues() throws Exception {
    jdbc.update(
        "UPDATE app_user SET name = ? WHERE id = ?", "<b>x</b> Smith", acmeCustomerUser.id());
    jdbc.update("UPDATE app_user SET name = ? WHERE id = ?", "Alex Tran", acmeAgentUser.id());
    Created t = acmeTicket();
    Browser a = signedIn(acmeAgentUser);
    MockHttpServletResponse r =
        a.post(
            "/api/v1/agent/tickets/" + t.ticketId() + "/saved-replies/" + shared + "/render", "{}");
    assertThat(r.getStatus()).isEqualTo(200);
    String html = json(r.getContentAsString()).get("bodyHtml").asString();
    assertThat(html)
        .contains("Hi &lt;b&gt;x&lt;/b&gt;,")
        .doesNotContain("<b>")
        .contains(t.reference().toString())
        .contains("Acme Dental")
        .contains("{unknown}")
        .contains("Alex Tran");
  }

  @Test
  void renderOnAnotherOrganisationsTicketIsNotFound() throws Exception {
    Created other = betaTicket();
    Browser a = signedIn(acmeAgentUser);
    assertThat(
            a.post(
                    "/api/v1/agent/tickets/"
                        + other.ticketId()
                        + "/saved-replies/"
                        + shared
                        + "/render",
                    "{}")
                .getStatus())
        .isEqualTo(404);
  }
}
