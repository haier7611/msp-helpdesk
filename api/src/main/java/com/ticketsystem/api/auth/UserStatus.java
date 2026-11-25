package com.ticketsystem.api.auth;

/** Account status: created by an invitation, active after accepting it, deactivated by an admin. */
public enum UserStatus {
  INVITED,
  ACTIVE,
  DEACTIVATED
}
