package com.ticketsystem.api.mail;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The support name used as the From name and in email templates: set by an admin in Admin -
 * Branding ({@code signature_template.support_name}), else {@code MAIL_SUPPORT_NAME} from the
 * environment. Read on every use (one primary-key row), so a change shows in the next email on
 * every instance. MSP-wide branding, no organisation data, so it reads the row directly
 * (ArchitectureTest allow-list); a database error falls back to the environment value rather than
 * stopping mail.
 */
@Component
public class SupportName implements Supplier<String> {

  private static final Logger log = LoggerFactory.getLogger(SupportName.class);

  private final JdbcClient jdbc;
  private final MailSettings settings;

  public SupportName(JdbcClient jdbc, MailSettings settings) {
    this.jdbc = jdbc;
    this.settings = settings;
  }

  @Override
  public String get() {
    try {
      String name =
          jdbc.sql("SELECT support_name FROM signature_template WHERE id = 1")
              .query(String.class)
              .optional()
              .orElse(null);
      return name == null || name.isBlank() ? settings.supportName() : name;
    } catch (DataAccessException e) {
      log.warn("could not read the support name; using MAIL_SUPPORT_NAME");
      return settings.supportName();
    }
  }
}
