package com.ticketsystem.api.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.AppUser;
import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A half-signed-in session (password done, two-step set-up or code still owed; spec section 7,
 * WP-02c) reaches no real agent endpoint, admin endpoint or ticket attachment. The web app hides
 * its shell for such a session (fix/mfa-guard-agents-table), but the server is the gate: these
 * calls answer 403, and the same calls answer 200 once the user finishes the step, which proves the
 * 403 comes from the auth-level gate and not from a missing route or record.
 */
class HalfSignedInAccessIntegrationTest extends TicketTestBase {

  private Browser passwordOnly(AppUser user, String expectedLevel) throws Exception {
    Browser b = browser();
    String body =
        b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD))
            .getContentAsString();
    assertThat(body).contains("\"authLevel\":\"" + expectedLevel + "\"");
    return b;
  }

  private void assertRefused(Browser b, UUID file) throws Exception {
    assertThat(b.get("/api/v1/agent/tickets").getStatus()).as("agent tickets").isEqualTo(403);
    assertThat(b.get("/api/v1/agent/me/profile").getStatus()).as("agent profile").isEqualTo(403);
    assertThat(b.get("/api/v1/admin/supporters").getStatus()).as("admin agents").isEqualTo(403);
    assertThat(b.get("/api/v1/agent/attachments/" + file).getStatus())
        .as("attachment")
        .isEqualTo(403);
    assertThat(b.get("/api/v1/agent/attachments/" + file + "/thumbnail").getStatus())
        .as("thumbnail")
        .isEqualTo(403);
    assertThat(uploadVia(b, "/api/v1/agent/attachments", "x.txt", "x".getBytes()).getStatus())
        .as("upload")
        .isEqualTo(403);
  }

  private void assertAllowedWhenFull(AppUser user, UUID file) throws Exception {
    Browser full = signedIn(user);
    assertThat(full.get("/api/v1/agent/tickets").getStatus()).isEqualTo(200);
    assertThat(full.get("/api/v1/agent/me/profile").getStatus()).isEqualTo(200);
    assertThat(full.get("/api/v1/admin/supporters").getStatus()).isEqualTo(200);
    assertThat(full.get("/api/v1/agent/attachments/" + file).getStatus()).isEqualTo(200);
  }

  @Test
  void codeOwedSessionGetsNoAgentAdminOrAttachmentData() throws Exception {
    if (!secrets.containsKey(adminUser.id())) {
      enrol(adminUser);
    }
    UUID file = upload(admin, "notes.txt", "synthetic".getBytes());
    assertRefused(passwordOnly(adminUser, "MFA_REQUIRED"), file);
    assertAllowedWhenFull(adminUser, file);
  }

  @Test
  void setUpOwedSessionGetsNoAgentAdminOrAttachmentData() throws Exception {
    AppUser fresh = activeUser(Role.ADMIN);
    UUID file = upload(AccessScope.admin(fresh.id()), "notes.txt", "synthetic".getBytes());
    assertRefused(passwordOnly(fresh, "MFA_ENROLMENT_REQUIRED"), file);
    assertAllowedWhenFull(fresh, file);
  }
}
