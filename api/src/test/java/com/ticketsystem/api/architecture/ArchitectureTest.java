package com.ticketsystem.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.reference.TicketReference;
import com.ticketsystem.api.security.SecurityGoLive;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Build-time rules for organisation scoping (spec 9 and 12: client data is scoped by organisation,
 * ticket references come only from the reference module). Runs in {@code ./gradlew test}, so a
 * violation fails CI.
 *
 * <ol>
 *   <li>Every public method of a class in a {@code ..clientdata..} package takes an {@link
 *       AccessScope}.
 *   <li>Those classes inherit no unscoped methods: no Spring Data repository base types, no
 *       superclass other than Object.
 *   <li>Web controllers never use {@code ..clientdata..} classes directly (they go through a
 *       service).
 *   <li>Only the reference module constructs {@link TicketReference}.
 *   <li>Every class named {@code *Repository} lives in {@code ..clientdata..}, except the named
 *       non-client-data allow-list ({@link #NON_CLIENT_DATA_REPOSITORIES}).
 *   <li>Only {@code ..clientdata..} classes and a named allow-list ({@link #JDBC_ALLOWED}) may use
 *       {@code org.springframework.jdbc..} ({@code JdbcClient}, {@code JdbcTemplate}) or {@code
 *       javax.sql.DataSource}, so client data cannot be queried from anywhere else.
 *   <li>Only the access module and job/poller packages may create a SYSTEM scope ({@code
 *       AccessScope.system}).
 *   <li>(internal notes never leave agent-only code) Portal and public read-model packages ({@code
 *       ..portal..}, {@code ..publicview..}) never depend on internal-note, system-entry or history
 *       storage, or on the agent read model ({@code ticket.agent..}).
 *   <li>(WP-04) Internal-note and system-entry storage and the agent read model are used only by
 *       {@code ticket.agent..} and {@code ticket.clientdata..}.
 * </ol>
 *
 * Records and enums are exempt from rules 1 and 2 (value types, no queries); other nested classes
 * are not. Deliberately bad classes in the test-only package {@code archfixture} prove rules 1, 6,
 * 7 and 8 fail: an unscoped {@code clientdata} repository, a rogue {@code ticket.TicketRepository}
 * using JdbcClient with an unscoped {@code findAll}, and a controller creating a SYSTEM scope.
 */
@SecurityGoLive({1})
class ArchitectureTest {

  static final String CLIENT_DATA = "..clientdata..";

  /**
   * Repositories that hold no client-owned data (users and auth tokens are guarded by the auth
   * module; the outbox is system mail). Add to this list only with an audit note.
   */
  static final Set<String> NON_CLIENT_DATA_REPOSITORIES =
      Set.of(
          "com.ticketsystem.api.auth.UserRepository",
          "com.ticketsystem.api.auth.AuthTokenRepository",
          // WP-02c (audit note, PR #9 rebase): user_mfa and mfa_recovery_code - a supporter's own
          // encrypted TOTP secret and hashed recovery codes, keyed by user id; no organisation
          // data.
          "com.ticketsystem.api.auth.mfa.MfaRepository",
          // WP-10 (audit note): entra_settings (one MSP-wide row, admin only, secret as
          // ciphertext) and external_identity (a user's own (tid, oid) link, keyed by user id or
          // by the validated (tid, oid)); no organisation data. A customer's organisation tenant
          // is read through OrganisationService with that customer's own scope.
          "com.ticketsystem.api.auth.entra.EntraSettingsRepository",
          "com.ticketsystem.api.auth.entra.ExternalIdentityRepository",
          // WP-15 (audit note): mfa_exemption - one engineer's own two-step exemption (reason,
          // end time, granting admin id), keyed by user id; no organisation data. Admin-only
          // writes go through MfaExemptionService behind the admin route and step-up.
          "com.ticketsystem.api.auth.mfa.MfaExemptionRepository",
          "com.ticketsystem.api.mail.OutboxRepository");

  /**
   * Classes outside {@code ..clientdata..} that may use JDBC directly: auth tables, the outbox, the
   * audit log, the per-request scope reader and its SQL helper. Nested classes of these count as
   * the class itself. Add to this list only with an audit note.
   */
  static final Set<String> JDBC_ALLOWED =
      Set.of(
          "com.ticketsystem.api.access.PrincipalAccessReader",
          // WP-08 (audit note): the API key bootstrap read - one api_key row by the presented
          // key id (id, organisation, hash, expiry, revocation) and its last_used_at; no client
          // content. Same role as PrincipalAccessReader for sessions.
          "com.ticketsystem.api.access.ApiKeyAccessReader",
          "com.ticketsystem.api.access.ScopeSql",
          "com.ticketsystem.api.auth.UserRepository",
          "com.ticketsystem.api.auth.AuthTokenRepository",
          "com.ticketsystem.api.auth.SetupService",
          // WP-02c (audit note, PR #9 rebase): auth tables only, all keyed by user id, no
          // organisation data - user_mfa / mfa_recovery_code (MfaRepository), user_device
          // (DeviceService), app_user.security_version and the user's own spring_session rows
          // (SecurityVersionService).
          "com.ticketsystem.api.auth.mfa.MfaRepository",
          "com.ticketsystem.api.auth.device.DeviceService",
          "com.ticketsystem.api.auth.session.SecurityVersionService",
          // WP-10 (audit note): entra_settings and external_identity only (see above).
          "com.ticketsystem.api.auth.entra.EntraSettingsRepository",
          "com.ticketsystem.api.auth.entra.ExternalIdentityRepository",
          // WP-15 (audit note): mfa_exemption only (see above).
          "com.ticketsystem.api.auth.mfa.MfaExemptionRepository",
          "com.ticketsystem.api.mail.OutboxRepository",
          "com.ticketsystem.api.audit.AuditService",
          // WP-11b (audit note): reads signature_template.support_name only - the MSP-wide
          // support name (Admin - Branding) used as the From name; no organisation data.
          "com.ticketsystem.api.mail.SupportName");

  /** Packages that may create a SYSTEM scope (scheduled jobs, the mail poller). */
  static final String[] SYSTEM_SCOPE_PACKAGES = {
    "com.ticketsystem.api.access..", "..job..", "..jobs..", "..poller.."
  };

  static boolean inAllowList(JavaClass c, Set<String> names) {
    String name = c.getName();
    int nested = name.indexOf('$');
    return names.contains(nested < 0 ? name : name.substring(0, nested));
  }

  static final DescribedPredicate<JavaClass> VALUE_TYPES =
      new DescribedPredicate<>("records or enums") {
        @Override
        public boolean test(JavaClass c) {
          return c.isRecord() || c.isEnum();
        }
      };

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.ticketsystem.api");

  private static final JavaClasses BAD_FIXTURE =
      new ClassFileImporter().importPackages("com.ticketsystem.api.archfixture");

  static final ArchCondition<JavaMethod> TAKE_AN_ACCESS_SCOPE =
      new ArchCondition<>("take an AccessScope parameter") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
          boolean scoped =
              method.getRawParameterTypes().stream()
                  .anyMatch(t -> t.isEquivalentTo(AccessScope.class));
          if (!scoped) {
            events.add(
                SimpleConditionEvent.violated(
                    method, method.getFullName() + " has no AccessScope parameter"));
          }
        }
      };

  /** Rule 1. */
  static final ArchRule CLIENT_DATA_METHODS_TAKE_A_SCOPE =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage(CLIENT_DATA)
          .and()
          .arePublic()
          .and(
              new DescribedPredicate<JavaMethod>("are not compiler-generated") {
                @Override
                public boolean test(JavaMethod m) {
                  return !m.getModifiers().contains(JavaModifier.SYNTHETIC)
                      && !m.getModifiers().contains(JavaModifier.BRIDGE);
                }
              })
          .and()
          .areDeclaredInClassesThat(DescribedPredicate.not(VALUE_TYPES))
          .should(TAKE_AN_ACCESS_SCOPE)
          .because("every query on client-owned data is scoped by organisation (C13, rule 7)");

  @Test
  void everyClientDataRepositoryMethodTakesAnAccessScope() {
    CLIENT_DATA_METHODS_TAKE_A_SCOPE.check(PRODUCTION);
  }

  @Test
  void theRuleFailsOnARepositoryMethodWithoutAScope() {
    assertThat(BAD_FIXTURE).isNotEmpty();
    assertThatThrownBy(() -> CLIENT_DATA_METHODS_TAKE_A_SCOPE.check(BAD_FIXTURE))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("findAllTickets")
        .hasMessageContaining("has no AccessScope parameter")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("findTickets(com"));
  }

  @Test
  void theRuleIsNotVacuous() {
    // Guards against a package rename silently emptying the rule.
    assertThat(
            PRODUCTION.stream()
                .filter(c -> c.getPackageName().contains(".clientdata"))
                .filter(c -> c.getSimpleName().endsWith("Repository"))
                .count())
        .isGreaterThanOrEqualTo(5);
  }

  @Test
  void clientDataRepositoriesInheritNoUnscopedMethods() {
    classes()
        .that()
        .resideInAPackage(CLIENT_DATA)
        .and(DescribedPredicate.not(VALUE_TYPES))
        .and()
        .areNotInterfaces()
        .should()
        .notBeAssignableTo("org.springframework.data.repository.Repository")
        .andShould(
            new ArchCondition<>("extend only Object") {
              @Override
              public void check(
                  com.tngtech.archunit.core.domain.JavaClass c, ConditionEvents events) {
                boolean plain =
                    c.getRawSuperclass().map(s -> s.isEquivalentTo(Object.class)).orElse(true);
                if (!plain || !c.getRawInterfaces().isEmpty()) {
                  events.add(
                      SimpleConditionEvent.violated(
                          c, c.getName() + " inherits methods that may not be scoped"));
                }
              }
            })
        .check(PRODUCTION);
  }

  @Test
  void controllersDoNotUseClientDataRepositoriesDirectly() {
    noClasses()
        .that()
        .resideInAPackage("..web..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(CLIENT_DATA)
        .because("controllers go through a service that applies the AccessScope")
        .check(PRODUCTION);
  }

  @Test
  void onlyTheReferenceModuleBuildsTicketReferences() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.ticketsystem.api.reference..")
        .should()
        .callConstructorWhere(
            new DescribedPredicate<JavaConstructorCall>("target is TicketReference") {
              @Override
              public boolean test(JavaConstructorCall call) {
                return call.getTargetOwner().isEquivalentTo(TicketReference.class);
              }
            })
        .because("references are built and parsed only by the reference module (rule 10)")
        .check(PRODUCTION);
  }

  /** Rule 6. */
  static final ArchRule REPOSITORIES_LIVE_IN_CLIENT_DATA =
      classes()
          .that()
          .haveSimpleNameEndingWith("Repository")
          .and(
              new DescribedPredicate<JavaClass>("are not on the non-client-data allow-list") {
                @Override
                public boolean test(JavaClass c) {
                  return !inAllowList(c, NON_CLIENT_DATA_REPOSITORIES);
                }
              })
          .should()
          .resideInAPackage(CLIENT_DATA)
          .because(
              "a repository outside clientdata escapes the AccessScope rules (C13, rule 7);"
                  + " see WP-03 'How to add a client-data repository'");

  /** Rule 7. */
  static final ArchRule ONLY_CLIENT_DATA_USES_JDBC =
      noClasses()
          .that()
          .resideOutsideOfPackage(CLIENT_DATA)
          .and(
              new DescribedPredicate<JavaClass>("are not on the JDBC allow-list") {
                @Override
                public boolean test(JavaClass c) {
                  return !inAllowList(c, JDBC_ALLOWED);
                }
              })
          .should()
          .dependOnClassesThat(
              new DescribedPredicate<JavaClass>(
                  "are JDBC (org.springframework.jdbc.., DataSource)") {
                @Override
                public boolean test(JavaClass c) {
                  return c.getName().startsWith("org.springframework.jdbc.")
                      || c.getName().equals("javax.sql.DataSource");
                }
              })
          .because("client-owned data is queried only through scoped clientdata repositories");

  /** Rule 8. */
  static final ArchRule SYSTEM_SCOPE_ONLY_IN_JOBS =
      noClasses()
          .that()
          .resideOutsideOfPackages(SYSTEM_SCOPE_PACKAGES)
          .should()
          .callMethod(AccessScope.class, "system", String.class)
          .because("a SYSTEM scope sees every organisation; requests never widen themselves");

  @Test
  void everyRepositoryLivesInClientDataUnlessAllowListed() {
    REPOSITORIES_LIVE_IN_CLIENT_DATA.check(PRODUCTION);
  }

  @Test
  void onlyClientDataAndTheAllowListUseJdbc() {
    ONLY_CLIENT_DATA_USES_JDBC.check(PRODUCTION);
  }

  @Test
  void onlyJobsCreateASystemScope() {
    SYSTEM_SCOPE_ONLY_IN_JOBS.check(PRODUCTION);
  }

  @Test
  void aRogueTicketRepositoryOutsideClientDataFails() {
    assertThatThrownBy(() -> REPOSITORIES_LIVE_IN_CLIENT_DATA.check(BAD_FIXTURE))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("archfixture.ticket.TicketRepository");
    assertThatThrownBy(() -> ONLY_CLIENT_DATA_USES_JDBC.check(BAD_FIXTURE))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("archfixture.ticket.TicketRepository")
        .hasMessageContaining("JdbcClient");
  }

  /** Storage types that hold supporter-only content (and their nested row types). */
  static final Set<String> INTERNAL_STORAGE =
      Set.of(
          "com.ticketsystem.api.ticket.clientdata.InternalNoteRepository",
          "com.ticketsystem.api.ticket.clientdata.SystemEntryRepository");

  /** History is written by the ticket services but read only by the agent read model. */
  static final String HISTORY = "com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository";

  static final DescribedPredicate<JavaClass> SUPPORTER_ONLY_TYPES =
      new DescribedPredicate<>("hold internal notes, system entries, history or agent views") {
        @Override
        public boolean test(JavaClass c) {
          return inAllowList(c, INTERNAL_STORAGE)
              || inAllowList(c, Set.of(HISTORY))
              || c.getPackageName().startsWith("com.ticketsystem.api.ticket.agent");
        }
      };

  /** Rule 9. */
  static final ArchRule PUBLIC_MODELS_NEVER_SEE_INTERNAL_CONTENT =
      noClasses()
          .that()
          .resideInAnyPackage("..portal..", "..publicview..")
          .should()
          .dependOnClassesThat(SUPPORTER_ONLY_TYPES)
          .because("internal notes never leave agent-only code (ADR-009 C6)");

  /** Rule 10. */
  static final ArchRule INTERNAL_CONTENT_ONLY_IN_AGENT_CODE =
      classes()
          .that(
              new DescribedPredicate<JavaClass>("are internal-note or system-entry storage") {
                @Override
                public boolean test(JavaClass c) {
                  return inAllowList(c, INTERNAL_STORAGE);
                }
              })
          .should()
          .onlyHaveDependentClassesThat()
          .resideInAnyPackage(
              "com.ticketsystem.api.ticket.agent..", "com.ticketsystem.api.ticket.clientdata..")
          .because("only the agent read model may read supporter-only content");

  private static final JavaClasses TICKET_WITH_FIXTURE =
      new ClassFileImporter()
          .importPackages("com.ticketsystem.api.ticket", "com.ticketsystem.api.archfixture.portal");

  @Test
  void portalAndPublicModelsNeverSeeInternalContent() {
    PUBLIC_MODELS_NEVER_SEE_INTERNAL_CONTENT.check(PRODUCTION);
  }

  @Test
  void internalContentIsOnlyUsedByAgentCode() {
    INTERNAL_CONTENT_ONLY_IN_AGENT_CODE.check(PRODUCTION);
  }

  @Test
  void theInternalContentRulesAreNotVacuous() {
    assertThat(PRODUCTION.contain("com.ticketsystem.api.ticket.clientdata.InternalNoteRepository"))
        .isTrue();
    assertThat(PRODUCTION.contain("com.ticketsystem.api.ticket.portal.PortalTicketService"))
        .isTrue();
    assertThat(PRODUCTION.contain("com.ticketsystem.api.ticket.publicview.ClientThreadService"))
        .isTrue();
  }

  @Test
  void aPortalTypeCarryingInternalNotesFails() {
    assertThatThrownBy(() -> PUBLIC_MODELS_NEVER_SEE_INTERNAL_CONTENT.check(BAD_FIXTURE))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("LeakyPortalView")
        .hasMessageContaining("InternalNoteRow")
        .hasMessageContaining("InternalNoteView");
    assertThatThrownBy(() -> INTERNAL_CONTENT_ONLY_IN_AGENT_CODE.check(TICKET_WITH_FIXTURE))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("LeakyPortalView");
  }

  @Test
  void aControllerCreatingASystemScopeFails() {
    assertThatThrownBy(() -> SYSTEM_SCOPE_ONLY_IN_JOBS.check(BAD_FIXTURE))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("SystemScopeController.widen");
  }
}
