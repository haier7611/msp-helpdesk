package com.ticketsystem.api.auth.entra;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ticketsystem.api.mail.TestPorts;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the Microsoft identity platform (v2.0) for WP-10 tests: publishes a JWKS at
 * {@code /common/discovery/v2.0/keys} and redeems codes at {@code /{tenant}/oauth2/v2.0/token},
 * checking the client ID, client secret and PKCE verifier like Entra does. Tests choose the ID
 * token's claims (tid, oid, amr, email, ...) per code, so any tenant, user or MFA claim can be
 * simulated. Binds 127.0.0.1 only, on a port in the project's range. Synthetic keys and ids only.
 */
public final class FakeEntra implements AutoCloseable {

  /** What the token endpoint does. */
  public enum Mode {
    NORMAL,
    /** 503, as during a Microsoft outage. */
    OUTAGE,
    /** 401 invalid_client with an AADSTS code, as for a wrong or expired secret. */
    BAD_SECRET
  }

  /** A token request the fake received (no secret kept). */
  public record TokenRequest(String tenantSegment, String clientId, boolean secretMatched) {}

  private final HttpServer server;
  private final String authority;
  private final RSAKey signingKey;
  private final RSAKey strangerKey;
  private final Map<String, Issued> codes = new ConcurrentHashMap<>();
  private final List<TokenRequest> requests = new CopyOnWriteArrayList<>();
  private volatile String expectedClientId;
  private volatile String expectedSecret;
  private volatile Mode mode = Mode.NORMAL;

  private record Issued(String idToken, String challenge) {}

  public FakeEntra() {
    try {
      signingKey = new RSAKeyGenerator(2048).keyID("fake-entra-key-1").generate();
      strangerKey = new RSAKeyGenerator(2048).keyID("fake-entra-key-1").generate();
      int port = TestPorts.free();
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
      server.createContext("/", this::handle);
      server.start();
      authority = "http://127.0.0.1:" + port;
    } catch (Exception e) {
      throw new IllegalStateException("fake Entra did not start", e);
    }
  }

  public String authority() {
    return authority;
  }

  public void expect(String clientId, String secret) {
    this.expectedClientId = clientId;
    this.expectedSecret = secret;
  }

  public void mode(Mode m) {
    this.mode = m;
  }

  public List<TokenRequest> requests() {
    return List.copyOf(requests);
  }

  public void reset() {
    codes.clear();
    requests.clear();
    mode = Mode.NORMAL;
  }

  /** Standard claims for a user of {@code tid}; tests override or remove entries. */
  public Map<String, Object> claims(
      String clientId, UUID tid, UUID oid, String nonce, Instant now) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("iss", authority + "/" + tid + "/v2.0");
    c.put("aud", clientId);
    c.put("tid", tid.toString());
    c.put("oid", oid.toString());
    c.put("sub", UUID.randomUUID().toString());
    c.put("nonce", nonce);
    c.put("ver", "2.0");
    // The app registration adds the optional acct claim (runbook); 0 = member of the tenant.
    c.put("acct", 0);
    c.put("iat", Date.from(now));
    c.put("nbf", Date.from(now));
    c.put("exp", Date.from(now.plusSeconds(3600)));
    return c;
  }

  /** Signs claims with the published key (or, if {@code stranger}, a key nobody published). */
  public String sign(Map<String, Object> claims, boolean stranger) {
    JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
    claims.forEach(
        (k, v) -> {
          if ("aud".equals(k)) {
            if (v instanceof List<?> list) {
              b.audience(list.stream().map(String::valueOf).toList());
            } else {
              b.audience(String.valueOf(v));
            }
          } else {
            b.claim(k, v);
          }
        });
    try {
      RSAKey key = stranger ? strangerKey : signingKey;
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(key.getKeyID())
                  .type(JOSEObjectType.JWT)
                  .build(),
              b.build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The browser came back with {@code code}; redeeming it returns {@code idToken}. */
  public void issue(String code, String idToken, String codeChallenge) {
    codes.put(code, new Issued(idToken, codeChallenge));
  }

  private void handle(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      if ("GET".equals(ex.getRequestMethod()) && path.equals("/common/discovery/v2.0/keys")) {
        send(ex, 200, new JWKSet(signingKey.toPublicJWK()).toString());
        return;
      }
      if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/oauth2/v2.0/token")) {
        token(ex, path.substring(1, path.indexOf('/', 1)));
        return;
      }
      send(ex, 404, "{\"error\":\"not_found\"}");
    }
  }

  private void token(HttpExchange ex, String segment) throws IOException {
    Map<String, String> form = form(new String(ex.getRequestBody().readAllBytes(), UTF8));
    boolean secretOk = expectedSecret != null && expectedSecret.equals(form.get("client_secret"));
    requests.add(new TokenRequest(segment, form.get("client_id"), secretOk));
    if (mode == Mode.OUTAGE) {
      send(ex, 503, "{\"error\":\"temporarily_unavailable\"}");
      return;
    }
    if (mode == Mode.BAD_SECRET
        || !secretOk
        || expectedClientId == null
        || !expectedClientId.equals(form.get("client_id"))) {
      send(
          ex,
          401,
          "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client"
              + " secret provided.\"}");
      return;
    }
    Issued issued = codes.remove(String.valueOf(form.get("code")));
    String verifier = form.get("code_verifier");
    if (issued == null
        || verifier == null
        || !MicrosoftSignInService.challenge(verifier).equals(issued.challenge())
        || !"authorization_code".equals(form.get("grant_type"))) {
      send(
          ex,
          400,
          "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: The code has"
              + " expired or was already used.\"}");
      return;
    }
    send(ex, 200, "{\"token_type\":\"Bearer\",\"id_token\":\"" + issued.idToken() + "\"}");
  }

  private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

  private static Map<String, String> form(String body) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        out.put(
            URLDecoder.decode(pair.substring(0, eq), UTF8),
            URLDecoder.decode(pair.substring(eq + 1), UTF8));
      }
    }
    return out;
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(UTF8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
