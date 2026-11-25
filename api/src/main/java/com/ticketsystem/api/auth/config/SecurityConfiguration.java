package com.ticketsystem.api.auth.config;

import com.ticketsystem.api.access.AccessScopeFilter;
import com.ticketsystem.api.access.PrincipalAccessReader;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.AuthLevel;
import com.ticketsystem.api.auth.AuthProperties;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserRepository;
import com.ticketsystem.api.auth.mfa.MfaExemptionService;
import com.ticketsystem.api.auth.session.SecurityVersionFilter;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Spring Security for the JSON API.
 *
 * <ul>
 *   <li>Server-side sessions in Postgres (Spring Session JDBC); cookie {@code __Host-TS_SESSION},
 *       HttpOnly, Secure, SameSite=Lax. No JWT, no remember-me, no form login, no HTTP Basic.
 *   <li>CSRF on every state-changing request with the SPA pattern: a readable cookie ({@code
 *       __Host-XSRF-TOKEN}, or {@code XSRF-TOKEN} on plain-http dev) echoed in the {@code
 *       X-XSRF-TOKEN} header. The {@code __Host-} prefix stops a sibling subdomain planting the
 *       cookie (cookie tossing) to defeat the double submit.
 *   <li>Three role trees: {@code /api/v1/portal} customers only, {@code /api/v1/agent} agents and
 *       admins, {@code /api/v1/admin} admins only - each also needs a fully signed-in session
 *       ({@link AuthLevel#FULL}). Supporters below FULL (MFA owed) reach only the MFA steps, {@code
 *       /me} and sign-out. The rest of {@code /api/v1/auth} is the public sign-in tree, except the
 *       signed-in settings endpoints (FULL). Everything else is denied.
 *   <li>Session freshness (C4): {@link SecurityVersionFilter} ends a session, before the security
 *       context is loaded, when the user's security_version, status or role changed or the MFA step
 *       was not finished within 10 minutes.
 *   <li>Organisation scoping (C13): {@link AccessScopeFilter} builds the request's AccessScope from
 *       the user's current row and ends the session if the user's role, status or security_version
 *       changed since sign-in (behind SecurityVersionFilter, so in practice it only catches a
 *       change made between the two filters of one request).
 *   <li>Argon2id (C15) through a DelegatingPasswordEncoder, so parameters can be raised later and
 *       old hashes upgrade at sign-in.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
public class SecurityConfiguration {

  /** Cookie names: {@code __Host-} prefix whenever cookies are Secure. */
  public static String sessionCookieName(boolean secure) {
    return secure ? "__Host-TS_SESSION" : "TS_SESSION";
  }

  public static String csrfCookieName(boolean secure) {
    return secure ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN";
  }

  public static final String CSRF_HEADER = "X-XSRF-TOKEN";

  /**
   * Servlet only: the break-glass command ({@code BreakGlass}) starts the application without a web
   * server, where there is no {@code HttpSecurity}. The other beans here (password encoder, cookie
   * and CSRF settings, properties) are plain objects and stay.
   */
  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      CsrfTokenRepository csrfTokenRepository,
      SecurityContextRepository securityContextRepository,
      JsonSecurityHandlers handlers,
      AuthProperties props,
      Clock clock,
      AuditService audit,
      PrincipalAccessReader accessReader,
      UserRepository users,
      MfaExemptionService exemptions)
      throws Exception {
    AbsoluteSessionTimeoutFilter absoluteTimeout =
        new AbsoluteSessionTimeoutFilter(clock, props.sessionAbsoluteTimeout(), audit);
    // security_version, status, role and the 10-minute MFA step limit, checked on every
    // signed-in request before the security context is loaded.
    SecurityVersionFilter securityVersion =
        new SecurityVersionFilter(users, exemptions, audit, clock);
    AuthorizationManager<RequestAuthorizationContext> full =
        AuthorityAuthorizationManager.hasAuthority(AuthLevel.FULL.authority());
    AuthorizationManager<RequestAuthorizationContext> mfaRequired =
        AuthorityAuthorizationManager.hasAuthority(AuthLevel.MFA_REQUIRED.authority());
    AuthorizationManager<RequestAuthorizationContext> enrolmentOrFull =
        AuthorityAuthorizationManager.hasAnyAuthority(
            AuthLevel.MFA_ENROLMENT_REQUIRED.authority(), AuthLevel.FULL.authority());
    http.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
        .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
        .requestCache(rc -> rc.disable())
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .anonymous(a -> {})
        .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
        .addFilterBefore(absoluteTimeout, SecurityContextHolderFilter.class)
        .addFilterBefore(securityVersion, SecurityContextHolderFilter.class)
        // Builds the request's AccessScope and ends sessions whose user changed.
        .addFilterBefore(
            new AccessScopeFilter(accessReader, audit), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // Error pages for requests that already passed (e.g. 404 for an admin).
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/version")
                    .permitAll()
                    // The support name for the top bar, sign-in card and tab title.
                    .requestMatchers(HttpMethod.GET, "/api/v1/display-name")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/me")
                    .authenticated()
                    // Below FULL a session reaches only these MFA steps (plus /me and
                    // the public sign-in tree below, sign-out included).
                    .requestMatchers("/api/v1/auth/mfa/verify", "/api/v1/auth/mfa/recovery")
                    .access(mfaRequired)
                    .requestMatchers(
                        "/api/v1/auth/mfa/enrolment", "/api/v1/auth/mfa/enrolment/confirm")
                    .access(enrolmentOrFull)
                    .requestMatchers(
                        "/api/v1/auth/password/change",
                        "/api/v1/auth/mfa/**",
                        "/api/v1/auth/step-up",
                        "/api/v1/auth/sessions/**",
                        "/api/v1/auth/trusted-devices/**",
                        // The signed-in user's Microsoft link (status, link, unlink).
                        "/api/v1/auth/microsoft/link")
                    .access(full)
                    .requestMatchers("/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers("/api/v1/portal/**")
                    .access(
                        AuthorizationManagers.allOf(
                            AuthorityAuthorizationManager.hasAuthority(Role.CUSTOMER.authority()),
                            full))
                    .requestMatchers("/api/v1/agent/**")
                    .access(
                        AuthorizationManagers.allOf(
                            AuthorityAuthorizationManager.hasAnyAuthority(
                                Role.AGENT.authority(), Role.ADMIN.authority()),
                            full))
                    .requestMatchers("/api/v1/admin/**")
                    .access(
                        AuthorizationManagers.allOf(
                            AuthorityAuthorizationManager.hasAuthority(Role.ADMIN.authority()),
                            full))
                    .anyRequest()
                    .denyAll());
    return http.build();
  }

  @Bean
  CsrfTokenRepository csrfTokenRepository(AuthProperties props) {
    CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repo.setCookieName(csrfCookieName(props.secureCookies()));
    repo.setHeaderName(CSRF_HEADER);
    repo.setCookiePath("/");
    repo.setCookieCustomizer(c -> c.secure(props.secureCookies()).sameSite("Lax"));
    return repo;
  }

  /**
   * No Spring-managed password users: sign-in goes through SignInService only. Declaring this bean
   * also stops Boot creating its default in-memory "user" with a generated password (and logging
   * that password at start-up).
   */
  @Bean
  UserDetailsService noUserDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("not used");
    };
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  /** Applied at every sign-in: new session id (fixation) and a fresh CSRF token. */
  @Bean
  SessionAuthenticationStrategy signInSessionStrategy(CsrfTokenRepository csrfTokenRepository) {
    return new CompositeSessionAuthenticationStrategy(
        List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(csrfTokenRepository)));
  }

  /** Spring Session's cookie (replaces Boot's default serializer). */
  @Bean
  CookieSerializer cookieSerializer(AuthProperties props) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(sessionCookieName(props.secureCookies()));
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(props.secureCookies());
    serializer.setSameSite("Lax");
    return serializer;
  }

  /**
   * Argon2id with OWASP's minimum parameters (19 MiB memory, 2 iterations, 1 lane), stored as
   * {@code {argon2}$argon2id$...}. Raise the parameters here; old hashes upgrade at sign-in.
   */
  @Bean
  PasswordEncoder passwordEncoder() {
    Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
  }
}
