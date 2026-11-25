package com.ticketsystem.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a test class as part of the go-live security suite (spec section 12, security tests 1-5,
 * WP-12). Carries the JUnit tag {@code SecurityGoLive}, so the whole suite runs on its own with
 * {@code scripts/ci/api.sh securityGoLive}; it also runs in every normal build. {@link #value}
 * names which of the five tests the class covers; {@code SecurityGoLiveInventoryTest} fails the
 * build if any of the five has no tagged class.
 *
 * <ol>
 *   <li>Isolation matrix (organisation scoping, internal notes never reach customers).
 *   <li>Microsoft sign-in (wrong tenant, MFA claim, tid+oid links).
 *   <li>Sanitiser corpus, CSP and download headers.
 *   <li>Session and token lifecycle.
 *   <li>Inbound email spoofing.
 * </ol>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("SecurityGoLive")
public @interface SecurityGoLive {

  /** The spec section 12 security test numbers (1-5) this class covers. */
  int[] value();
}
