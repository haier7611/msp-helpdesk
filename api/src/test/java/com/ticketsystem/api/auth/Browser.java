package com.ticketsystem.api.auth;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A minimal browser over MockMvc: keeps cookies from Set-Cookie headers (honouring Max-Age=0),
 * sends them back, and echoes the CSRF cookie in the X-XSRF-TOKEN header on state-changing requests
 * - the same thing the web app does. Each instance is one client with its own IP address.
 */
public class Browser {

  public static final String SESSION_COOKIE = "__Host-TS_SESSION";
  public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

  private final MockMvc mvc;
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private final List<String> setCookieHeaders = new ArrayList<>();
  private String ip;
  private boolean sendCsrf = true;

  public Browser(MockMvc mvc, String ip) {
    this.mvc = mvc;
    this.ip = ip;
  }

  public Browser ip(String value) {
    this.ip = value;
    return this;
  }

  public Browser withoutCsrfHeader() {
    this.sendCsrf = false;
    return this;
  }

  public String cookie(String name) {
    return cookies.get(name);
  }

  public void setCookie(String name, String value) {
    cookies.put(name, value);
  }

  /** Set-Cookie headers of the last response. */
  public List<String> lastSetCookies() {
    return List.copyOf(setCookieHeaders);
  }

  public MockHttpServletResponse get(String path) throws Exception {
    return perform(MockMvcRequestBuilders.get(path), HttpMethod.GET);
  }

  public MockHttpServletResponse post(String path, String json) throws Exception {
    if (cookie(CSRF_COOKIE) == null) {
      get("/api/v1/auth/csrf");
    }
    return perform(
        MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json),
        HttpMethod.POST);
  }

  public MockHttpServletResponse put(String path, String json) throws Exception {
    return send(HttpMethod.PUT, path, json);
  }

  public MockHttpServletResponse delete(String path) throws Exception {
    return send(HttpMethod.DELETE, path, null);
  }

  /** Any method with an optional JSON body; state-changing methods carry the CSRF header. */
  public MockHttpServletResponse send(HttpMethod method, String path, String json)
      throws Exception {
    if (method != HttpMethod.GET && cookie(CSRF_COOKIE) == null) {
      get("/api/v1/auth/csrf");
    }
    MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path);
    if (json != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return perform(builder, method);
  }

  public MockHttpServletResponse perform(
      AbstractMockHttpServletRequestBuilder<?> builder, HttpMethod method) throws Exception {
    builder.with(
        r -> {
          r.setRemoteAddr(ip);
          r.setSecure(true);
          return r;
        });
    if (!cookies.isEmpty()) {
      builder.cookie(
          cookies.entrySet().stream()
              .map(e -> new Cookie(e.getKey(), e.getValue()))
              .toArray(Cookie[]::new));
    }
    if (method != HttpMethod.GET && sendCsrf && cookie(CSRF_COOKIE) != null) {
      builder.header("X-XSRF-TOKEN", cookie(CSRF_COOKIE));
    }
    MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
    setCookieHeaders.clear();
    for (String header : response.getHeaders("Set-Cookie")) {
      setCookieHeaders.add(header);
      String pair = header.split(";", 2)[0];
      int eq = pair.indexOf('=');
      String name = pair.substring(0, eq).trim();
      String value = pair.substring(eq + 1).trim();
      String lower = header.toLowerCase(Locale.ROOT);
      if (lower.contains("max-age=0") || value.isEmpty()) {
        cookies.remove(name);
      } else {
        cookies.put(name, value);
      }
    }
    return response;
  }

  /** JSON string literal for request bodies. */
  public static String q(String value) {
    if (value == null) {
      return "null";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
