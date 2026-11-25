package com.ticketsystem.api.auth.entra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Unit checks for the parts of {@link EntraClient} that need no identity platform. */
class EntraClientTest {

  private final EntraClient client =
      new EntraClient(
          "https://login.microsoftonline.com/", Duration.ofSeconds(1), false, Clock.systemUTC());

  @Test
  void theIssuerIsBuiltPerTenantNeverAWildcard() {
    UUID tid = UUID.fromString("22222222-bbbb-4ccc-8ddd-000000000002");
    assertThat(client.issuerFor(tid))
        .isEqualTo("https://login.microsoftonline.com/22222222-bbbb-4ccc-8ddd-000000000002/v2.0");
  }

  @Test
  void theAuthorisationUrlCarriesPkceStateNonceAndQueryResponseMode() {
    URI url =
        client.authorizationUrl(
            "organizations",
            "client-1",
            "https://support.example.test/api/v1/auth/microsoft/callback",
            "state-1",
            "nonce-1",
            "challenge-1");
    assertThat(url.getHost()).isEqualTo("login.microsoftonline.com");
    assertThat(url.getPath()).isEqualTo("/organizations/oauth2/v2.0/authorize");
    assertThat(url.getQuery())
        .contains("response_type=code")
        .contains("response_mode=query")
        .contains("code_challenge_method=S256")
        .contains("state=state-1")
        .contains("nonce=nonce-1")
        .contains("scope=openid profile email");
  }

  @Test
  void onlyAnAadstsCodeOrAnOauthErrorNameIsKeptFromAnErrorAnswer() {
    JsonMapper json = new JsonMapper();
    assertThat(
            EntraClient.errorCode(
                json.readTree(
                    "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000222: The"
                        + " provided client secret keys are expired. Trace ID: abc\"}")))
        .isEqualTo("AADSTS7000222");
    assertThat(EntraClient.errorCode(json.readTree("{\"error\":\"invalid_grant\"}")))
        .isEqualTo("invalid_grant");
    assertThat(EntraClient.errorCode(json.readTree("{\"error\":\"<script>\"}")))
        .isEqualTo("unknown");
    assertThat(EntraClient.errorCode(null)).isEqualTo("unknown");
  }

  @Test
  void theAuthorityMustBeHttps() {
    assertThatThrownBy(
            () -> new EntraClient("ftp://x", Duration.ofSeconds(1), false, Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class);
    // Plain HTTP would send the client secret and fetch the signing keys in the clear.
    assertThatThrownBy(
            () ->
                new EntraClient(
                    "http://login.microsoftonline.com",
                    Duration.ofSeconds(1),
                    false,
                    Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> EntraClient.checkedAuthority("http://127.0.0.1:55501", false))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> EntraClient.checkedAuthority("https://", false))
        .isInstanceOf(IllegalStateException.class);
    // The test-only flag allows plain HTTP to a loopback address only, never a real host.
    assertThatThrownBy(() -> EntraClient.checkedAuthority("http://login.microsoftonline.com", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> EntraClient.checkedAuthority("http://127.0.0.1.evil.example", true))
        .isInstanceOf(IllegalStateException.class);
    assertThat(EntraClient.checkedAuthority("http://127.0.0.1:55501/", true))
        .isEqualTo("http://127.0.0.1:55501");
    assertThat(EntraClient.checkedAuthority("HTTPS://login.example.test/", false))
        .isEqualTo("HTTPS://login.example.test");
  }

  @Test
  void aMissingOrUnexpectedAcctClaimCountsAsAGuest() {
    UUID tid = UUID.randomUUID();
    UUID oid = UUID.randomUUID();
    assertThat(new EntraClient.IdToken(tid, oid, java.util.List.of(), null, false, 0).guest())
        .isFalse();
    assertThat(new EntraClient.IdToken(tid, oid, java.util.List.of(), null, false, 1).guest())
        .isTrue();
    assertThat(new EntraClient.IdToken(tid, oid, java.util.List.of(), null, false, null).guest())
        .isTrue();
    assertThat(new EntraClient.IdToken(tid, oid, java.util.List.of(), null, false, 7).guest())
        .isTrue();
  }

  @Test
  void aTokenThatIsNotAJwtIsInvalid() {
    assertThatThrownBy(() -> client.validate("not-a-jwt", "client-1", "nonce"))
        .isInstanceOf(EntraClient.InvalidToken.class);
  }

  @Test
  void pkceChallengeIsTheRfc7636Example() {
    // RFC 7636 appendix B.
    assertThat(MicrosoftSignInService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  }
}
