package com.ticketsystem.api.notification;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.clientdata.SupporterSettingsRepository;
import com.ticketsystem.api.notification.clientdata.SupporterSettingsRepository.OutOfOfficeRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A supporter's notification choices, digest, timezone and out of office as stored by My settings.
 * Defaults when nothing is stored (migration V40): email and in-app on for every event, no daily
 * digest, timezone {@code app.notifications.default-timezone} (Asia/Ho_Chi_Minh), not out of
 * office. P1 alerts ignore the choices.
 */
@Service
public class SupporterPreferences {

  private static final Logger log = LoggerFactory.getLogger(SupporterPreferences.class);

  public enum Channel {
    IN_APP,
    EMAIL
  }

  private final SupporterSettingsRepository settings;
  private final ZoneId defaultZone;

  public SupporterPreferences(
      SupporterSettingsRepository settings,
      @Value("${app.notifications.default-timezone:Asia/Ho_Chi_Minh}") String defaultZone) {
    this.settings = settings;
    this.defaultZone = ZoneId.of(defaultZone);
  }

  /** The stored event name for a kind; null for kinds without a choice (P1). */
  static String eventName(NotificationKind kind) {
    return switch (kind) {
      case TICKET_ASSIGNED, CLIENT_REPLIED, MENTIONED, INTERNAL_NOTE_ADDED, SLA_BREACHED ->
          kind.name();
      case SLA_AT_RISK, SLA_AUTOMATION -> "SLA_WARNING";
      case P1_CREATED, P1_AT_RISK -> null;
    };
  }

  @Transactional(readOnly = true)
  public boolean wants(AccessScope scope, long userId, NotificationKind kind, Channel channel) {
    String event = eventName(kind);
    if (kind.mandatory() || event == null) {
      return true;
    }
    return settings
        .choice(scope, userId, event)
        .map(c -> channel == Channel.EMAIL ? c.email() : c.inApp())
        .orElse(true);
  }

  /** Optional daily digest email of open and breaching tickets (default off). */
  @Transactional(readOnly = true)
  public boolean dailyDigest(AccessScope scope, long userId) {
    return settings.digest(scope, userId).map(d -> d.dailyDigest()).orElse(false);
  }

  /** The supporter's timezone (the digest goes out at 08:00 in it). */
  @Transactional(readOnly = true)
  public ZoneId timezone(AccessScope scope, long userId) {
    Optional<String> tz = settings.digest(scope, userId).map(d -> d.timezone());
    if (tz.isEmpty()) {
      return defaultZone;
    }
    try {
      return ZoneId.of(tz.get());
    } catch (RuntimeException e) {
      log.warn("supporter {} has an unreadable timezone; using the default", userId);
      return defaultZone;
    }
  }

  /** The supporter's out of office if it is on and covers {@code day}. */
  @Transactional(readOnly = true)
  public Optional<OutOfOfficeRow> outOfOfficeOn(AccessScope scope, long userId, LocalDate day) {
    return settings.outOfOffice(scope, userId).filter(o -> o.covers(day));
  }
}
