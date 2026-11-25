package com.ticketsystem.api.mail.intake.imap;

import com.ticketsystem.api.mail.intake.imap.MailboxAccessException.Category;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth2 client-credentials token requests for IMAP XOAUTH2 (Microsoft 365 {@code
 * IMAP.AccessAsApp}). Tokens are cached in memory until a minute before they expire and never
 * logged or stored; the response body is never echoed into an error.
 */
@Component
public class OAuth2TokenClient {

  static final Duration TIMEOUT = Duration.ofSeconds(15);
  static final Duration EARLY_REFRESH = Duration.ofSeconds(60);

  private record Cached(String token, Instant expiresAt) {}

  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final JsonMapper json = new JsonMapper();
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private final Clock clock;

  public OAuth2TokenClient(Clock clock) {
    this.clock = clock;
  }

  /** An access token for the client; cached per token URL, client id, scope and secret. */
  public String token(String tokenUrl, String clientId, String clientSecret, String scope)
      throws MailboxAccessException {
    String key = cacheKey(tokenUrl, clientId, clientSecret, scope);
    Cached c = cache.get(key);
    Instant now = clock.instant();
    if (c != null && now.isBefore(c.expiresAt())) {
      return c.token();
    }
    String form =
        "grant_type=client_credentials&client_id="
            + enc(clientId)
            + "&client_secret="
            + enc(clientSecret)
            + "&scope="
            + enc(scope);
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(tokenUrl))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new MailboxAccessException(Category.TOKEN_REQUEST_FAILED);
      }
      JsonNode body = json.readTree(response.body());
      JsonNode token = body.get("access_token");
      if (token == null || !token.isString() || token.asString().isBlank()) {
        throw new MailboxAccessException(Category.TOKEN_REQUEST_FAILED);
      }
      long seconds = body.has("expires_in") ? body.get("expires_in").asLong(300) : 300;
      Instant expires = now.plusSeconds(Math.max(0, seconds)).minus(EARLY_REFRESH);
      cache.put(key, new Cached(token.asString(), expires));
      return token.asString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MailboxAccessException(Category.TOKEN_REQUEST_FAILED);
    } catch (MailboxAccessException e) {
      throw e;
    } catch (Exception e) {
      throw new MailboxAccessException(Category.TOKEN_REQUEST_FAILED);
    }
  }

  /** Forgets cached tokens for this client (after the IMAP server refused one). */
  public void invalidate(String tokenUrl, String clientId, String clientSecret, String scope) {
    cache.remove(cacheKey(tokenUrl, clientId, clientSecret, scope));
  }

  private static String cacheKey(String url, String clientId, String secret, String scope) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      for (String part : new String[] {url, clientId, secret, scope}) {
        d.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
        d.update((byte) 0);
      }
      return HexFormat.of().formatHex(d.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }
}
