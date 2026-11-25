package com.ticketsystem.api.ticket.admin;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.admin.AdminConfigAuditEvent;
import com.ticketsystem.api.admin.PlainText;
import com.ticketsystem.api.audit.AuditEvent;
import com.ticketsystem.api.audit.AuditService;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository.RequestTypeRow;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin - Request types: add, rename, describe, set the starting priority, show or hide from the
 * portal, archive and reorder. The default assignee uses the SLA configuration endpoint. Admins
 * only; every change is audit-logged with the names of changed fields.
 *
 * <p>Guards: names are unique (case-insensitive); the built-in "Alert" type keeps its name, stays
 * hidden from the portal and cannot be archived (the integrations API and sender rules use it); a
 * type clients can choose never starts at P1, so a client's choice never starts the 24/7 P1 clock;
 * a type used by a sender rule cannot be archived. A type in use is archived, never deleted.
 */
@Service
public class RequestTypeAdminService {

  public static final String ALERT = "Alert";
  static final int NAME_MAX = 100;
  static final int DESCRIPTION_MAX = 200;
  static final int MAX_TYPES = 200;

  public record RequestTypeInput(
      String name,
      String description,
      String defaultPriority,
      boolean portalVisible,
      boolean archived) {}

  public record AdminRequestType(
      long id,
      String name,
      String description,
      Priority defaultPriority,
      boolean portalVisible,
      boolean archived,
      int sortOrder,
      Long defaultAssigneeUserId,
      boolean system,
      long ticketCount) {}

  private final RequestTypeRepository types;
  private final AuditService audit;
  private final Clock clock;

  public RequestTypeAdminService(RequestTypeRepository types, AuditService audit, Clock clock) {
    this.types = types;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<AdminRequestType> list(AccessScope scope) {
    scope.requireAdmin();
    return types.listAll(scope).stream().map(r -> view(scope, r)).toList();
  }

  @Transactional
  public AdminRequestType create(AccessScope scope, RequestTypeInput in, String clientIp) {
    scope.requireAdmin();
    String name = PlainText.required(in.name(), "a name", NAME_MAX);
    String description = PlainText.optional(in.description(), "the description", DESCRIPTION_MAX);
    Priority priority = priority(in.defaultPriority());
    checkP1(priority, in.portalVisible());
    if (types.listAll(scope).size() >= MAX_TYPES) {
      throw ApiException.invalidRequest("There can be at most " + MAX_TYPES + " request types.");
    }
    if (types.nameTaken(scope, name, null)) {
      throw duplicate(name);
    }
    long id =
        types.insert(
            scope,
            name,
            description.isEmpty() ? null : description,
            priority,
            in.portalVisible(),
            clock.instant());
    record(scope, AdminConfigAuditEvent.REQUEST_TYPE_CREATED, id, null, clientIp);
    return view(scope, types.find(scope, id).orElseThrow());
  }

  @Transactional
  public AdminRequestType update(AccessScope scope, long id, RequestTypeInput in, String clientIp) {
    scope.requireAdmin();
    RequestTypeRow old = types.find(scope, id).orElseThrow(AccessScope::notFound);
    String name = PlainText.required(in.name(), "a name", NAME_MAX);
    String description = PlainText.optional(in.description(), "the description", DESCRIPTION_MAX);
    Priority priority = priority(in.defaultPriority());
    boolean system = isAlert(old);
    if (system) {
      if (!name.equalsIgnoreCase(ALERT)) {
        throw conflict(
            "system_type", "The Alert type keeps its name: the integrations API uses it.");
      }
      if (in.portalVisible()) {
        throw conflict("system_type", "The Alert type is never offered to clients.");
      }
      if (in.archived()) {
        throw conflict(
            "system_type", "The Alert type cannot be archived: monitoring alerts use it.");
      }
    }
    checkP1(priority, in.portalVisible());
    if (types.nameTaken(scope, name, id)) {
      throw duplicate(name);
    }
    if (in.archived() && !old.archived() && types.senderRuleCount(scope, id) > 0) {
      throw conflict(
          "in_use_by_sender_rules",
          "Sender rules route mail to this type. Change or remove those rules first.");
    }
    String storedDescription = description.isEmpty() ? null : description;
    List<String> changed = new ArrayList<>();
    if (!old.name().equals(name)) changed.add("name");
    if (!Objects.equals(old.description(), storedDescription)) changed.add("description");
    if (old.defaultPriority() != priority) changed.add("default_priority");
    if (old.portalVisible() != in.portalVisible()) changed.add("portal_visible");
    if (old.archived() != in.archived()) changed.add("archived");
    if (!changed.isEmpty()) {
      types.update(scope, id, name, storedDescription, priority, in.portalVisible(), in.archived());
      record(
          scope,
          AdminConfigAuditEvent.REQUEST_TYPE_UPDATED,
          id,
          String.join(",", changed),
          clientIp);
    }
    return view(scope, types.find(scope, id).orElseThrow());
  }

  /** Sets the display order; {@code ids} must list every type exactly once. */
  @Transactional
  public List<AdminRequestType> reorder(AccessScope scope, List<Long> ids, String clientIp) {
    scope.requireAdmin();
    List<RequestTypeRow> all = types.listAll(scope);
    Set<Long> expected = new HashSet<>();
    all.forEach(r -> expected.add(r.id()));
    if (ids == null || ids.size() != expected.size() || !new HashSet<>(ids).equals(expected)) {
      throw ApiException.invalidRequest("List every request type exactly once.");
    }
    types.reorder(scope, ids);
    record(scope, AdminConfigAuditEvent.REQUEST_TYPES_REORDERED, null, null, clientIp);
    return list(scope);
  }

  private AdminRequestType view(AccessScope scope, RequestTypeRow r) {
    return new AdminRequestType(
        r.id(),
        r.name(),
        r.description() == null ? "" : r.description(),
        r.defaultPriority(),
        r.portalVisible(),
        r.archived(),
        r.sortOrder(),
        r.defaultAssigneeUserId(),
        isAlert(r),
        types.ticketCount(scope, r.id()));
  }

  static boolean isAlert(RequestTypeRow r) {
    return r.name().equalsIgnoreCase(ALERT);
  }

  private static Priority priority(String raw) {
    try {
      return Priority.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("Choose a starting priority: P1, P2, P3 or P4.");
    }
  }

  private static void checkP1(Priority priority, boolean portalVisible) {
    if (priority == Priority.P1 && portalVisible) {
      throw ApiException.invalidRequest(
          "A type clients can choose cannot start at P1 (it would start the 24/7 clock). Hide"
              + " it from the portal or choose P2-P4.");
    }
  }

  private static ApiException duplicate(String name) {
    return conflict("duplicate_name", "A request type called \"" + name + "\" already exists.");
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  private void record(
      AccessScope scope, AuditEvent type, Long id, String changed, String clientIp) {
    Map<String, Object> d = new LinkedHashMap<>();
    if (id != null) d.put("request_type", id);
    if (changed != null) d.put("changed", changed);
    audit.record(type, scope.principalId().orElse(null), null, clientIp, d);
  }
}
