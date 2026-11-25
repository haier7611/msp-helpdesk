package com.ticketsystem.api.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.auth.Browser;
import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.security.SecurityGoLive;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spec 12: a customer gets 403 on every {@code /agent} and {@code /admin} endpoint, an agent on
 * every {@code /admin} endpoint, and supporters on every {@code /portal} endpoint - checked by
 * walking every registered handler mapping, so endpoints added by later items are covered
 * automatically.
 */
@SecurityGoLive({1})
class RoleGateWalkIntegrationTest extends OrganisationTestBase {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private record Endpoint(HttpMethod method, String path) {}

  /** Every concrete method + path under a tree, with path variables filled in. */
  private List<Endpoint> endpoints(String tree) {
    List<Endpoint> out = new ArrayList<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : info.getPatternValues()) {
        if (!pattern.startsWith(tree)) {
          continue;
        }
        String path = pattern.replaceAll("\\{[^}]+}", "1");
        if (methods.isEmpty()) {
          out.add(new Endpoint(HttpMethod.GET, path));
        }
        for (RequestMethod m : methods) {
          out.add(new Endpoint(HttpMethod.valueOf(m.name()), path));
        }
      }
    }
    return out;
  }

  private static void expectForbidden(Browser b, List<Endpoint> endpoints) throws Exception {
    for (Endpoint e : endpoints) {
      String body = e.method() == HttpMethod.GET ? null : "{}";
      assertThat(b.send(e.method(), e.path(), body).getStatus())
          .as(e.method() + " " + e.path())
          .isEqualTo(403);
    }
  }

  @Test
  void theWalkFindsTheEndpointsOfThisItem() {
    assertThat(endpoints("/api/v1/admin/"))
        .contains(
            new Endpoint(HttpMethod.POST, "/api/v1/admin/organisations"),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/organisations/1"),
            new Endpoint(HttpMethod.PUT, "/api/v1/admin/organisations/1/prefix"),
            new Endpoint(HttpMethod.PUT, "/api/v1/admin/users/1/supporter-access"),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/invitations"));
    assertThat(endpoints("/api/v1/agent/"))
        .contains(new Endpoint(HttpMethod.GET, "/api/v1/agent/organisations/1"));
  }

  @Test
  void customerGets403OnEveryAgentAndAdminEndpoint() throws Exception {
    Browser customer = signedIn(activeUser(Role.CUSTOMER));
    expectForbidden(customer, endpoints("/api/v1/agent/"));
    expectForbidden(customer, endpoints("/api/v1/admin/"));
  }

  @Test
  void agentGets403OnEveryAdminEndpoint() throws Exception {
    expectForbidden(signedIn(activeUser(Role.AGENT)), endpoints("/api/v1/admin/"));
  }

  @Test
  void supportersGet403OnEveryPortalEndpoint() throws Exception {
    // WP-06: the portal routes (tickets, request types, attachments, account).
    expectForbidden(signedIn(activeUser(Role.AGENT)), endpoints("/api/v1/portal/"));
    expectForbidden(signedIn(activeUser(Role.ADMIN)), endpoints("/api/v1/portal/"));
  }
}
