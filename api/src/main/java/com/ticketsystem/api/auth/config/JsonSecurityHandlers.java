package com.ticketsystem.api.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * JSON answers for the security layer: 401 when not signed in, 403 when signed in without the right
 * role or level, 403 {@code csrf} when the CSRF header is missing or wrong. Never a redirect, never
 * details.
 */
@Component
public class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    write(response, 401, "unauthenticated", "Please sign in.");
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    if (accessDeniedException instanceof CsrfException) {
      write(
          response,
          403,
          "csrf",
          "Your security token is missing or out of date. Reload the page and try again.");
    } else {
      write(response, 403, "forbidden", "You do not have access to this.");
    }
  }

  private static void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
