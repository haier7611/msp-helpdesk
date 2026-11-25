package com.ticketsystem.api.settings.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.settings.NotificationRow;
import com.ticketsystem.api.settings.PreferencesRow;
import com.ticketsystem.api.settings.ProfileRow;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * My settings: the signed-in supporter's own profile, photo, preferences, notification choices and
 * out of office. Every method acts on the scope's own user id only - no method takes another user's
 * id - so one supporter can never read or change another's settings. Supporters only (customers
 * have no My settings).
 */
@Repository
public class MySettingsRepository {

  private final JdbcClient jdbc;

  public MySettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public ProfileRow profile(AccessScope scope) {
    long id = self(scope);
    return jdbc.sql(
            "SELECT u.email, u.name, COALESCE(p.job_title, '') AS job_title,"
                + " COALESCE(p.phone, '') AS phone, COALESCE(p.mobile, '') AS mobile,"
                + " COALESCE(p.free_line, '') AS free_line,"
                + " EXISTS (SELECT 1 FROM user_photo ph WHERE ph.user_id = u.id) AS has_photo"
                + " FROM app_user u LEFT JOIN supporter_profile p ON p.user_id = u.id"
                + " WHERE u.id = :id")
        .param("id", id)
        .query(
            (rs, n) ->
                new ProfileRow(
                    rs.getString("email"),
                    rs.getString("name"),
                    rs.getString("job_title"),
                    rs.getString("phone"),
                    rs.getString("mobile"),
                    rs.getString("free_line"),
                    rs.getBoolean("has_photo")))
        .single();
  }

  public void saveProfile(
      AccessScope scope,
      String displayName,
      String jobTitle,
      String phone,
      String mobile,
      String freeLine,
      Instant now) {
    long id = self(scope);
    jdbc.sql("UPDATE app_user SET name = :name, updated_at = :now WHERE id = :id")
        .param("name", displayName)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
    jdbc.sql(
            "INSERT INTO supporter_profile (user_id, job_title, phone, mobile, free_line,"
                + " updated_at) VALUES (:id, :title, :phone, :mobile, :line, :now)"
                + " ON CONFLICT (user_id) DO UPDATE SET job_title = :title, phone = :phone,"
                + " mobile = :mobile, free_line = :line, updated_at = :now")
        .param("id", id)
        .param("title", jobTitle)
        .param("phone", phone)
        .param("mobile", mobile)
        .param("line", freeLine)
        .param("now", Timestamp.from(now))
        .update();
  }

  public Optional<byte[]> photo(AccessScope scope) {
    long id = self(scope);
    return jdbc.sql("SELECT png FROM user_photo WHERE user_id = :id")
        .param("id", id)
        .query(byte[].class)
        .optional();
  }

  public void savePhoto(AccessScope scope, byte[] png, Instant now) {
    long id = self(scope);
    jdbc.sql(
            "INSERT INTO user_photo (user_id, png, updated_at) VALUES (:id, :png, :now)"
                + " ON CONFLICT (user_id) DO UPDATE SET png = :png, updated_at = :now")
        .param("id", id)
        .param("png", png)
        .param("now", Timestamp.from(now))
        .update();
  }

  public boolean deletePhoto(AccessScope scope) {
    long id = self(scope);
    return jdbc.sql("DELETE FROM user_photo WHERE user_id = :id").param("id", id).update() > 0;
  }

  public PreferencesRow preferences(AccessScope scope) {
    long id = self(scope);
    return jdbc.sql(
            "SELECT composer_tab, queue_view, timezone, date_format, theme, daily_digest,"
                + " out_of_office, ooo_starts_on, ooo_ends_on FROM supporter_preferences"
                + " WHERE user_id = :id")
        .param("id", id)
        .query(
            (rs, n) -> {
              Date from = rs.getDate("ooo_starts_on");
              Date until = rs.getDate("ooo_ends_on");
              return new PreferencesRow(
                  rs.getString("composer_tab"),
                  rs.getString("queue_view"),
                  rs.getString("timezone"),
                  rs.getString("date_format"),
                  rs.getString("theme"),
                  rs.getBoolean("daily_digest"),
                  rs.getBoolean("out_of_office"),
                  from == null ? null : from.toLocalDate(),
                  until == null ? null : until.toLocalDate());
            })
        .optional()
        .orElse(PreferencesRow.DEFAULTS);
  }

  public void savePreferences(AccessScope scope, PreferencesRow p, Instant now) {
    long id = self(scope);
    jdbc.sql(
            "INSERT INTO supporter_preferences (user_id, composer_tab, queue_view, timezone,"
                + " date_format, theme, daily_digest, out_of_office, ooo_starts_on, ooo_ends_on,"
                + " updated_at) VALUES (:id, :tab, :view, :tz, :df, :theme, :digest, :ooo, :from,"
                + " :until, :now) ON CONFLICT (user_id) DO UPDATE SET composer_tab = :tab,"
                + " queue_view = :view, timezone = :tz, date_format = :df, theme = :theme,"
                + " daily_digest = :digest, out_of_office = :ooo, ooo_starts_on = :from,"
                + " ooo_ends_on = :until, updated_at = :now")
        .param("id", id)
        .param("tab", p.composerTab())
        .param("view", p.queueView())
        .param("tz", p.timezone())
        .param("df", p.dateFormat())
        .param("theme", p.theme())
        .param("digest", p.dailyDigest())
        .param("ooo", p.outOfOffice())
        .param("from", p.outOfOfficeFrom() == null ? null : Date.valueOf(p.outOfOfficeFrom()))
        .param("until", p.outOfOfficeUntil() == null ? null : Date.valueOf(p.outOfOfficeUntil()))
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Stored choices keyed by event; events without a row use the default. */
  public Map<String, NotificationRow> notifications(AccessScope scope) {
    long id = self(scope);
    Map<String, NotificationRow> out = new LinkedHashMap<>();
    jdbc.sql("SELECT event, email, in_app FROM supporter_notification WHERE user_id = :id")
        .param("id", id)
        .query(
            (rs, n) ->
                new NotificationRow(
                    rs.getString("event"), rs.getBoolean("email"), rs.getBoolean("in_app")))
        .list()
        .forEach(r -> out.put(r.event(), r));
    return out;
  }

  public void saveNotifications(AccessScope scope, List<NotificationRow> rows) {
    long id = self(scope);
    for (NotificationRow r : rows) {
      jdbc.sql(
              "INSERT INTO supporter_notification (user_id, event, email, in_app)"
                  + " VALUES (:id, :event, :email, :inApp) ON CONFLICT (user_id, event)"
                  + " DO UPDATE SET email = :email, in_app = :inApp")
          .param("id", id)
          .param("event", r.event())
          .param("email", r.email())
          .param("inApp", r.inApp())
          .update();
    }
  }

  private static long self(AccessScope scope) {
    if (!scope.isSupporter()) {
      throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this.");
    }
    return scope.principalId().orElseThrow();
  }
}
