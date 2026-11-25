package com.ticketsystem.api.supporter;

import com.ticketsystem.api.auth.Role;
import com.ticketsystem.api.auth.UserStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One supporter in Admin - Manage supporters. {@code roleChangeBlockedReason} and {@code
 * deactivateBlockedReason} carry the C18 guard reasons (yourself, last active admin) so the UI can
 * disable the buttons and say why; the server enforces the same guards on every action. {@code
 * mfaExemptUntil} / {@code mfaExemptReason} describe a two-step exemption that applies now (both
 * null otherwise).
 */
public record SupporterView(
    long id,
    String email,
    String name,
    String jobTitle,
    Role role,
    UserStatus status,
    boolean allOrganisations,
    List<Long> organisationIds,
    boolean mfaEnrolled,
    Instant lastSignInAt,
    int openTickets,
    boolean outOfOffice,
    LocalDate outOfOfficeUntil,
    Instant invitationExpiresAt,
    Instant createdAt,
    boolean self,
    String roleChangeBlockedReason,
    String deactivateBlockedReason,
    Instant mfaExemptUntil,
    String mfaExemptReason) {

  @Override
  public String toString() {
    return "SupporterView[id=" + id + ", role=" + role + ", status=" + status + "]";
  }
}
