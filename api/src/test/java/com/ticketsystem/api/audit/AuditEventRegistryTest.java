package com.ticketsystem.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.AuthAuditEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * WP-13: the per-area audit event registry keeps exactly the event names and detail keys of the old
 * single {@code AuditEventType} enum and {@code AuditService.ALLOWED_FIELDS} set. The database
 * stores event names as strings, so a renamed or lost event would orphan stored rows; a lost or
 * added key would change what is logged.
 */
class AuditEventRegistryTest {

  /** Every value of the old AuditEventType enum (main before WP-13), in its order. */
  static final List<String> PREVIOUS_EVENTS =
      List.of(
          "SIGN_IN_SUCCEEDED",
          "SIGN_IN_FAILED",
          "SIGN_IN_THROTTLED",
          "SIGN_OUT",
          "SESSION_EXPIRED",
          "PASSWORD_CHANGED",
          "PASSWORD_RESET_REQUESTED",
          "PASSWORD_RESET_COMPLETED",
          "MAGIC_LINK_REQUESTED",
          "INVITATION_CREATED",
          "INVITATION_ACCEPTED",
          "INVITATION_CODE_FAILED",
          "INVITATION_REVOKED",
          "SETUP_COMPLETED",
          "SETUP_FAILED",
          "ORGANISATION_CREATED",
          "ORGANISATION_UPDATED",
          "ORGANISATION_DELETED",
          "ORGANISATION_PREFIX_CHANGED",
          "ORGANISATION_DOMAIN_ADDED",
          "ORGANISATION_DOMAIN_REMOVED",
          "USER_ORGANISATION_CHANGED",
          "SUPPORTER_ACCESS_CHANGED",
          "MFA_ENROLMENT_STARTED",
          "MFA_ENROLLED",
          "MFA_FAILED",
          "MFA_THROTTLED",
          "MFA_SIGN_IN_SUCCEEDED",
          "RECOVERY_CODE_USED",
          "RECOVERY_CODES_REGENERATED",
          "STEP_UP_SUCCEEDED",
          "MFA_RESET",
          "NEW_DEVICE_SIGN_IN",
          "TRUSTED_DEVICE_ADDED",
          "TRUSTED_DEVICE_REMOVED",
          "SESSION_REVOKED",
          "SECURITY_VERSION_BUMPED",
          "SESSION_INVALIDATED",
          "TICKET_ORGANISATION_CHANGED",
          "SUPPORTER_UPDATED",
          "SUPPORTER_ROLE_CHANGED",
          "SUPPORTER_DEACTIVATED",
          "SUPPORTER_REACTIVATED",
          "PROFILE_UPDATED",
          "PROFILE_PHOTO_CHANGED",
          "ENTRA_SETTINGS_CHANGED",
          "ENTRA_ENABLED",
          "ENTRA_DISABLED",
          "ENTRA_TEST_SUCCEEDED",
          "ENTRA_TEST_FAILED",
          "MICROSOFT_LINKED",
          "MICROSOFT_LINK_FAILED",
          "MICROSOFT_UNLINKED",
          "ORGANISATION_ENTRA_TENANT_CHANGED",
          "SLA_SETTINGS_CHANGED",
          "SLA_REPORT_EXPORTED",
          "MAILBOX_SAVED",
          "MAILBOX_DELETED",
          "EMAIL_SETTINGS_CHANGED",
          "BLOCK_RULE_ADDED",
          "BLOCK_RULE_REMOVED",
          "BLOCKED_MAIL_RESTORED",
          "SENDER_RULE_ADDED",
          "SENDER_RULE_REMOVED",
          "SUPPORTER_EMAIL_REPLY",
          "QUEUE_EXPORTED",
          "CONTACT_CREATED",
          "REQUEST_TYPE_CREATED",
          "REQUEST_TYPE_UPDATED",
          "REQUEST_TYPES_REORDERED",
          "SIGNATURE_TEMPLATE_CHANGED",
          "SIGNATURE_LOGO_CHANGED",
          "BRANDING_CHANGED",
          "AUDIT_LOG_EXPORTED",
          "SHARED_SAVED_REPLY_CHANGED",
          "RETENTION_RUN",
          "USER_ERASED",
          "AUDIT_EMAIL_SCRUBBED",
          "DATA_KEY_ROTATED",
          "API_KEY_CREATED",
          "API_KEY_REVOKED",
          "QA_SEED",
          "QA_WIPE",
          // WP-15 (ADR-031)
          "MFA_EXEMPTION_GRANTED",
          "MFA_EXEMPTION_REVOKED",
          "MFA_EXEMPTION_EXPIRED",
          // ADR-034
          "MAILBOX_SMTP_SET",
          "MAILBOX_SMTP_CHANGED",
          "MAILBOX_SMTP_REMOVED",
          // ADR-033
          "MAILBOX_UIDVALIDITY_CHANGED",
          // ADR-038 (Admin - Customers)
          "CUSTOMER_SIGNED_OUT_EVERYWHERE",
          "CUSTOMER_DEACTIVATED",
          "CUSTOMER_REACTIVATED",
          "CUSTOMER_MOVED",
          // ADR-041 (admin reopens a closed ticket)
          "TICKET_REOPENED",
          // ADR-049 (SLA automation rules)
          "SLA_AUTOMATION_RULE_CREATED",
          "SLA_AUTOMATION_RULE_UPDATED",
          "SLA_AUTOMATION_RULE_TOGGLED",
          "SLA_AUTOMATION_RULE_DELETED",
          "SLA_AUTOMATION_RULES_REORDERED",
          "SLA_AUTOMATION_ACTION",
          // ADR-048 (client email switches per organisation)
          "CLIENT_EMAILS_CHANGED");

  /** The old AuditService.ALLOWED_FIELDS (main before WP-13). */
  static final Set<String> PREVIOUS_ALLOWED_FIELDS =
      Set.of(
          "reason",
          "role",
          "method",
          "purpose",
          "email",
          "attempts",
          "status",
          "organisation",
          "prefix",
          "old_prefix",
          "domain",
          "all_organisations",
          "organisations",
          "device",
          "remaining",
          "sessions",
          "devices",
          "ticket",
          "old_organisation",
          "old_role",
          "tickets",
          "reassigned_to",
          "fields",
          "tenant",
          "changed",
          "error_code",
          "from",
          "to",
          "rows",
          "mailbox",
          "kind",
          "rule",
          "mode",
          "inbound_mail",
          "dmarc",
          "spf",
          "dkim",
          "request_type",
          "actor",
          "action",
          "saved_reply",
          "attachments",
          "api_key",
          "key_id",
          // WP-15 (ADR-031); the exemption reason is deliberately not a key
          "exempt_until",
          "replaced",
          "ended_by",
          // ADR-034: never a host, user name or password
          "smtp_security",
          "smtp_sign_in",
          // AUD-43: receiving server host names, not secrets
          "trusted_authserv_before",
          "trusted_authserv_after",
          // ADR-049: the SLA automation trigger name (a code)
          "trigger");

  @Test
  void registeredEventNamesAreUniqueAndEqualThePreviousEnum() {
    List<String> all = new ArrayList<>();
    for (Class<?> type : auditEventEnums()) {
      for (Object constant : type.getEnumConstants()) {
        all.add(((AuditEvent) constant).name());
      }
    }
    assertThat(all).doesNotHaveDuplicates();
    assertThat(new HashSet<>(all)).isEqualTo(new HashSet<>(PREVIOUS_EVENTS));
    assertThat(AuditEventRegistry.events().keySet())
        .containsExactlyInAnyOrderElementsOf(PREVIOUS_EVENTS);
    assertThat(PREVIOUS_EVENTS).hasSize(102);
  }

  @Test
  void allowListUnionEqualsThePreviousSet() {
    assertThat(AuditEventRegistry.allowedDetailKeys()).isEqualTo(PREVIOUS_ALLOWED_FIELDS);
    assertThat(AuditService.ALLOWED_FIELDS).isEqualTo(PREVIOUS_ALLOWED_FIELDS);
  }

  @Test
  void everyAreaIsAnEnumInItsOwnFeaturePackage() {
    List<Class<?>> enums = auditEventEnums();
    assertThat(enums).hasSizeGreaterThan(1).allMatch(Class::isEnum);
    assertThat(enums).noneMatch(c -> c.getPackageName().equals("com.ticketsystem.api.audit"));
  }

  @Test
  void eventsAreLookedUpBySameConstant() {
    Map<String, AuditEvent> events = AuditEventRegistry.events();
    assertThat(events.get("SIGN_IN_SUCCEEDED")).isSameAs(AuthAuditEvent.SIGN_IN_SUCCEEDED);
    assertThat(AuditEventRegistry.isRegistered(AuthAuditEvent.SIGN_OUT)).isTrue();
    assertThat(AuditEventRegistry.isRegistered(() -> "SIGN_OUT")).isFalse();
    assertThat(AuditEventRegistry.isRegistered(null)).isFalse();
  }

  @Test
  void anUnregisteredEventIsRefusedBeforeAnyWrite() {
    JdbcClient jdbc = Mockito.mock(JdbcClient.class);
    AuditService service =
        new AuditService(jdbc, JsonMapper.builder().build(), java.time.Clock.systemUTC());
    assertThatThrownBy(() -> service.record(() -> "NOT_REGISTERED", null, null, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    Mockito.verifyNoInteractions(jdbc);
  }

  private static List<Class<?>> auditEventEnums() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(AuditEvent.class));
    List<Class<?>> out = new ArrayList<>();
    scanner
        .findCandidateComponents(AuditEventRegistry.BASE_PACKAGE)
        .forEach(
            bd -> {
              try {
                out.add(Class.forName(bd.getBeanClassName()));
              } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
              }
            });
    return out;
  }
}
