package com.ticketsystem.api.portal.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.portal.PortalAccountService;
import com.ticketsystem.api.portal.PortalAccountService.Account;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own account: read it, change the name. The only non-ticket routes in the portal
 * tree; the route inventory test fails if another one appears.
 */
@RestController
@RequestMapping("/api/v1/portal/account")
public class PortalAccountController {

  record RenameRequest(String name) {}

  private final PortalAccountService accounts;

  public PortalAccountController(PortalAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  Account get(AccessScope scope) {
    return accounts.get(scope);
  }

  @PatchMapping
  Account rename(AccessScope scope, @RequestBody RenameRequest r) {
    return accounts.rename(scope, r.name());
  }
}
