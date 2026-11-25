package com.ticketsystem.api.sla.ticket;

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
 * Every WP-09 endpoint is documented in {@code openapi/sla-notifications.yaml} with its method, and
 * the document lists nothing that does not exist.
 */
class SlaOpenApiCoverageIntegrationTest extends OrganisationTestBase {

  private static final String[] PREFIXES = {
    "/api/v1/agent/notifications",
    "/api/v1/agent/sla-report",
    "/api/v1/admin/organisations/{id}/sla",
    // Only WP-09's endpoint under request-types; the rest are WP-11b's (admin-config.yaml).
    "/api/v1/admin/request-types/{requestTypeId}/default-assignee"
  };

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  @SuppressWarnings("unchecked")
  void everyWp09EndpointIsDocumentedAndNothingElse() throws Exception {
    Set<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
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
    try (InputStream in = getClass().getResourceAsStream("/openapi/sla-notifications.yaml")) {
      doc = new Yaml().load(in);
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).hasSizeGreaterThanOrEqualTo(14);
    assertThat(documented).isEqualTo(registered);
  }
}
