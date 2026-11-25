package com.ticketsystem.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.mfa.Totp;
import com.ticketsystem.api.security.SecurityGoLive;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WP-02c TOTP flows through the real filter chain and Postgres: forced enrolment, sign-in codes,
 * back-off, replay, recovery codes, trusted devices, step-up and the pre-MFA access limits.
 */
@SecurityGoLive({4})
class MfaIntegrationTest extends AuthIntegrationTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode json(MockHttpServletResponse r) throws Exception {
    return JSON.readTree(r.getContentAsString());
  }

  private static String code(String value) {
    return "{\"code\":" + Browser.q(value) + "}";
  }

  /** RFC 4648 base32 decode, for the manual key the enrolment page shows. */
  static byte[] base32Decode(String s) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : s.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  private Browser passwordOnly(AppUser user) throws Exception {
    Browser b = browser();
    assertThat(b.post("/api/v1/auth/sign-in", signInJson(user.email(), GOOD_PASSWORD)).getStatus())
        .isEqualTo(200);
    return b;
  }

  /** Enrols through the real API; returns the recovery codes and records the secret. */
  private List<String> enrolThroughApi(AppUser user, Browser b) throws Exception {
    MockHttpServletResponse start = b.post("/api/v1/auth/mfa/enrolment", "{}");
    assertThat(start.getStatus()).isEqualTo(200);
    byte[] secret = base32Decode(json(start).get("manualKey").asString());
    secrets.put(user.id(), secret);
    MockHttpServletResponse confirm =
        b.post(
            "/api/v1/auth/mfa/enrolment/confirm",
            code(Totp.codeAt(secret, Totp.stepAt(clock.instant()))));
    assertThat(confirm.getStatus()).isEqualTo(200);
    List<String> codes = new ArrayList<>();
    json(confirm).get("recoveryCodes").forEach(n -> codes.add(n.asString()));
    return codes;
  }

  @Test
  void firstSupporterSignInForcesEnrolmentAndOnlyThenOpensTheAgentTree() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = passwordOnly(agent);
    assertThat(b.get("/api/v1/auth/me").getContentAsString())
        .contains("\"authLevel\":\"MFA_ENROLMENT_REQUIRED\"");
    assertThat(b.get("/api/v1/agent/anything").getStatus()).isEqualTo(403);
    assertThat(b.post("/api/v1/auth/mfa/verify", code("123456")).getStatus()).isEqualTo(403);

    MockHttpServletResponse start = b.post("/api/v1/auth/mfa/enrolment", "{}");
    assertThat(start.getStatus()).isEqualTo(200);
    JsonNode body = json(start);
    String manualKey = body.get("manualKey").asString();
    assertThat(body.get("otpauthUri").asString())
        .startsWith("otpauth://totp/Example%20MSP%20Support:")
        .contains("secret=" + manualKey);
    assertThat(body.get("qrPath").asString()).startsWith("M");
    byte[] secret = base32Decode(manualKey);

    // Wrong code: refused, still not signed in.
    assertThat(b.post("/api/v1/auth/mfa/enrolment/confirm", code("000000")).getContentAsString())
        .contains("invalid_code");
    assertThat(b.get("/api/v1/agent/anything").getStatus()).isEqualTo(403);

    MockHttpServletResponse confirm =
        b.post(
            "/api/v1/auth/mfa/enrolment/confirm",
            code(Totp.codeAt(secret, Totp.stepAt(clock.instant()))));
    assertThat(confirm.getStatus()).isEqualTo(200);
    JsonNode confirmed = json(confirm);
    assertThat(confirmed.get("recoveryCodes")).hasSize(10);
    assertThat(confirmed.get("me").get("authLevel").asString()).isEqualTo("FULL");
    assertThat(b.get("/api/v1/agent/anything").getStatus()).isEqualTo(404);
    assertThat(mailer.sentTo(agent.email()))
        .extracting(RecordingSystemMailer.Sent::type)
        .contains("notice-MFA_ENROLLED");
    assertThat(auditEvents("MFA_ENROLLED", agent.id())).hasSize(1);

    // The secret is not readable in the database: only AES-GCM ciphertext with a key id.
    var row =
        jdbc.queryForMap(
            "SELECT secret_ciphertext, secret_key_id, pending_ciphertext FROM user_mfa WHERE"
                + " user_id = ?",
            agent.id());
    byte[] stored = (byte[]) row.get("secret_ciphertext");
    assertThat(row.get("secret_key_id")).isEqualTo("test1");
    assertThat(row.get("pending_ciphertext")).isNull();
    String storedHex = HexFormat.of().formatHex(stored);
    assertThat(storedHex).doesNotContain(HexFormat.of().formatHex(secret));
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(manualKey);
    // Recovery codes are stored hashed only.
    List<String> hashes =
        jdbc.queryForList(
            "SELECT code_hash FROM mfa_recovery_code WHERE user_id = ?", String.class, agent.id());
    assertThat(hashes).hasSize(10);
    String first = confirmed.get("recoveryCodes").get(0).asString();
    assertThat(hashes).noneMatch(h -> h.contains(first.replace("-", "")));
  }

  @Test
  void customersSignInFullyAndHaveNoMfa() throws Exception {
    Browser b = passwordOnly(activeUser(Role.CUSTOMER));
    assertThat(b.get("/api/v1/auth/me").getContentAsString()).contains("\"authLevel\":\"FULL\"");
    assertThat(b.post("/api/v1/auth/mfa/enrolment", "{}").getStatus()).isEqualTo(403);
    assertThat(b.post("/api/v1/auth/step-up", code("123456")).getStatus()).isEqualTo(403);
  }

  @Test
  void wrongCodesBackOffAndAreAudited() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    Browser b = passwordOnly(agent);
    for (int i = 0; i < 3; i++) {
      assertThat(b.post("/api/v1/auth/mfa/verify", code("000000")).getStatus()).isEqualTo(400);
    }
    MockHttpServletResponse throttled = b.post("/api/v1/auth/mfa/verify", code(totp(agent)));
    assertThat(throttled.getStatus()).isEqualTo(429);
    assertThat(throttled.getHeader("Retry-After")).isNotNull();
    assertThat(auditEvents("MFA_FAILED", agent.id())).hasSize(3);
    assertThat(auditEvents("MFA_THROTTLED", agent.id())).hasSize(1);

    clock.advance(Duration.ofSeconds(31));
    assertThat(b.post("/api/v1/auth/mfa/verify", code(totp(agent))).getStatus()).isEqualTo(200);
    assertThat(b.get("/api/v1/agent/anything").getStatus()).isEqualTo(404);
  }

  @Test
  void aCodeCannotBeReplayed() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    String c = totp(agent);
    Browser first = passwordOnly(agent);
    assertThat(first.post("/api/v1/auth/mfa/verify", code(c)).getStatus()).isEqualTo(200);
    Browser second = passwordOnly(agent);
    assertThat(second.post("/api/v1/auth/mfa/verify", code(c)).getContentAsString())
        .contains("invalid_code");
    assertThat(second.get("/api/v1/agent/anything").getStatus()).isEqualTo(403);
  }

  @Test
  void aRecoveryCodeSignsInOnceOnly() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    List<String> codes = enrolThroughApi(agent, passwordOnly(agent));

    Browser b = passwordOnly(agent);
    MockHttpServletResponse ok =
        b.post(
            "/api/v1/auth/mfa/recovery",
            "{\"recoveryCode\":" + Browser.q(codes.get(3).toLowerCase()) + "}");
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(json(ok).get("recoveryCodesLeft").asInt()).isEqualTo(9);
    assertThat(b.get("/api/v1/agent/anything").getStatus()).isEqualTo(404);
    assertThat(auditEvents("RECOVERY_CODE_USED", agent.id())).hasSize(1);

    Browser again = passwordOnly(agent);
    assertThat(
            again
                .post(
                    "/api/v1/auth/mfa/recovery",
                    "{\"recoveryCode\":" + Browser.q(codes.get(3)) + "}")
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void belowFullASessionReachesOnlyTheMfaStepsMeAndSignOut() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    enrol(admin);
    Browser b = passwordOnly(admin);
    for (String path :
        List.of(
            "/api/v1/portal/x",
            "/api/v1/agent/x",
            "/api/v1/admin/x",
            "/api/v1/auth/mfa",
            "/api/v1/auth/sessions",
            "/api/v1/auth/trusted-devices")) {
      assertThat(b.get(path).getStatus()).as(path).isEqualTo(403);
    }
    for (String path :
        List.of(
            "/api/v1/auth/password/change",
            "/api/v1/auth/step-up",
            "/api/v1/auth/mfa/enrolment",
            "/api/v1/auth/mfa/recovery-codes",
            "/api/v1/auth/sessions/sign-out-others",
            "/api/v1/admin/invitations")) {
      assertThat(b.post(path, "{}").getStatus()).as(path).isEqualTo(403);
    }
    assertThat(b.get("/api/v1/auth/me").getContentAsString())
        .contains("\"authLevel\":\"MFA_REQUIRED\"");

    // The MFA step must be finished within 10 minutes of the password.
    clock.advance(Duration.ofMinutes(10));
    assertThat(b.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
    assertThat(auditEvents("SESSION_INVALIDATED", admin.id())).hasSize(1);

    Browser c = passwordOnly(admin);
    assertThat(c.post("/api/v1/auth/sign-out", "{}").getStatus()).isEqualTo(204);
    assertThat(c.get("/api/v1/auth/me").getStatus()).isEqualTo(401);
  }

  @Test
  void aTrustedDeviceSkipsTheSignInCodeForThirtyDaysButNeverSkipsStepUp() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    enrol(admin);
    Browser b = passwordOnly(admin);
    assertThat(
            b.post(
                    "/api/v1/auth/mfa/verify",
                    "{\"code\":" + Browser.q(totp(admin)) + ",\"trustDevice\":true}")
                .getStatus())
        .isEqualTo(200);
    assertThat(json(b.get("/api/v1/auth/trusted-devices"))).hasSize(1);
    b.post("/api/v1/auth/sign-out", "{}");

    // Same browser: straight to FULL, no code.
    assertThat(
            b.post("/api/v1/auth/sign-in", signInJson(admin.email(), GOOD_PASSWORD))
                .getContentAsString())
        .contains("\"authLevel\":\"FULL\"");
    assertThat(b.get("/api/v1/admin/x").getStatus()).isEqualTo(404);
    // ...but a sensitive action still asks for a code.
    MockHttpServletResponse sensitive =
        b.post("/api/v1/admin/supporters/" + agent.id() + "/security/reset-mfa", "{}");
    assertThat(sensitive.getStatus()).isEqualTo(403);
    assertThat(sensitive.getContentAsString()).contains("step_up_required");

    // Another browser still needs the code.
    assertThat(passwordOnly(admin).get("/api/v1/auth/me").getContentAsString())
        .contains("MFA_REQUIRED");

    // Removing the device brings the prompt back.
    long deviceId = json(b.get("/api/v1/auth/trusted-devices")).get(0).get("id").asLong();
    assertThat(
            b.perform(
                    MockMvcRequestBuilders.delete("/api/v1/auth/trusted-devices/" + deviceId),
                    HttpMethod.DELETE)
                .getStatus())
        .isEqualTo(204);
    b.post("/api/v1/auth/sign-out", "{}");
    assertThat(
            b.post("/api/v1/auth/sign-in", signInJson(admin.email(), GOOD_PASSWORD))
                .getContentAsString())
        .contains("MFA_REQUIRED");
  }

  @Test
  void deviceTrustExpiresAfterThirtyDays() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    Browser b = passwordOnly(agent);
    b.post(
        "/api/v1/auth/mfa/verify",
        "{\"code\":" + Browser.q(totp(agent)) + ",\"trustDevice\":true}");
    b.post("/api/v1/auth/sign-out", "{}");
    clock.advance(Duration.ofDays(30).plusMinutes(1));
    assertThat(
            b.post("/api/v1/auth/sign-in", signInJson(agent.email(), GOOD_PASSWORD))
                .getContentAsString())
        .contains("MFA_REQUIRED");
  }

  @Test
  void stepUpLastsTenMinutesAndARecoveryCodeCountsOnce() throws Exception {
    AppUser admin = activeUser(Role.ADMIN);
    AppUser agent = activeUser(Role.AGENT);
    List<String> codes = enrolThroughApi(admin, passwordOnly(admin));
    enrol(agent);
    Browser b = signedIn(admin);
    String resetPath = "/api/v1/admin/supporters/" + agent.id() + "/security/reset-mfa";

    // The sign-in code counts as a step-up for 10 minutes.
    clock.advance(Duration.ofMinutes(10));
    assertThat(b.post(resetPath, "{}").getContentAsString()).contains("step_up_required");

    assertThat(b.post("/api/v1/auth/step-up", code("000000")).getStatus()).isEqualTo(400);
    assertThat(b.post("/api/v1/auth/step-up", code(totp(admin))).getStatus()).isEqualTo(200);
    assertThat(b.post(resetPath, "{}").getStatus()).isEqualTo(204);

    clock.advance(Duration.ofMinutes(10));
    assertThat(b.post(resetPath, "{}").getStatus()).isEqualTo(403);

    // A recovery code is accepted for step-up and used up (ADR-019).
    String recovery = "{\"recoveryCode\":" + Browser.q(codes.get(0)) + "}";
    MockHttpServletResponse viaRecovery = b.post("/api/v1/auth/step-up", recovery);
    assertThat(viaRecovery.getStatus()).isEqualTo(200);
    assertThat(json(viaRecovery).get("recoveryCodesLeft").asInt()).isEqualTo(9);
    assertThat(b.post(resetPath, "{}").getStatus()).isEqualTo(204);
    clock.advance(Duration.ofMinutes(11));
    assertThat(b.post("/api/v1/auth/step-up", recovery).getStatus()).isEqualTo(400);
    assertThat(auditEvents("STEP_UP_SUCCEEDED", admin.id())).hasSize(2);
  }

  @Test
  void regeneratingRecoveryCodesNeedsStepUpAndVoidsTheOldOnes() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    List<String> old = enrolThroughApi(agent, passwordOnly(agent));
    Browser b = signedIn(agent);
    clock.advance(Duration.ofMinutes(11));
    assertThat(b.post("/api/v1/auth/mfa/recovery-codes", "{}").getStatus()).isEqualTo(403);
    b.post("/api/v1/auth/step-up", code(totp(agent)));
    MockHttpServletResponse fresh = b.post("/api/v1/auth/mfa/recovery-codes", "{}");
    assertThat(fresh.getStatus()).isEqualTo(200);
    assertThat(json(fresh).get("recoveryCodes")).hasSize(10);
    assertThat(json(b.get("/api/v1/auth/mfa")).get("recoveryCodesLeft").asInt()).isEqualTo(10);

    Browser other = passwordOnly(agent);
    assertThat(
            other
                .post(
                    "/api/v1/auth/mfa/recovery", "{\"recoveryCode\":" + Browser.q(old.get(0)) + "}")
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void reEnrolmentNeedsStepUpAndReplacesTheSecret() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    enrol(agent);
    Browser b = signedIn(agent);
    byte[] oldSecret = secrets.get(agent.id());
    clock.advance(Duration.ofMinutes(11));
    assertThat(b.post("/api/v1/auth/mfa/enrolment", "{}").getContentAsString())
        .contains("step_up_required");
    b.post("/api/v1/auth/step-up", code(totp(agent)));

    enrolThroughApi(agent, b);
    assertThat(secrets.get(agent.id())).isNotEqualTo(oldSecret);
    // The old authenticator no longer works.
    clock.advance(Duration.ofSeconds(31));
    Browser other = passwordOnly(agent);
    assertThat(
            other
                .post(
                    "/api/v1/auth/mfa/verify",
                    code(Totp.codeAt(oldSecret, Totp.stepAt(clock.instant()))))
                .getStatus())
        .isEqualTo(400);
    assertThat(other.post("/api/v1/auth/mfa/verify", code(totp(agent))).getStatus()).isEqualTo(200);
    // This session stayed signed in.
    assertThat(b.get("/api/v1/agent/x").getStatus()).isEqualTo(404);
  }

  @Test
  void aDistributedPasswordAttackSlowsOnlyStrangersNotTheOwnersKnownBrowser() throws Exception {
    AppUser customer = activeUser(Role.CUSTOMER);
    Browser owner = signedIn(customer);
    owner.post("/api/v1/auth/sign-out", "{}");

    for (int i = 0; i < 11; i++) {
      browser().post("/api/v1/auth/sign-in", signInJson(customer.email(), "Wrong-Password-" + i));
    }
    assertThat(
            browser()
                .post("/api/v1/auth/sign-in", signInJson(customer.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(429);
    assertThat(
            owner
                .post("/api/v1/auth/sign-in", signInJson(customer.email(), GOOD_PASSWORD))
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void signInResponsesNeverCarryTheSecretOrCodesAfterwards() throws Exception {
    AppUser agent = activeUser(Role.AGENT);
    Browser b = passwordOnly(agent);
    enrolThroughApi(agent, b);
    String status = b.get("/api/v1/auth/mfa").getContentAsString();
    assertThat(status).contains("\"enrolled\":true").contains("\"recoveryCodesLeft\":10");
    Matcher m = Pattern.compile("[A-Z2-7]{32}").matcher(status);
    assertThat(m.find()).isFalse();
  }
}
