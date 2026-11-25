package com.ticketsystem.api.audit;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Every audit event type and the detail-key allow-list, collected once from the per-area {@link
 * AuditEvent} enums under {@code com.ticketsystem.api}. Replaces the single shared enum and
 * allow-list, so parallel features no longer edit the same lines; the stored names and the allowed
 * keys are unchanged (AuditEventRegistryTest pins both).
 *
 * <p>Fails fast (on first use, at application start) when two areas declare the same event name, or
 * when an {@link AuditEvent} implementation is not an enum.
 */
public final class AuditEventRegistry {

  static final String BASE_PACKAGE = "com.ticketsystem.api";

  private static final Registry REGISTRY = scan();

  private AuditEventRegistry() {}

  /** All registered events by stored name, sorted by name. */
  public static Map<String, AuditEvent> events() {
    return REGISTRY.events();
  }

  /** The union of every area's {@link AuditDetailKeys}: the only detail keys ever stored. */
  public static Set<String> allowedDetailKeys() {
    return REGISTRY.detailKeys();
  }

  /** True when {@code event} is one of the registered events (same name, same constant). */
  public static boolean isRegistered(AuditEvent event) {
    return event != null && REGISTRY.events().get(event.name()) == event;
  }

  private record Registry(Map<String, AuditEvent> events, Set<String> detailKeys) {}

  private static Registry scan() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(AuditEvent.class));
    Map<String, AuditEvent> events = new TreeMap<>();
    Set<String> keys = new TreeSet<>();
    ClassLoader loader = AuditEventRegistry.class.getClassLoader();
    for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
      Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), loader);
      if (!type.isEnum()) {
        throw new IllegalStateException(
            "AuditEvent implementations must be enums: " + type.getName());
      }
      for (Object constant : type.getEnumConstants()) {
        AuditEvent event = (AuditEvent) constant;
        AuditEvent previous = events.putIfAbsent(event.name(), event);
        if (previous != null) {
          throw new IllegalStateException(
              "Duplicate audit event name "
                  + event.name()
                  + " in "
                  + type.getName()
                  + " and "
                  + previous.getClass().getName());
        }
      }
      AuditDetailKeys declared = type.getAnnotation(AuditDetailKeys.class);
      if (declared != null) {
        keys.addAll(Set.of(declared.value()));
      }
    }
    if (events.isEmpty()) {
      throw new IllegalStateException("No AuditEvent enums found under " + BASE_PACKAGE);
    }
    return new Registry(Collections.unmodifiableMap(events), Collections.unmodifiableSet(keys));
  }
}
