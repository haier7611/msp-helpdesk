package com.ticketsystem.api.auth;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;

/**
 * The principal kept in the server-side session. Deliberately small: no email or name (personal
 * data stays in {@code app_user}), no password hash. {@link #getName()} is the user id, which
 * Spring Session stores as the session's principal name so a user's sessions can be found. The
 * user's security_version at sign-in is a separate session attribute ({@link SessionKeys}).
 */
public record AuthenticatedUser(long id, Role role) implements Principal, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public String getName() {
    return Long.toString(id);
  }
}
