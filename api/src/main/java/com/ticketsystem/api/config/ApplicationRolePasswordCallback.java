package com.ticketsystem.api.config;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sets the password of the application's database role after every Flyway migrate (C14).
 *
 * <p>Flyway runs as the owner role ({@code spring.flyway.user}); migration V5 creates the
 * restricted role {@code ticketsys_app} that the running application uses ({@code
 * spring.datasource.username}). This callback gives that role the datasource password, so the
 * operator manages one secret per role in {@code .env} and a rotation takes effect on the next
 * start. The password is hashed client-side with SCRAM-SHA-256 by the PostgreSQL driver, so the
 * plain value never appears in SQL text, the server log or {@code pg_stat_activity}.
 *
 * <p>With a single role (no {@code spring.flyway.user}, e.g. a bare local run) there is nothing to
 * do; a warning says the audit log is then not append-only for the application.
 */
@Component
public class ApplicationRolePasswordCallback implements Callback {

  static final String APP_ROLE = "ticketsys_app";
  private static final Logger log = LoggerFactory.getLogger(ApplicationRolePasswordCallback.class);

  private final String flywayUser;
  private final String appUser;
  private final String appPassword;

  public ApplicationRolePasswordCallback(
      @Value("${spring.flyway.user:}") String flywayUser,
      @Value("${spring.datasource.username:}") String appUser,
      @Value("${spring.datasource.password:}") String appPassword) {
    this.flywayUser = flywayUser;
    this.appUser = appUser;
    this.appPassword = appPassword;
  }

  /** True when Flyway and the application use different database roles. */
  boolean split() {
    return flywayUser != null && !flywayUser.isBlank() && !flywayUser.equals(appUser);
  }

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.AFTER_MIGRATE;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public void handle(Event event, Context context) {
    if (!split()) {
      log.warn(
          "Database: Flyway and the application share one role - the audit log is not"
              + " append-only for the application. Set SPRING_FLYWAY_USER to the owner role and"
              + " SPRING_DATASOURCE_USERNAME to {} in production.",
          APP_ROLE);
      return;
    }
    if (!APP_ROLE.equals(appUser)) {
      throw new IllegalStateException(
          "spring.datasource.username must be " + APP_ROLE + " when spring.flyway.user is set");
    }
    if (appPassword == null || appPassword.isBlank()) {
      throw new IllegalStateException(
          "spring.datasource.password (the " + APP_ROLE + " password) is not set");
    }
    try {
      Connection connection = context.getConnection();
      connection
          .unwrap(PGConnection.class)
          .alterUserPassword(APP_ROLE, appPassword.toCharArray(), "scram-sha-256");
      log.info("Database: password for role {} set (value not logged)", APP_ROLE);
    } catch (SQLException e) {
      throw new IllegalStateException("could not set the password of role " + APP_ROLE, e);
    }
  }

  @Override
  public String getCallbackName() {
    return "ticketsys-app-role-password";
  }
}
