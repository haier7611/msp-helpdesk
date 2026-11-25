package com.ticketsystem.api.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.OrganisationTestBase;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Admin - Organisations over HTTP (spec 6, 7, 11a, 11b; ADR-014, ADR-019, ADR-022): create with
 * prefix rules, edit, prefix change, verified domains (public domains refused, unique across
 * organisations), delete, audit. Agents get 403 on all of it.
 */
class OrganisationAdminIntegrationTest extends OrganisationTestBase {

  private static final String BASE = "/api/v1/admin/organisations";

  private AppUser adminUser;
  private Browser admin;

  @BeforeEach
  void signInAdmin() throws Exception {
    adminUser = activeUser(Role.ADMIN);
    admin = signedIn(adminUser);
  }

  private static String uniqueName(String base) {
    return base + " " + UUID.randomUUID().toString().replace("-", "");
  }

  private static String uniqueDomain(String base) {
    return base + "-" + UUID.randomUUID().toString().replace("-", "") + ".example";
  }

  private static String createJson(String name, String prefix, List<String> domains) {
    StringBuilder d = new StringBuilder("[");
    for (int i = 0; i < domains.size(); i++) {
      d.append(i == 0 ? "" : ",").append(Browser.q(domains.get(i)));
    }
    d.append("]");
    return "{\"name\":"
        + Browser.q(name)
        + ",\"prefix\":"
        + Browser.q(prefix)
        + ",\"verifiedDomains\":"
        + d
        + ",\"timezone\":\"Asia/Ho_Chi_Minh\"}";
  }

  private JsonNode create(String name, String prefix, List<String> domains) throws Exception {
    MockHttpServletResponse r = admin.post(BASE, createJson(name, prefix, domains));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    return json(r.getContentAsString());
  }

  private static String error(MockHttpServletResponse r) throws Exception {
    return json(r.getContentAsString()).get("error").asString();
  }

  @Test
  void createStoresThePrefixUpperCaseWithDomainsAndAudits() throws Exception {
    String prefix = uniquePrefix();
    String domain = uniqueDomain("acme-dental");
    JsonNode org =
        create(
            uniqueName("Acme Dental"),
            prefix.toLowerCase(Locale.ROOT),
            List.of(domain.toUpperCase(Locale.ROOT)));

    assertThat(org.get("prefix").asString()).isEqualTo(prefix);
    assertThat(org.get("verifiedDomains").get(0).asString()).isEqualTo(domain);
    assertThat(org.get("timezone").asString()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(org.get("retiredPrefixes").size()).isZero();
    long id = org.get("id").asLong();
    assertThat(
            jdbc.queryForObject(
                "SELECT next_number FROM ticket_counter WHERE organisation_id = ?", Long.class, id))
        .isEqualTo(1L);
    List<Map<String, Object>> events = auditEvents("ORGANISATION_CREATED", null);
    assertThat(events)
        .anySatisfy(
            e -> {
              assertThat(e.get("actor_user_id")).isEqualTo(adminUser.id());
              assertThat(e.get("details").toString()).contains("\"prefix\": \"" + prefix + "\"");
            });
    assertThat(admin.get(BASE + "/" + id).getStatus()).isEqualTo(200);
  }

  @Test
  void badPrefixShapesAndTheTriagePrefixAreRefused() throws Exception {
    for (String bad : new String[] {"TRI", "tri", "1AB", "A", "ABCDEFG", "A-1", ""}) {
      MockHttpServletResponse r = admin.post(BASE, createJson(uniqueName("Bad"), bad, List.of()));
      assertThat(r.getStatus()).as(bad).isEqualTo(400);
      assertThat(error(r)).as(bad).isEqualTo("invalid_prefix");
    }
  }

  @Test
  void anExistingPrefixInAnyCaseIsRefused() throws Exception {
    String prefix = uniquePrefix();
    create(uniqueName("Acme Dental"), prefix, List.of());
    MockHttpServletResponse r =
        admin.post(
            BASE,
            createJson(uniqueName("Beta Clinic"), prefix.toLowerCase(Locale.ROOT), List.of()));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(error(r)).isEqualTo("prefix_taken");
    // Nothing half-created: the second organisation does not exist.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM organisation WHERE name LIKE 'Beta Clinic%' AND id NOT IN"
                    + " (SELECT organisation_id FROM organisation_prefix WHERE organisation_id IS"
                    + " NOT NULL)",
                Long.class))
        .isZero();
  }

  @Test
  void aPrefixChangeRetiresTheOldOneWhichNoOtherOrganisationMayTake() throws Exception {
    String oldPrefix = uniquePrefix();
    String newPrefix = uniquePrefix();
    long id = create(uniqueName("Acme Dental"), oldPrefix, List.of()).get("id").asLong();

    MockHttpServletResponse r =
        admin.put(BASE + "/" + id + "/prefix", "{\"prefix\":" + Browser.q(newPrefix) + "}");
    assertThat(r.getStatus()).isEqualTo(200);
    JsonNode org = json(r.getContentAsString());
    assertThat(org.get("prefix").asString()).isEqualTo(newPrefix);
    assertThat(org.get("retiredPrefixes").get(0).asString()).isEqualTo(oldPrefix);
    assertThat(auditEvents("ORGANISATION_PREFIX_CHANGED", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains(oldPrefix));

    MockHttpServletResponse other =
        admin.post(
            BASE,
            createJson(uniqueName("Beta Clinic"), oldPrefix.toLowerCase(Locale.ROOT), List.of()));
    assertThat(other.getStatus()).isEqualTo(409);
    assertThat(error(other)).isEqualTo("prefix_taken");

    // The organisation itself may go back to its own retired prefix.
    MockHttpServletResponse back =
        admin.put(BASE + "/" + id + "/prefix", "{\"prefix\":" + Browser.q(oldPrefix) + "}");
    assertThat(back.getStatus()).isEqualTo(200);
    assertThat(json(back.getContentAsString()).get("prefix").asString()).isEqualTo(oldPrefix);
    assertThat(json(back.getContentAsString()).get("retiredPrefixes").get(0).asString())
        .isEqualTo(newPrefix);
  }

  @Test
  void changingToAnotherOrganisationsPrefixOrTriIsRefusedAndChangesNothing() throws Exception {
    String acmePrefix = uniquePrefix();
    String betaPrefix = uniquePrefix();
    long acme = create(uniqueName("Acme Dental"), acmePrefix, List.of()).get("id").asLong();
    create(uniqueName("Beta Clinic"), betaPrefix, List.of());

    MockHttpServletResponse r =
        admin.put(BASE + "/" + acme + "/prefix", "{\"prefix\":" + Browser.q(betaPrefix) + "}");
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(activePrefixOf(acme)).isEqualTo(acmePrefix);

    MockHttpServletResponse tri = admin.put(BASE + "/" + acme + "/prefix", "{\"prefix\":\"tri\"}");
    assertThat(tri.getStatus()).isEqualTo(400);
    assertThat(activePrefixOf(acme)).isEqualTo(acmePrefix);
  }

  @Test
  void publicEmailDomainsCanNeverBeVerifiedDomains() throws Exception {
    MockHttpServletResponse r =
        admin.post(
            BASE, createJson(uniqueName("Acme Dental"), uniquePrefix(), List.of("gmail.com")));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(error(r)).isEqualTo("public_domain");

    long id = create(uniqueName("Acme Dental"), uniquePrefix(), List.of()).get("id").asLong();
    for (String d : new String[] {"Outlook.com", "@hotmail.com", "eu.gmail.com", "yahoo.co.uk"}) {
      MockHttpServletResponse add =
          admin.post(BASE + "/" + id + "/domains", "{\"domain\":" + Browser.q(d) + "}");
      assertThat(add.getStatus()).as(d).isEqualTo(400);
      assertThat(error(add)).as(d).isEqualTo("public_domain");
    }
    assertThat(admin.get(BASE + "/" + id).getContentAsString()).contains("\"verifiedDomains\":[]");
  }

  @Test
  void aVerifiedDomainBelongsToOneOrganisationOnly() throws Exception {
    String domain = uniqueDomain("acme-dental");
    long acme =
        create(uniqueName("Acme Dental"), uniquePrefix(), List.of(domain)).get("id").asLong();
    long beta = create(uniqueName("Beta Clinic"), uniquePrefix(), List.of()).get("id").asLong();

    MockHttpServletResponse add =
        admin.post(
            BASE + "/" + beta + "/domains",
            "{\"domain\":" + Browser.q(domain.toUpperCase(Locale.ROOT)) + "}");
    assertThat(add.getStatus()).isEqualTo(409);
    assertThat(error(add)).isEqualTo("domain_taken");

    MockHttpServletResponse createDup =
        admin.post(BASE, createJson(uniqueName("Gamma Care"), uniquePrefix(), List.of(domain)));
    assertThat(createDup.getStatus()).isEqualTo(409);

    // Removing it from Acme frees it for Beta; both edits are audited.
    assertThat(admin.delete(BASE + "/" + acme + "/domains/" + domain).getStatus()).isEqualTo(200);
    assertThat(
            admin
                .post(BASE + "/" + beta + "/domains", "{\"domain\":" + Browser.q(domain) + "}")
                .getStatus())
        .isEqualTo(200);
    assertThat(auditEvents("ORGANISATION_DOMAIN_REMOVED", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains(domain));
    assertThat(auditEvents("ORGANISATION_DOMAIN_ADDED", null))
        .anySatisfy(e -> assertThat(e.get("details").toString()).contains(domain));
    assertThat(admin.delete(BASE + "/" + acme + "/domains/" + domain).getStatus()).isEqualTo(404);
  }

  @Test
  void editChangesNameTimezoneAndTenantAndRefusesBadValues() throws Exception {
    long id = create(uniqueName("Acme Dental"), uniquePrefix(), List.of()).get("id").asLong();
    String name = uniqueName("Acme Dental Group");
    String tenant = "11111111-2222-3333-4444-555555555555";
    MockHttpServletResponse ok =
        admin.put(
            BASE + "/" + id,
            "{\"name\":"
                + Browser.q(name)
                + ",\"timezone\":\"Europe/London\",\"entraTenantId\":"
                + Browser.q(tenant)
                + "}");
    assertThat(ok.getStatus()).isEqualTo(200);
    JsonNode org = json(ok.getContentAsString());
    assertThat(org.get("name").asString()).isEqualTo(name);
    assertThat(org.get("timezone").asString()).isEqualTo("Europe/London");
    assertThat(org.get("entraTenantId").asString()).isEqualTo(tenant);

    assertThat(
            admin
                .put(BASE + "/" + id, "{\"name\":\"X\",\"timezone\":\"Mars/Olympus\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            admin
                .put(
                    BASE + "/" + id,
                    "{\"name\":\"X\",\"timezone\":\"UTC\",\"entraTenantId\":\"not-a-guid\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(admin.put(BASE + "/" + id, "{\"name\":\"  \",\"timezone\":\"UTC\"}").getStatus())
        .isEqualTo(400);
  }

  @Test
  void twoOrganisationsCannotShareAName() throws Exception {
    String name = uniqueName("Acme Dental");
    create(name, uniquePrefix(), List.of());
    MockHttpServletResponse r =
        admin.post(BASE, createJson(name.toUpperCase(Locale.ROOT), uniquePrefix(), List.of()));
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(error(r)).isEqualTo("name_taken");
  }

  @Test
  void deleteRefusesAnOrganisationWithCustomersAndRemovesAnEmptyOne() throws Exception {
    long withCustomer =
        create(uniqueName("Acme Dental"), uniquePrefix(), List.of()).get("id").asLong();
    activeUser(Role.CUSTOMER, withCustomer);
    MockHttpServletResponse r = admin.delete(BASE + "/" + withCustomer);
    assertThat(r.getStatus()).isEqualTo(409);
    assertThat(error(r)).isEqualTo("organisation_in_use");

    String prefix = uniquePrefix();
    long empty =
        create(uniqueName("Beta Clinic"), prefix, List.of(uniqueDomain("beta"))).get("id").asLong();
    assertThat(admin.delete(BASE + "/" + empty).getStatus()).isEqualTo(204);
    assertThat(admin.get(BASE + "/" + empty).getStatus()).isEqualTo(404);
    assertThat(auditEvents("ORGANISATION_DELETED", null))
        .anySatisfy(
            e -> assertThat(e.get("details").toString()).contains("\"organisation\": " + empty));
    assertThat(admin.delete(BASE + "/" + empty).getStatus()).isEqualTo(404);
  }

  @Test
  void unknownIdsAnswer404() throws Exception {
    assertThat(admin.get(BASE + "/999999999").getStatus()).isEqualTo(404);
    assertThat(admin.put(BASE + "/999999999/prefix", "{\"prefix\":\"ZZ9\"}").getStatus())
        .isEqualTo(404);
  }

  @Test
  void agentsAndCustomersGet403OnOrganisationManagement() throws Exception {
    long id = create(uniqueName("Acme Dental"), uniquePrefix(), List.of()).get("id").asLong();
    for (Browser b :
        new Browser[] {signedIn(activeUser(Role.AGENT)), signedIn(activeUser(Role.CUSTOMER))}) {
      assertThat(b.get(BASE).getStatus()).isEqualTo(403);
      assertThat(b.get(BASE + "/" + id).getStatus()).isEqualTo(403);
      assertThat(b.post(BASE, createJson(uniqueName("X"), uniquePrefix(), List.of())).getStatus())
          .isEqualTo(403);
      assertThat(b.put(BASE + "/" + id, "{\"name\":\"X\",\"timezone\":\"UTC\"}").getStatus())
          .isEqualTo(403);
      assertThat(b.put(BASE + "/" + id + "/prefix", "{\"prefix\":\"ZZ9\"}").getStatus())
          .isEqualTo(403);
      assertThat(b.post(BASE + "/" + id + "/domains", "{\"domain\":\"x.example\"}").getStatus())
          .isEqualTo(403);
      assertThat(b.delete(BASE + "/" + id).getStatus()).isEqualTo(403);
    }
    assertThat(activePrefixOf(id)).isNotEqualTo("ZZ9");
  }

  @Test
  void customerInvitationsNeedAnExistingOrganisationAndSupportersNone() throws Exception {
    long org = create(uniqueName("Acme Dental"), uniquePrefix(), List.of()).get("id").asLong();
    String path = "/api/v1/admin/invitations";
    String customerNoOrg =
        "{\"email\":" + Browser.q(uniqueEmail("c")) + ",\"name\":\"C\",\"role\":\"CUSTOMER\"}";
    assertThat(admin.post(path, customerNoOrg).getStatus()).isEqualTo(400);
    String agentWithOrg =
        "{\"email\":"
            + Browser.q(uniqueEmail("a"))
            + ",\"name\":\"A\",\"role\":\"AGENT\",\"organisationId\":"
            + org
            + "}";
    assertThat(admin.post(path, agentWithOrg).getStatus()).isEqualTo(400);
    String unknownOrg =
        "{\"email\":"
            + Browser.q(uniqueEmail("c"))
            + ",\"name\":\"C\",\"role\":\"CUSTOMER\",\"organisationId\":999999999}";
    MockHttpServletResponse unknown = admin.post(path, unknownOrg);
    assertThat(unknown.getStatus()).isEqualTo(400);
    assertThat(error(unknown)).isEqualTo("unknown_organisation");

    String email = uniqueEmail("c");
    String good =
        "{\"email\":"
            + Browser.q(email)
            + ",\"name\":\"C\",\"role\":\"CUSTOMER\",\"organisationId\":"
            + org
            + "}";
    assertThat(admin.post(path, good).getStatus()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT organisation_id FROM app_user WHERE email = ?", Long.class, email))
        .isEqualTo(org);
  }
}
