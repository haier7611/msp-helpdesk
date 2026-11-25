package com.ticketsystem.api.notification.clientdata;

import com.ticketsystem.api.access.AccessScope;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads what My settings stores for each supporter (V40: {@code supporter_preferences}, {@code
 * supporter_notification}) for notifications, digest and auto-assignment. Read only; supporter or
 * SYSTEM scope (customers and API keys get 404). A missing row reads as the defaults.
 */
@Repository
public class SupporterSettingsRepository {

  /** One stored per-event choice. */
  public record Choice(boolean email, boolean inApp) {}

  /** Stored digest choice and timezone (defaults when there is no row). */
  public record DigestSettings(boolean dailyDigest, String timezone) {}

  /** Out of office as stored: on, with optional first and last day. */
  public record OutOfOfficeRow(LocalDate startsOn, LocalDate endsOn) {

    /** On today: both days included; a missing day means "open-ended" on that side. */
    public boolean covers(LocalDate day) {
      return (startsOn == null || !day.isBefore(startsOn))
          && (endsOn == null || !day.isAfter(endsOn));
    }
  }

  private final JdbcClient jdbc;

  public SupporterSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The stored choice for {@code event} (event name); empty = default (both on). */
  public Optional<Choice> choice(AccessScope scope, long userId, String event) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT email, in_app FROM supporter_notification WHERE user_id = :u AND event = :e")
        .param("u", userId)
        .param("e", event)
        .query((rs, n) -> new Choice(rs.getBoolean("email"), rs.getBoolean("in_app")))
        .optional();
  }

  public Optional<DigestSettings> digest(AccessScope scope, long userId) {
    scope.requireSupporterOrSystem();
    return jdbc.sql("SELECT daily_digest, timezone FROM supporter_preferences WHERE user_id = :u")
        .param("u", userId)
        .query((rs, n) -> new DigestSettings(rs.getBoolean(1), rs.getString(2)))
        .optional();
  }

  /** The supporter's out of office when it is switched on; empty when off or never set. */
  public Optional<OutOfOfficeRow> outOfOffice(AccessScope scope, long userId) {
    scope.requireSupporterOrSystem();
    return jdbc.sql(
            "SELECT ooo_starts_on, ooo_ends_on FROM supporter_preferences WHERE user_id = :u AND"
                + " out_of_office")
        .param("u", userId)
        .query(
            (rs, n) -> {
              Date s = rs.getDate(1);
              Date e = rs.getDate(2);
              return new OutOfOfficeRow(
                  s == null ? null : s.toLocalDate(), e == null ? null : e.toLocalDate());
            })
        .optional();
  }
}
