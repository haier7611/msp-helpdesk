package com.ticketsystem.api.integration.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.session.RequiresStepUp;
import com.ticketsystem.api.integration.ApiKeyService;
import com.ticketsystem.api.integration.ApiKeyService.ApiKeyView;
import com.ticketsystem.api.integration.ApiKeyService.CreatedKey;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for an organisation's integrations API keys. Behind the {@code /api/v1/admin} gate;
 * creating a key needs step-up. The created key is in the response body once, with {@code
 * Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/admin/organisations/{organisationId}/api-keys")
public class AdminApiKeyController {

  record CreateRequest(String name, Instant expiresAt) {}

  private final ApiKeyService keys;

  public AdminApiKeyController(ApiKeyService keys) {
    this.keys = keys;
  }

  @GetMapping
  List<ApiKeyView> list(AccessScope scope, @PathVariable long organisationId) {
    return keys.list(scope, organisationId);
  }

  @PostMapping
  @RequiresStepUp
  ResponseEntity<CreatedKey> create(
      AccessScope scope,
      @PathVariable long organisationId,
      @RequestBody CreateRequest body,
      HttpServletRequest request) {
    CreatedKey created =
        keys.create(scope, organisationId, body.name(), body.expiresAt(), request.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(created);
  }

  @DeleteMapping("/{keyId}")
  ApiKeyView revoke(
      AccessScope scope,
      @PathVariable long organisationId,
      @PathVariable long keyId,
      HttpServletRequest request) {
    return keys.revoke(scope, organisationId, keyId, request.getRemoteAddr());
  }
}
