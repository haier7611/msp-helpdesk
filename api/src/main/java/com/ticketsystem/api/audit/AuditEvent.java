package com.ticketsystem.api.audit;

/**
 * An audit event type (C14). Each feature area declares its own events as a small enum in its own
 * package that implements this interface (for example {@code auth.AuthAuditEvent}), annotated with
 * {@link AuditDetailKeys} for the detail keys it adds to the allow-list. {@link AuditEventRegistry}
 * finds them on the classpath, so a new feature adds its own file instead of editing a shared enum.
 *
 * <p>The event is stored by {@link #name()} in {@code audit_event.type}: names are unique across
 * all areas (checked at startup and by a test) and are never renamed.
 */
public interface AuditEvent {

  /** The stored event type, e.g. {@code SIGN_IN_SUCCEEDED}. Enum constants provide it. */
  String name();
}
