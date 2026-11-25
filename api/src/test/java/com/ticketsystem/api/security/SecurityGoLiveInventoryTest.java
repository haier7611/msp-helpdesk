package com.ticketsystem.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The go-live security suite (spec section 12, WP-12) is complete: each of security tests 1-5 has
 * at least one {@link SecurityGoLive} class, and the named core classes stay tagged (removing a tag
 * by accident fails here). Run the suite alone with {@code scripts/ci/api.sh securityGoLive}.
 */
@SecurityGoLive({1, 2, 3, 4, 5})
class SecurityGoLiveInventoryTest {

  /** The classes that carry each test; extend, never shrink, without an audit note. */
  static final Map<Integer, Set<String>> REQUIRED =
      Map.of(
          1,
          Set.of(
              "ticket.TicketIsolationIntegrationTest",
              "organisation.OrganisationIsolationIntegrationTest",
              "access.RoleGateWalkIntegrationTest",
              "architecture.ArchitectureTest"),
          2,
          Set.of("auth.entra.MicrosoftSignInIntegrationTest"),
          3,
          Set.of(
              "ticket.richtext.RichTextSanitiserTest",
              "mail.inbound.HtmlToSafeHtmlTest",
              "ticket.AttachmentIntegrationTest"),
          4,
          Set.of("auth.SessionSecurityIntegrationTest", "auth.LinkFlowsIntegrationTest"),
          5,
          Set.of("mail.intake.InboundRouterTest", "mail.intake.EmailInOutIntegrationTest"));

  @Test
  void everySecurityTestHasTaggedClasses() {
    Map<Integer, Set<String>> found = new TreeMap<>();
    for (JavaClass c :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("com.ticketsystem.api")) {
      if (c.isAnnotatedWith(SecurityGoLive.class)) {
        SecurityGoLive tag = c.reflect().getAnnotation(SecurityGoLive.class);
        for (int n : tag.value()) {
          assertThat(n).as(c.getName() + " names a test 1-5").isBetween(1, 5);
          found
              .computeIfAbsent(n, k -> new TreeSet<>())
              .add(c.getName().replace("com.ticketsystem.api.", ""));
        }
      }
    }
    REQUIRED.forEach(
        (n, classes) ->
            assertThat(found.getOrDefault(n, Set.of()))
                .as("security test " + n + " suite")
                .containsAll(classes));
  }
}
