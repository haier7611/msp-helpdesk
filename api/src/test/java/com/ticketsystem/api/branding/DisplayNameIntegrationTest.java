package com.ticketsystem.api.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.AuthIntegrationTestBase;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.mail.MailSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-045: GET /api/v1/display-name is public and read-only, returns only the display name, and
 * follows Admin - Branding, then MAIL_SUPPORT_NAME, then the neutral default. Synthetic names only.
 */
class DisplayNameIntegrationTest extends AuthIntegrationTestBase {

  private static final String PATH = "/api/v1/display-name";
  private static final JsonMapper JSON = new JsonMapper();

  @Autowired MailSettings mailSettings;

  private String savedName;

  @BeforeEach
  void remember() {
    savedName =
        jdbc.queryForObject(
            "SELECT support_name FROM signature_template WHERE id = 1", String.class);
  }

  @AfterEach
  void restore() {
    jdbc.update("UPDATE signature_template SET support_name = ? WHERE id = 1", savedName);
  }

  private void setBrandingName(String name) {
    jdbc.update("UPDATE signature_template SET support_name = ? WHERE id = 1", name);
  }

  @Test
  void anonymousVisitorGetsOnlyTheBrandingName() throws Exception {
    setBrandingName("Example Helpdesk Team");

    MockHttpServletResponse response = browser().get(PATH);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).contains("no-cache");
    JsonNode body = JSON.readTree(response.getContentAsString());
    assertThat(body.propertyNames()).containsExactly("name");
    assertThat(body.get("name").asString()).isEqualTo("Example Helpdesk Team");
  }

  @Test
  void anAdminChangeShowsOnTheNextRead() throws Exception {
    setBrandingName("Example Helpdesk Team");
    assertThat(nameFrom(browser().get(PATH))).isEqualTo("Example Helpdesk Team");
    setBrandingName("Example Service Desk");
    assertThat(nameFrom(browser().get(PATH))).isEqualTo("Example Service Desk");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void noBrandingNameFallsBackToTheEnvironmentName(String branding) throws Exception {
    setBrandingName(branding);

    assertThat(nameFrom(browser().get(PATH))).isEqualTo(mailSettings.supportName());
  }

  @Test
  void signedInUsersCanReadItToo() throws Exception {
    setBrandingName("Example Helpdesk Team");
    var customer = signedIn(activeUser(Role.CUSTOMER, newOrganisation("Example Bakery")));

    assertThat(nameFrom(customer.get(PATH))).isEqualTo("Example Helpdesk Team");
  }

  @Test
  void itIsReadOnly() throws Exception {
    // Only GET is open; anything else is refused (401 for a visitor) and changes nothing.
    setBrandingName("Example Helpdesk Team");
    assertThat(browser().post(PATH, "{\"name\":\"Changed\"}").getStatus()).isIn(401, 403);
    assertThat(browser().put(PATH, "{\"name\":\"Changed\"}").getStatus()).isIn(401, 403);
    assertThat(nameFrom(browser().get(PATH))).isEqualTo("Example Helpdesk Team");
  }

  private static String nameFrom(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return JSON.readTree(response.getContentAsString()).get("name").asString();
  }

  /** The last fallback, for an empty name from every source. */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "\t"})
  void aBlankNameBecomesTheNeutralDefault(String configured) {
    assertThat(DisplayNameController.resolve(configured)).isEqualTo("Support desk");
  }

  @Test
  void theNameIsTrimmed() {
    assertThat(DisplayNameController.resolve("  Example Helpdesk  ")).isEqualTo("Example Helpdesk");
  }
}
