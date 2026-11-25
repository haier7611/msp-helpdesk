package com.ticketsystem.api.auth;

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
 * WP-15: every operation in {@code openapi/two-step.yaml} exists, and every exemption endpoint is
 * documented there.
 */
class TwoStepOpenApiCoverageIntegrationTest extends OrganisationTestBase {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  @SuppressWarnings("unchecked")
  void theDocumentMatchesTheRegisteredEndpoints() throws Exception {
    Set<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String p : info.getPatternValues()) {
        for (RequestMethod m : info.getMethodsCondition().getMethods()) {
          registered.add(m.name().toLowerCase(Locale.ROOT) + " " + p);
        }
      }
    }
    Map<String, Object> doc;
    try (InputStream in = getClass().getResourceAsStream("/openapi/two-step.yaml")) {
      doc = new Yaml().load(in);
    }
    Set<String> documented = new TreeSet<>();
    ((Map<String, Map<String, Object>>) doc.get("paths"))
        .forEach(
            (path, ops) ->
                ops.keySet().stream()
                    .filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                    .forEach(k -> documented.add(k + " " + path)));
    assertThat(registered).containsAll(documented);
    Set<String> exemption = new TreeSet<>();
    registered.stream().filter(e -> e.contains("/mfa-exemption")).forEach(exemption::add);
    assertThat(exemption).hasSize(2);
    assertThat(documented).containsAll(exemption);
  }
}
