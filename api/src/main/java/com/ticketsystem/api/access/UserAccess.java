package com.ticketsystem.api.access;

import com.ticketsystem.api.auth.Role;
import java.util.List;

/**
 * A user's organisation access as the admin sees it: a customer's organisation, or an agent's "all
 * organisations" flag and list. Admins always see every organisation.
 */
public record UserAccess(
    long userId,
    Role role,
    Long organisationId,
    boolean allOrganisations,
    List<Long> organisationIds) {}
