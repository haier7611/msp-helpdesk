package com.ticketsystem.api.ticket;

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
 * Every WP-04 and WP-05 endpoint (agent tickets, attachments, request types, contacts, saved
 * replies, supporters and the portal) is documented in {@code openapi/tickets.yaml} with its
 * method, and the document lists nothing that does not exist.
 */
class OpenApiCoverageIntegrationTest extends OrganisationTestBase {

  private static final String[] PREFIXES = {
    "/api/v1/agent/tickets",
    "/api/v1/agent/attachments",
    "/api/v1/agent/request-types",
    "/api/v1/agent/contacts",
    "/api/v1/agent/saved-replies",
    "/api/v1/agent/supporters",
    "/api/v1/portal"
  };

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  @SuppressWarnings("unchecked")
  void everyTicketEndpointIsDocumentedAndNothingElse() throws Exception {
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
    try (InputStream in = getClass().getResourceAsStream("/openapi/tickets.yaml")) {
      doc = new Yaml().load(in);
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).hasSizeGreaterThanOrEqualTo(20);
    assertThat(documented).isEqualTo(registered);
  }
}
