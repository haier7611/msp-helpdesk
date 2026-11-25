package com.ticketsystem.api.auth.session;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.auth.SessionKeys;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Step-up (C3): sensitive actions need a fresh TOTP or recovery code entered in the last 10 minutes
 * on this session. Trusted devices never satisfy it. Enforced by {@link RequiresStepUp} on
 * endpoints, or by calling {@link #require} where it is conditional.
 */
@Component
public class StepUp {

  public static final Duration VALIDITY = Duration.ofMinutes(10);

  private final Clock clock;

  public StepUp(Clock clock) {
    this.clock = clock;
  }

  public static ApiException required() {
    return new ApiException(
        HttpStatus.FORBIDDEN,
        "step_up_required",
        "Please confirm it is you with a code from your authenticator app.");
  }

  public boolean isFresh(HttpSession session) {
    if (session == null) {
      return false;
    }
    Object at = session.getAttribute(SessionKeys.STEP_UP_AT);
    return at instanceof Long millis
        && clock.millis() >= millis
        && clock.millis() - millis < VALIDITY.toMillis();
  }

  /** Throws 403 {@code step_up_required} unless the session stepped up in the last 10 minutes. */
  public void require(HttpSession session) {
    if (!isFresh(session)) {
      throw required();
    }
  }

  public void markDone(HttpSession session) {
    session.setAttribute(SessionKeys.STEP_UP_AT, clock.millis());
  }
}
