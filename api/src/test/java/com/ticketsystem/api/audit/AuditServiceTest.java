package com.ticketsystem.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The audit field allow list (C14): secrets and unknown fields never reach the log. */
class AuditServiceTest {

  @Test
  void onlyAllowListedSimpleFieldsAreKept() {
    Map<String, Object> in = new HashMap<>();
    in.put("reason", "wrong_password");
    in.put("attempts", 3);
    in.put("password", "Violet-Tractor-Sundial-42");
    in.put("token", "tok_SYNTHETIC");
    in.put("code", "123456");
    in.put("token_hash", "ab".repeat(32));
    in.put("role", List.of("ADMIN")); // allowed key, but not a simple value
    in.put("email", "x".repeat(500));

    Map<String, Object> out = AuditService.filter(in);

    assertThat(out).containsOnlyKeys("reason", "attempts", "email");
    assertThat(out.get("email").toString()).hasSize(AuditService.MAX_VALUE_LENGTH);
    assertThat(AuditService.ALLOWED_FIELDS)
        .doesNotContain("password", "token", "code", "hash", "secret");
  }

  @Test
  void nullDetailsAreEmpty() {
    assertThat(AuditService.filter(null)).isEmpty();
  }
}
