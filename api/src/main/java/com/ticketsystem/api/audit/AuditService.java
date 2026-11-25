package com.ticketsystem.api.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the security audit log (C14). Only allow-listed detail fields are stored: anything else is
 * dropped, so a caller cannot leak a password, token, code or hash into the log by mistake. Values
 * are reduced to short strings, numbers or booleans. The application's database role can only
 * insert and read {@code audit_event} (migration V5).
 *
 * <p>Runs in the caller's transaction when there is one (so an event is recorded exactly when its
 * change commits), otherwise on its own.
 */
@Service
public class AuditService {

  /**
   * The only detail keys ever stored: the union of every area's {@link AuditDetailKeys} (each
   * feature declares its keys next to its {@link AuditEvent} enum). Never add secret-bearing keys.
   */
  public static final Set<String> ALLOWED_FIELDS = AuditEventRegistry.allowedDetailKeys();

  static final int MAX_VALUE_LENGTH = 200;

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;

  public AuditService(JdbcClient jdbc, JsonMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  public void record(
      AuditEvent type,
      Long actorUserId,
      Long targetUserId,
      String clientIp,
      Map<String, ?> details) {
    if (!AuditEventRegistry.isRegistered(type)) {
      throw new IllegalArgumentException("Unregistered audit event: " + type);
    }
    jdbc.sql(
            "INSERT INTO audit_event (occurred_at, type, actor_user_id, target_user_id, client_ip,"
                + " details) VALUES (?, ?, ?, ?, ?, ?::jsonb)")
        .params(
            Timestamp.from(clock.instant()),
            type.name(),
            actorUserId,
            targetUserId,
            truncate(clientIp, 64),
            json.writeValueAsString(filter(details)))
        .update();
  }

  /** Keeps allow-listed keys only, with simple values. Visible for tests. */
  static Map<String, Object> filter(Map<String, ?> details) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (details == null) {
      return out;
    }
    details.forEach(
        (key, value) -> {
          if (key == null || value == null || !ALLOWED_FIELDS.contains(key)) {
            return;
          }
          if (value instanceof Number || value instanceof Boolean) {
            out.put(key, value);
          } else if (value instanceof CharSequence || value instanceof Enum<?>) {
            out.put(key, truncate(value.toString(), MAX_VALUE_LENGTH));
          }
          // Anything else (objects, collections) is dropped: no accidental dumps.
        });
    return out;
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
