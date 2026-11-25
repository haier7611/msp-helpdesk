package com.ticketsystem.api.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The audit detail keys a feature area adds to the allow-list, declared on its {@link AuditEvent}
 * enum. The allow-list is the union over all areas: {@link AuditService} stores only these keys,
 * for any event. Never declare a secret-bearing key (password, token, code, hash, secret).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AuditDetailKeys {
  String[] value();
}
