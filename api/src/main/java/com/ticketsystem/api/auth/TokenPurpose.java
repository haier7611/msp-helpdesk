package com.ticketsystem.api.auth;

/**
 * What a link token is for. The first-admin setup token is not stored here: it comes from the
 * {@code TS_SETUP_TOKEN} environment variable (C8).
 */
public enum TokenPurpose {
  INVITATION,
  PASSWORD_RESET,
  MAGIC_LINK
}
