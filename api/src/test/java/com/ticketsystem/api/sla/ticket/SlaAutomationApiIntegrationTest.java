package com.ticketsystem.api.sla.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.sla.automation.clientdata.SlaAutomationRepository;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * ADR-049: the admin SLA automation API - create, edit, turn off, reorder and delete, each audited;
 * validation; admin only (agents and customers 403); the repository refuses non-admin scopes; and
 * every endpoint is documented in {@code openapi/sla-automation.yaml}.
 */
class SlaAutomationApiIntegrationTest extends SlaTestBase {

  private static final String BASE = "/api/v1/admin/sla-automation";

  @Autowired SlaAutomationRepository repository;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private Browser adminBrowser;

  @BeforeEach
  void signIn() throws Exception {
    jdbc.update("DELETE FROM sla_automation_rule");
    clock.reset();
    adminBrowser = signedIn(adminUser);
  }

  @AfterEach
  void noRules() {
    jdbc.update("DELETE FROM sla_automation_rule");
  }

  private String body(String name, String trigger, String conditions, String actions) {
    return "{\"name\":\""
        + name
        + "\",\"trigger\":\""
        + trigger
        + "\",\"conditions\":"
        + conditions
        + ",\"actions\":"
        + actions
        + "}";
  }

  private String noteAction(String text) {
    return "[{\"type\":\"ADD_NOTE\",\"text\":\"" + text + "\"}]";
  }

  private long create(String name) throws Exception {
    MockHttpServletResponse r =
        adminBrowser.post(
            BASE + "/rules",
            body(name, "FIRST_RESPONSE_BREACHED", "{}", noteAction("Breached - please look")));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    return json(r.getContentAsString()).get("id").asLong();
  }

  private long ruleAudits(String type, long ruleId) {
    return auditEvents(type, null).stream()
        .filter(a -> a.get("details").toString().contains("\"rule\": " + ruleId))
        .count();
  }

  @Test
  void anAdminCreatesEditsTurnsOffReordersAndDeletesRules() throws Exception {
    String conditions =
        "{\"includeTriage\":true,\"organisationIds\":["
            + acme
            + "],\"priorities\":[\"P1\",\"P2\"],\"requestTypeIds\":["
            + generalType
            + "]}";
    String actions =
        "[{\"type\":\"NOTIFY_AGENTS\",\"agentIds\":["
            + acmeAgentUser.id()
            + "]},{\"type\":\"NOTIFY_ORGANISATION\"},{\"type\":\"ASSIGN\",\"agentId\":"
            + acmeAgentUser.id()
            + "},{\"type\":\"RAISE_PRIORITY\"},{\"type\":\"ADD_NOTE\",\"text\":\"Line one\\nLine"
            + " two\"}]";
    MockHttpServletResponse created =
        adminBrowser.post(
            BASE + "/rules", body("Escalate breaches", "RESOLUTION_AT_RISK", conditions, actions));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    JsonNode rule = json(created.getContentAsString());
    long first = rule.get("id").asLong();
    assertThat(rule.get("enabled").asBoolean()).isTrue();
    assertThat(rule.get("trigger").asString()).isEqualTo("RESOLUTION_AT_RISK");
    assertThat(rule.get("conditions").get("includeTriage").asBoolean()).isTrue();
    assertThat(rule.get("conditions").get("priorities").size()).isEqualTo(2);
    assertThat(rule.get("actions").size()).isEqualTo(5);
    assertThat(rule.get("actions").get(4).get("text").asString()).isEqualTo("Line one\nLine two");
    long second = create("Second");

    JsonNode list = json(adminBrowser.get(BASE + "/rules").getContentAsString());
    assertThat(list.size()).isEqualTo(2);
    assertThat(list.get(0).get("id").asLong()).isEqualTo(first);

    MockHttpServletResponse updated =
        adminBrowser.put(
            BASE + "/rules/" + first,
            body("Renamed", "RESOLUTION_BREACHED", "{}", noteAction("Now on breach")));
    assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
    assertThat(json(updated.getContentAsString()).get("name").asString()).isEqualTo("Renamed");

    MockHttpServletResponse off =
        adminBrowser.put(BASE + "/rules/" + first + "/enabled", "{\"enabled\":false}");
    assertThat(off.getStatus()).isEqualTo(200);
    assertThat(json(off.getContentAsString()).get("enabled").asBoolean()).isFalse();

    MockHttpServletResponse order =
        adminBrowser.put(BASE + "/order", "{\"ids\":[" + second + "," + first + "]}");
    assertThat(order.getStatus()).as(order.getContentAsString()).isEqualTo(200);
    assertThat(json(order.getContentAsString()).get(0).get("id").asLong()).isEqualTo(second);

    assertThat(adminBrowser.delete(BASE + "/rules/" + first).getStatus()).isEqualTo(204);
    assertThat(adminBrowser.delete(BASE + "/rules/" + first).getStatus()).isEqualTo(404);
    assertThat(json(adminBrowser.get(BASE + "/rules").getContentAsString()).size()).isEqualTo(1);

    assertThat(ruleAudits("SLA_AUTOMATION_RULE_CREATED", first)).isEqualTo(1);
    assertThat(ruleAudits("SLA_AUTOMATION_RULE_UPDATED", first)).isEqualTo(1);
    assertThat(ruleAudits("SLA_AUTOMATION_RULE_TOGGLED", first)).isEqualTo(1);
    assertThat(ruleAudits("SLA_AUTOMATION_RULE_DELETED", first)).isEqualTo(1);
    assertThat(auditEvents("SLA_AUTOMATION_RULES_REORDERED", null)).isNotEmpty();
    // The note text is never in the audit log.
    assertThat(auditEvents("SLA_AUTOMATION_RULE_CREATED", null))
        .noneMatch(a -> a.get("details").toString().contains("Line one"));
  }

  @Test
  void invalidRulesAreRefused() throws Exception {
    String t = "FIRST_RESPONSE_BREACHED";
    String note = noteAction("Look");
    List<String> bad =
        List.of(
            body("", t, "{}", note),
            body("No actions", t, "{}", "[]"),
            body("Bad trigger", "SOMETIME", "{}", note),
            body("Unknown org", t, "{\"organisationIds\":[999999999]}", note),
            body("Unknown type", t, "{\"requestTypeIds\":[999999999]}", note),
            body("Unknown agent", t, "{}", "[{\"type\":\"ASSIGN\",\"agentId\":999999999}]"),
            body(
                "A customer",
                t,
                "{}",
                "[{\"type\":\"ASSIGN\",\"agentId\":" + acmeCustomerUser.id() + "}]"),
            body("Nobody to tell", t, "{}", "[{\"type\":\"NOTIFY_AGENTS\",\"agentIds\":[]}]"),
            body("Assign nobody", t, "{}", "[{\"type\":\"ASSIGN\"}]"),
            body("Empty note", t, "{}", noteAction("  ")),
            body("Long note", t, "{}", noteAction("x".repeat(1001))),
            body("No type", t, "{}", "[{\"text\":\"x\"}]"));
    for (String b : bad) {
      MockHttpServletResponse r = adminBrowser.post(BASE + "/rules", b);
      assertThat(r.getStatus()).as(b + " -> " + r.getContentAsString()).isEqualTo(400);
    }
    long id = create("Valid");
    assertThat(adminBrowser.put(BASE + "/order", "{\"ids\":[]}").getStatus()).isEqualTo(400);
    assertThat(adminBrowser.put(BASE + "/order", "{\"ids\":[" + id + "," + id + "]}").getStatus())
        .isEqualTo(400);
    assertThat(adminBrowser.put(BASE + "/rules/999999999", body("X", t, "{}", note)).getStatus())
        .isEqualTo(404);
    assertThat(
            adminBrowser.put(BASE + "/rules/999999999/enabled", "{\"enabled\":true}").getStatus())
        .isEqualTo(404);
  }

  @Test
  void agentsAndCustomersGet403() throws Exception {
    long id = create("Admin only");
    for (Browser b : List.of(signedIn(acmeAgentUser), signedIn(acmeCustomerUser))) {
      assertThat(b.get(BASE + "/rules").getStatus()).isEqualTo(403);
      assertThat(
              b.post(BASE + "/rules", body("X", "FIRST_RESPONSE_MET", "{}", noteAction("x")))
                  .getStatus())
          .isEqualTo(403);
      assertThat(
              b.put(BASE + "/rules/" + id, body("X", "FIRST_RESPONSE_MET", "{}", noteAction("x")))
                  .getStatus())
          .isEqualTo(403);
      assertThat(b.put(BASE + "/rules/" + id + "/enabled", "{\"enabled\":false}").getStatus())
          .isEqualTo(403);
      assertThat(b.put(BASE + "/order", "{\"ids\":[" + id + "]}").getStatus()).isEqualTo(403);
      assertThat(b.delete(BASE + "/rules/" + id).getStatus()).isEqualTo(403);
    }
    assertThat(json(adminBrowser.get(BASE + "/rules").getContentAsString()).size()).isEqualTo(1);
    // Defence in depth: the repository refuses anything but an admin (or the SYSTEM engine).
    assertThatThrownBy(() -> repository.list(acmeAgent)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> repository.list(acmeCustomer)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> repository.delete(acmeAgent, id)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> repository.anyEnabled(admin)).isInstanceOf(RuntimeException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyEndpointIsDocumentedAndNothingElse() throws Exception {
    Set<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        if (p.startsWith(BASE)) {
          for (RequestMethod m : info.getMethodsCondition().getMethods()) {
            registered.add(m.name().toLowerCase(Locale.ROOT) + " " + p);
          }
        }
      }
    }
    Map<String, Object> doc;
    try (InputStream in = getClass().getResourceAsStream("/openapi/sla-automation.yaml")) {
      doc = new Yaml().load(in);
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).hasSize(6);
    assertThat(documented).isEqualTo(registered);
  }
}
