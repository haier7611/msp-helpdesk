package com.ticketsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The walking skeleton: the app starts on real Postgres 16, migrates, and serves its endpoints. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.git-sha=0123abc")
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, ApplicationIntegrationTest.RequestUrlEcho.class})
class ApplicationIntegrationTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void applicationRoleCannotReadFlywayHistory() {
    // WP-02b (C14): the app connects as ticketsys_app; schema history is owner-only.
    assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("ticketsys_app");
    assertThatThrownBy(
            () -> jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class))
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void flywayAppliedTheBaselineMigration() {
    // Flyway's history belongs to the owner role; the application role cannot read it (WP-02b).
    Integer applied =
        TestcontainersConfiguration.ownerJdbc()
            .queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class);
    assertThat(applied).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("rawtypes")
  void versionEndpointReturnsNameVersionAndSha() {
    ResponseEntity<Map> response = http.getForEntity("/api/v1/version", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .containsEntry("name", "Ticket System")
        .containsEntry("version", "0.1.0")
        .containsEntry("gitSha", "0123abc");
  }

  @Test
  @SuppressWarnings("rawtypes")
  void healthIsPublicAndShowsNoDetails() {
    ResponseEntity<Map> response = http.getForEntity("/actuator/health", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsOnlyKeys("status").containsEntry("status", "UP");
  }

  @Test
  void healthBodyIsExactlyStatusUp() {
    // Boot 4 enables liveness/readiness probes by default, which would add a "groups" list;
    // they are switched off so the public body stays exactly this (WP-01b, lead decision).
    ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
  }

  @Test
  void otherActuatorEndpointsAreNotExposed() {
    // Since WP-02b the security chain denies every path it does not list before Spring MVC sees
    // it, so an anonymous caller gets 401 (Caddy answers 404 for /actuator/* in front anyway).
    for (String path : new String[] {"/actuator/env", "/actuator/info", "/actuator/beans"}) {
      ResponseEntity<String> response = http.getForEntity(path, String.class);
      assertThat(response.getStatusCode())
          .as(path)
          .isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody()).as(path).doesNotContain("propertySources", "contexts");
    }
  }

  @Test
  void forwardedHeadersFromTheProxyChainAreHonoured() {
    // server.forward-headers-strategy=native: links and redirects use the public https URL.
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Forwarded-Proto", "https");
    headers.add("X-Forwarded-Host", "support.example.test");
    headers.add("X-Forwarded-Port", "443");
    ResponseEntity<String> response =
        http.exchange("/test/request-url", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("https://support.example.test/test/request-url");
  }

  @Test
  void clientIpIsTheRightMostUntrustedForwardedAddress() {
    // WP-02b: rate caps and the audit log use this address. The test client connects from
    // 127.0.0.1 (a trusted proxy), so Tomcat's RemoteIpValve walks X-Forwarded-For from the right:
    // the left-most entry was typed by the client and must be ignored.
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Forwarded-For", "203.0.113.66, 198.51.100.7, 10.1.2.3");
    ResponseEntity<String> response =
        http.exchange("/test/client-ip", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getBody()).isEqualTo("198.51.100.7");
  }

  /**
   * Test-only endpoints (registered as member classes) showing what Spring sees, with their own
   * permit-all security chain so the main chain's deny-by-default does not apply to them.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class RequestUrlEcho {
    @Bean
    @Order(0)
    SecurityFilterChain testEchoChain(HttpSecurity http) throws Exception {
      return http.securityMatcher("/test/**")
          .authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .build();
    }

    @RestController
    static class EchoController {
      @GetMapping("/test/request-url")
      String requestUrl() {
        return ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
      }

      @GetMapping("/test/client-ip")
      String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
      }
    }
  }
}
