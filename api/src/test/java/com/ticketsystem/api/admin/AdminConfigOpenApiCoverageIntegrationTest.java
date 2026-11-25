package com.ticketsystem.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.access.OrganisationTestBase;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Every admin configuration endpoint (WP-07's email backend and WP-11b) is documented in {@code
 * openapi/admin-config.yaml} with its method, and the document lists nothing that does not exist.
 * The request-type default assignee belongs to sla-notifications.yaml (WP-09).
 */
class AdminConfigOpenApiCoverageIntegrationTest extends OrganisationTestBase {

  private static final String[] PREFIXES = {
    "/api/v1/admin/request-types",
    "/api/v1/admin/email",
    "/api/v1/admin/signature-template",
    "/api/v1/admin/branding",
    "/api/v1/admin/audit",
    "/api/v1/admin/saved-replies",
    "/api/v1/admin/tickets",
    "/api/v1/admin/organisations/{id}/client-emails"
  };

  private static final String WP09 = "/api/v1/admin/request-types/{requestTypeId}/default-assignee";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  @SuppressWarnings("unchecked")
  void everyAdminConfigEndpointIsDocumentedAndNothingElse() throws Exception {
    Set<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        if (p.equals(WP09)) {
          continue;
        }
        for (String prefix : PREFIXES) {
          if (p.startsWith(prefix)) {
            for (RequestMethod m : info.getMethodsCondition().getMethods()) {
              registered.add(m.name().toLowerCase(Locale.ROOT) + " " + p);
            }
          }
        }
      }
    }
    Map<String, Object> doc;
    try (InputStream in = getClass().getResourceAsStream("/openapi/admin-config.yaml")) {
      doc = new Yaml().load(in);
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).hasSizeGreaterThanOrEqualTo(30);
    assertThat(documented).isEqualTo(registered);
  }
}
