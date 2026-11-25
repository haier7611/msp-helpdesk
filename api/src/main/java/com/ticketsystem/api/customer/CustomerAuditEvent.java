package com.ticketsystem.api.customer;

import com.ticketsystem.api.audit.AuditEvent;

/**
 * Admin - Customers. The customer is the target user, the admin the actor. Details use keys other
 * areas already allow-list ({@code status}, {@code organisation}, {@code old_organisation}, {@code
 * tickets}); never a name or an email address. Resending an invitation and sending a password reset
 * keep their existing auth events ({@code INVITATION_CREATED}, {@code PASSWORD_RESET_REQUESTED}
 * with {@code reason=admin}); erasure keeps {@code USER_ERASED}.
 *
 * <p>Stored by name in {@code audit_event.type}: append new values, never rename. Registered
 * automatically by {@link com.ticketsystem.api.audit.AuditEventRegistry}.
 */
public enum CustomerAuditEvent implements AuditEvent {
  /** An admin ended every session of a customer ("sessions" is on SECURITY_VERSION_BUMPED). */
  CUSTOMER_SIGNED_OUT_EVERYWHERE,
  /** An admin deactivated a customer ("status" = the status before). */
  CUSTOMER_DEACTIVATED,
  /** An admin reactivated a customer ("status" = ACTIVE, or INVITED when no password was set). */
  CUSTOMER_REACTIVATED,
  /**
   * An admin moved a customer to another organisation ("organisation" = new id, "old_organisation"
   * = previous id, "tickets" = tickets of other organisations they stopped being a participant of).
   */
  CUSTOMER_MOVED
}
