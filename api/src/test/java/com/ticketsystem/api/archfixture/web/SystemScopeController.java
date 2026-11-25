package com.ticketsystem.api.archfixture.web;

import com.ticketsystem.api.access.AccessScope;

/**
 * Test-only negative fixture for ArchitectureTest: a request handler that widens itself to every
 * organisation with {@link AccessScope#system}. The rule that only job and poller packages may
 * create a SYSTEM scope must reject it. Not annotated, so it never becomes a Spring bean.
 */
public class SystemScopeController {

  public AccessScope widen() {
    return AccessScope.system("fixture");
  }
}
