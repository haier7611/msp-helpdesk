package com.ticketsystem.api.auth.entra;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Microsoft identity platform (v2.0 endpoints): builds the authorisation URL (authorisation
 * code flow with PKCE S256, state and nonce, {@code response_mode=query}), redeems the code at the
 * token endpoint, and validates the ID token.
 *
 * <p>ID token checks: RS256 signature against the Microsoft signing keys ({@code
 * {authority}/common/discovery/v2.0/keys}, cached); {@code aud} is exactly the client ID; {@code
 * iss} is exactly {@code {authority}/{tid}/v2.0} for the token's own {@code tid} - the multi-tenant
 * issuer is checked per tenant, never wildcard-trusted; {@code exp}, {@code nbf} and {@code iat}
 * against the server clock with 60 s skew; {@code nonce} equal to the one in the session; {@code
 * tid} and {@code oid} present GUIDs; personal Microsoft accounts refused. Which tenants may sign
 * in is the caller's decision.
 *
 * <p>The authority is {@code https://login.microsoftonline.com} unless {@code app.entra.authority}
 * points elsewhere (tests only; it is a server setting, never an admin one). It must be {@code
 * https://}, so the client secret, the code and the signing keys never travel in plain text (and
 * the expected issuer, built from it, is https too). The only exception is a loopback address
 * ({@code 127.0.0.1}, {@code localhost}, {@code [::1]}) when the test-only property {@code
 * app.entra.allow-insecure-loopback-authority} is true. Nothing secret or token-bearing is ever
 * logged: errors carry only the HTTP status and an {@code AADSTS} code.
 */
@Component
public class EntraClient {

  private static final Logger log = LoggerFactory.getLogger(EntraClient.class);

  /** The tenant id Microsoft uses for personal (consumer) accounts. */
  static final UUID CONSUMER_TENANT = UUID.fromString("9188040d-6c67-4c5b-b112-36a304b66dad");

  static final String SCOPES = "openid profile email";
  static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
  private static final Pattern AADSTS = Pattern.compile("AADSTS\\d{4,8}");
  private static final Pattern OAUTH_ERROR = Pattern.compile("[a-z_]{1,64}");

  /** The validated facts the application uses from an ID token. */
  public record IdToken(
      UUID tenantId,
      UUID objectId,
      List<String> amr,
      String email,
      boolean emailDomainVerified,
      Integer accountType) {

    /** Entra says MFA was done for this sign-in ({@code amr} contains {@code mfa}). */
    public boolean mfaClaimed() {
      return amr.contains("mfa");
    }

    /**
     * Not known to be a member of the tenant: a B2B guest ({@code acct} = 1), or the optional
     * {@code acct} claim is missing or unexpected. Fails closed, like {@code amr} and {@code
     * xms_edov}: only {@code acct} = 0 counts as a member.
     */
    public boolean guest() {
      return accountType == null || accountType != 0;
    }

    @Override
    public String toString() {
      return "IdToken[tid=" + tenantId + "]";
    }
  }

  /** Microsoft could not be reached, timed out or failed (5xx) - "use your password". */
  public static class Unavailable extends RuntimeException {
    Unavailable(String message) {
      super(message, null, false, false);
    }
  }

  /** Microsoft refused the request (4xx), e.g. a wrong or expired client secret. */
  public static class Rejected extends RuntimeException {
    private final String errorCode;

    Rejected(String errorCode) {
      super("rejected: " + errorCode, null, false, false);
      this.errorCode = errorCode;
    }

    /** {@code AADSTS...} or the OAuth error name; safe to show and audit. */
    public String errorCode() {
      return errorCode;
    }
  }

  /** The ID token failed a check; {@code reason} is a short fixed word for the audit log. */
  public static class InvalidToken extends RuntimeException {
    private final String reason;

    InvalidToken(String reason) {
      super("invalid id token: " + reason, null, false, false);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }

  private final String authority;
  private final Duration timeout;
  private final Clock clock;
  private final RestClient http;
  private final JsonMapper json = new JsonMapper();
  private final DefaultJWTProcessor<SecurityContext> processor;

  public EntraClient(
      @Value("${app.entra.authority:https://login.microsoftonline.com}") String authority,
      @Value("${app.entra.timeout:10s}") Duration timeout,
      @Value("${app.entra.allow-insecure-loopback-authority:false}") boolean allowInsecureLoopback,
      Clock clock) {
    this.authority = checkedAuthority(authority, allowInsecureLoopback);
    this.timeout = timeout;
    this.clock = clock;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(timeout);
    this.http = RestClient.builder().requestFactory(factory).build();
    this.processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys()));
    // Claims are checked below against the application clock (testable), not Nimbus's own.
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
  }

  public String authority() {
    return authority;
  }

  /**
   * The issuer an ID token from {@code tenantId} must carry.
   *
   * <p>Visible for tests.
   */
  String issuerFor(UUID tenantId) {
    return authority + "/" + tenantId + "/v2.0";
  }

  /**
   * The URL the browser is sent to. {@code tenantSegment} is {@code organizations} for sign-in (any
   * work account; the tenant is checked afterwards) or a tenant id for linking and testing.
   */
  public URI authorizationUrl(
      String tenantSegment,
      String clientId,
      String redirectUri,
      String state,
      String nonce,
      String codeChallenge) {
    return UriComponentsBuilder.fromUriString(authority)
        .pathSegment(tenantSegment, "oauth2", "v2.0", "authorize")
        .queryParam("client_id", clientId)
        .queryParam("response_type", "code")
        .queryParam("redirect_uri", redirectUri)
        .queryParam("response_mode", "query")
        .queryParam("scope", SCOPES)
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .queryParam("prompt", "select_account")
        .encode(StandardCharsets.UTF_8)
        .build()
        .toUri();
  }

  /** Redeems the authorisation code; returns the raw ID token (never logged). */
  public String redeem(
      String tenantSegment,
      String clientId,
      String clientSecret,
      String code,
      String redirectUri,
      String codeVerifier) {
    URI tokenUri =
        UriComponentsBuilder.fromUriString(authority)
            .pathSegment(tenantSegment, "oauth2", "v2.0", "token")
            .build()
            .toUri();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", redirectUri);
    form.add("code_verifier", codeVerifier);
    form.add("scope", SCOPES);
    TokenAnswer answer;
    try {
      answer =
          http.post()
              .uri(tokenUri)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .exchange(
                  (request, response) ->
                      new TokenAnswer(
                          response.getStatusCode().value(),
                          new String(
                              response.getBody().readNBytes(256 * 1024), StandardCharsets.UTF_8)),
                  true);
    } catch (ResourceAccessException e) {
      log.warn("Microsoft token endpoint unreachable ({})", e.getClass().getSimpleName());
      throw new Unavailable("token endpoint unreachable");
    }
    if (answer == null || answer.status() >= 500) {
      log.warn("Microsoft token endpoint failed (HTTP {})", answer == null ? 0 : answer.status());
      throw new Unavailable("token endpoint failed");
    }
    JsonNode body;
    try {
      body = json.readTree(answer.body());
    } catch (RuntimeException e) {
      throw new Unavailable("token endpoint answer not JSON");
    }
    if (answer.status() != 200) {
      String errorCode = errorCode(body);
      log.warn("Microsoft refused the code redemption (HTTP {}, {})", answer.status(), errorCode);
      throw new Rejected(errorCode);
    }
    JsonNode idToken = body.get("id_token");
    if (idToken == null || !idToken.isString() || idToken.asString().isEmpty()) {
      throw new InvalidToken("no_id_token");
    }
    return idToken.asString();
  }

  private record TokenAnswer(int status, String body) {}

  /** The first AADSTS code in the description, else the OAuth error name, else "unknown". */
  static String errorCode(JsonNode body) {
    JsonNode description = body == null ? null : body.get("error_description");
    if (description != null && description.isString()) {
      Matcher m = AADSTS.matcher(description.asString());
      if (m.find()) {
        return m.group();
      }
    }
    JsonNode error = body == null ? null : body.get("error");
    if (error != null && error.isString() && OAUTH_ERROR.matcher(error.asString()).matches()) {
      return error.asString();
    }
    return "unknown";
  }

  /** Validates the ID token for {@code clientId} and the session's {@code nonce}. */
  public IdToken validate(String idToken, String clientId, String expectedNonce) {
    JWTClaimsSet claims;
    try {
      claims = processor.process(idToken, null);
    } catch (KeySourceException e) {
      log.warn("Microsoft signing keys unavailable ({})", e.getClass().getSimpleName());
      throw new Unavailable("signing keys unavailable");
    } catch (ParseException | BadJOSEException | JOSEException e) {
      throw new InvalidToken("signature");
    }
    try {
      return check(claims, clientId, expectedNonce);
    } catch (ParseException e) {
      throw new InvalidToken("claims");
    }
  }

  private IdToken check(JWTClaimsSet claims, String clientId, String expectedNonce)
      throws ParseException {
    List<String> audience = claims.getAudience();
    if (audience == null || audience.size() != 1 || !audience.get(0).equals(clientId)) {
      throw new InvalidToken("audience");
    }
    UUID tid = uuid(claims.getStringClaim("tid"), "tenant");
    UUID oid = uuid(claims.getStringClaim("oid"), "object");
    if (CONSUMER_TENANT.equals(tid)) {
      throw new InvalidToken("personal_account");
    }
    if (!issuerFor(tid).equals(claims.getIssuer())) {
      throw new InvalidToken("issuer");
    }
    Instant now = clock.instant();
    Date exp = claims.getExpirationTime();
    if (exp == null || !exp.toInstant().plus(CLOCK_SKEW).isAfter(now)) {
      throw new InvalidToken("expired");
    }
    Date nbf = claims.getNotBeforeTime();
    if (nbf != null && nbf.toInstant().minus(CLOCK_SKEW).isAfter(now)) {
      throw new InvalidToken("not_yet_valid");
    }
    Date iat = claims.getIssueTime();
    if (iat == null || iat.toInstant().minus(CLOCK_SKEW).isAfter(now)) {
      throw new InvalidToken("issued_at");
    }
    String nonce = claims.getStringClaim("nonce");
    if (nonce == null
        || expectedNonce == null
        || !MessageDigest.isEqual(
            nonce.getBytes(StandardCharsets.UTF_8),
            expectedNonce.getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidToken("nonce");
    }
    String version = claims.getStringClaim("ver");
    if (version != null && !"2.0".equals(version)) {
      throw new InvalidToken("version");
    }
    List<String> amr = stringList(claims.getClaim("amr"));
    Object edov = claims.getClaim("xms_edov");
    boolean emailVerified =
        Boolean.TRUE.equals(edov) || "true".equalsIgnoreCase(String.valueOf(edov));
    Integer acct = intClaim(claims.getClaim("acct"));
    String email = claims.getStringClaim("email");
    return new IdToken(tid, oid, amr, email, emailVerified, acct);
  }

  private static UUID uuid(String raw, String what) {
    if (raw == null) {
      throw new InvalidToken(what);
    }
    try {
      UUID id = UUID.fromString(raw);
      if (!id.toString().equalsIgnoreCase(raw)) {
        throw new InvalidToken(what);
      }
      return id;
    } catch (IllegalArgumentException e) {
      throw new InvalidToken(what);
    }
  }

  private static List<String> stringList(Object value) {
    if (value instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    if (value instanceof String s) {
      return List.of(s);
    }
    return List.of();
  }

  private static Integer intClaim(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof String s && s.matches("\\d{1,3}")) {
      return Integer.valueOf(s);
    }
    return null;
  }

  private JWKSource<SecurityContext> keys() {
    int ms = (int) timeout.toMillis();
    try {
      URL url = URI.create(authority + "/common/discovery/v2.0/keys").toURL();
      // Keys are cached (Nimbus default 5 minutes) and refreshed when an unknown key id appears.
      return JWKSourceBuilder.<SecurityContext>create(
              url, new DefaultResourceRetriever(ms, ms, 512 * 1024))
          .retrying(false)
          .build();
    } catch (MalformedURLException e) {
      throw new IllegalStateException("app.entra.authority is not a valid URL");
    }
  }

  /** Visible for tests. HTTPS only, except loopback when the test-only flag is set. */
  static String checkedAuthority(String s, boolean allowInsecureLoopback) {
    String v = s == null ? "" : s.strip();
    while (v.endsWith("/")) {
      v = v.substring(0, v.length() - 1);
    }
    if (v.regionMatches(true, 0, "https://", 0, 8) && v.length() > 8) {
      return v;
    }
    if (allowInsecureLoopback && v.regionMatches(true, 0, "http://", 0, 7)) {
      String host;
      try {
        host = URI.create(v).getHost();
      } catch (IllegalArgumentException e) {
        host = null;
      }
      if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host)) {
        return v;
      }
    }
    throw new IllegalStateException("app.entra.authority must be an https:// URL");
  }
}
