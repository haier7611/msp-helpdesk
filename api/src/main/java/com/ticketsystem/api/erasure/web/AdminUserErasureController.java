package com.ticketsystem.api.erasure.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.erasure.UserErasureService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin - Erase personal data. Admin tree only (the role gate answers 403 to agents and customers);
 * needs a fresh step-up, like other irreversible admin actions. The screen is a follow-up in Admin
 * - Customers / Manage supporters.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/users/{id}/erase")
public class AdminUserErasureController {

  private final UserErasureService erasure;

  public AdminUserErasureController(UserErasureService erasure) {
    this.erasure = erasure;
  }

  @PostMapping
  @RequiresStepUp
  ResponseEntity<Void> erase(
      AccessScope scope, @PathVariable("id") long id, HttpServletRequest request) {
    erasure.erase(scope, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }
}
