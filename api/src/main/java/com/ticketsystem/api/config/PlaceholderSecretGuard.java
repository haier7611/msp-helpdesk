package com.ticketsystem.api.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops the app before anything connects when a secret still holds the placeholder from
 * .env.example (a copied-and-forgotten example file must not start with a known password). Runs as
 * an EnvironmentPostProcessor, so it fails before Flyway or the DataSource start.
 */
public class PlaceholderSecretGuard implements EnvironmentPostProcessor {

  static final String PLACEHOLDER_MARKER = "replace_me";
  static final List<String> GUARDED =
      List.of(
          "spring.datasource.password",
          "spring.flyway.password",
          "app.mail.smtp.password",
          "TS_SETUP_TOKEN",
          "app.crypto.data-key");

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    for (String key : GUARDED) {
      String value = environment.getProperty(key);
      if (isPlaceholder(value)) {
        throw new IllegalStateException(
            key
                + " is still the placeholder from .env.example - set a real value in .env"
                + " (the value itself is not logged)");
      }
    }
  }

  static boolean isPlaceholder(String value) {
    if (value == null) {
      return false;
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    return v.contains(PLACEHOLDER_MARKER) || v.equals("change-me") || v.equals("changeme");
  }
}
