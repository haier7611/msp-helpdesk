package com.ticketsystem.api.integration.config;

import com.ticketsystem.api.access.ApiKeyAccessReader;
import com.ticketsystem.api.auth.config.JsonSecurityHandlers;
import com.ticketsystem.api.auth.throttle.RateLimiter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

/**
 * The integrations API's own security chain, matched before the session chain: {@code
 * /api/v1/integrations/**} is stateless - no session, no CSRF (no cookie authenticates it), no
 * request cache - and authenticated only by {@link ApiKeyAuthenticationFilter}. The one allowed
 * call is {@code POST /api/v1/integrations/tickets}; everything else in the tree is denied.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationSecurityConfiguration {

  @Bean
  @Order(1)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain integrationSecurity(
      HttpSecurity http,
      ApiKeyAccessReader reader,
      RateLimiter rateLimiter,
      Clock clock,
      JsonSecurityHandlers handlers)
      throws Exception {
    http.securityMatcher("/api/v1/integrations/**")
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .securityContext(sc -> sc.securityContextRepository(new NullSecurityContextRepository()))
        .requestCache(rc -> rc.disable())
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .anonymous(a -> a.disable())
        .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(reader, rateLimiter, clock), AuthorizationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/api/v1/integrations/tickets")
                    .hasAuthority(ApiKeyAuthenticationFilter.AUTHORITY)
                    .anyRequest()
                    .denyAll());
    return http.build();
  }
}
