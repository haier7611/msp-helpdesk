package com.ticketsystem.api.auth;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Password rules (C15): at least 12 characters, at most 128 (bounds the Argon2 work per request),
 * not on the breached list, and not containing the email's local part.
 */
@Component
public class PasswordPolicy {

  public static final int MIN_LENGTH = 12;
  public static final int MAX_LENGTH = 128;

  /** Why a password was refused; the message is shown to the user as is. */
  public enum Violation {
    TOO_SHORT("Use at least " + MIN_LENGTH + " characters."),
    TOO_LONG("Use at most " + MAX_LENGTH + " characters."),
    CONTAINS_EMAIL("Do not use your email address in your password."),
    BREACHED(
        "This password has appeared in a data breach, so it is easy to guess. Choose another one.");

    private final String message;

    Violation(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  private final BreachedPasswordList breached;

  public PasswordPolicy(BreachedPasswordList breached) {
    this.breached = breached;
  }

  /** The first rule the password breaks, or empty when it is acceptable. */
  public Optional<Violation> check(String password, String email) {
    if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
      return Optional.of(Violation.TOO_SHORT);
    }
    if (password.length() > MAX_LENGTH) {
      return Optional.of(Violation.TOO_LONG);
    }
    if (email != null) {
      String local = Emails.localPart(email).toLowerCase(Locale.ROOT);
      if (local.length() >= 3 && password.toLowerCase(Locale.ROOT).contains(local)) {
        return Optional.of(Violation.CONTAINS_EMAIL);
      }
    }
    if (breached.contains(password)) {
      return Optional.of(Violation.BREACHED);
    }
    return Optional.empty();
  }
}
