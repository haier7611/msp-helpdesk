package com.ticketsystem.api.integration.config;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ApiKeyAccessReader;
import com.ticketsystem.api.access.ApiKeyAccessReader.KeyAccess;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import com.ticketsystem.api.auth.throttle.ThrottledException;
import com.ticketsystem.api.integration.ApiKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates integrations API calls in the integrations security chain only. Order of checks:
 *
 * <ol>
 *   <li>A declared body over {@link #MAX_BODY_BYTES} (256 KB): 413, before any lookup.
 *   <li>{@code Authorization: Bearer tsk_<id>_<secret>}: the row is read by its visible id and the
 *       SHA-256 compared in constant time; unknown, malformed, expired or revoked keys all get the
 *       same 401 body. Too many bad keys from one address: 429.
 *   <li>60 requests a minute per key: 429 with {@code Retry-After}.
 *   <li>The body is read with the same 256 KB cap (chunked uploads have no length): 413.
 * </ol>
 *
 * A session cookie never authenticates here: this chain has no session or security context
 * repository, and the request's {@link AccessScope} is only ever the key's. The key is never
 * logged.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  public static final int MAX_BODY_BYTES = 256 * 1024;
  public static final String AUTHORITY = "API_KEY";

  static final String INVALID_KEY =
      "{\"error\":\"invalid_api_key\",\"message\":\"The API key is missing or not valid.\"}";

  /** Hash compared when no row matches, so unknown ids take as long as wrong secrets. */
  private static final byte[] DUMMY_HASH = ApiKeys.hash("tsk_00000000_dummy");

  private final ApiKeyAccessReader reader;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ApiKeyAuthenticationFilter(
      ApiKeyAccessReader reader, RateLimiter rateLimiter, Clock clock) {
    this.reader = reader;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      tooLarge(response);
      return;
    }
    Instant now = clock.instant();
    Optional<KeyAccess> key = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION), now);
    if (key.isEmpty()) {
      try {
        rateLimiter.consume(RateLimiter.Scope.API_KEY_INVALID_IP, request.getRemoteAddr());
      } catch (ThrottledException e) {
        throttled(response, e);
        return;
      }
      write(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_KEY);
      return;
    }
    try {
      rateLimiter.consume(RateLimiter.Scope.API_KEY, Long.toString(key.get().id()));
    } catch (ThrottledException e) {
      throttled(response, e);
      return;
    }
    byte[] body = readCapped(request.getInputStream());
    if (body == null) {
      tooLarge(response);
      return;
    }
    reader.markUsed(key.get().id(), now);
    request.setAttribute(AccessScope.REQUEST_ATTRIBUTE, key.get().toScope());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "api-key:" + key.get().id(), null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(new CachedBodyRequest(request, body), response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Optional<KeyAccess> authenticate(String header, Instant now) {
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String presented = header.substring(7).strip();
    Optional<String> keyId = ApiKeys.keyIdOf(presented);
    if (keyId.isEmpty()) {
      return Optional.empty();
    }
    Optional<KeyAccess> row = reader.read(keyId.get());
    boolean hashOk = ApiKeys.matches(row.map(KeyAccess::secretHash).orElse(DUMMY_HASH), presented);
    if (row.isEmpty() || !hashOk || !row.get().usableAt(now)) {
      return Optional.empty();
    }
    return row;
  }

  /** The body, or null when it is longer than the cap. */
  static byte[] readCapped(InputStream in) throws IOException {
    byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
    return bytes.length > MAX_BODY_BYTES ? null : bytes;
  }

  private static void tooLarge(HttpServletResponse response) throws IOException {
    write(
        response,
        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
        "{\"error\":\"payload_too_large\",\"message\":\"The body can be at most 256 KB.\"}");
  }

  private static void throttled(HttpServletResponse response, ThrottledException e)
      throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
    write(
        response,
        429,
        "{\"error\":\"too_many_requests\",\"message\":\"Too many requests. Please wait "
            + e.retryAfterSeconds()
            + " seconds and try again.\"}");
  }

  private static void write(HttpServletResponse response, int status, String json)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json);
  }

  /** Replays the already-read (capped) body to the controller. */
  static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
