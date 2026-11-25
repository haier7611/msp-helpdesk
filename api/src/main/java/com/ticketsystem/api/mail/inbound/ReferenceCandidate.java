package com.ticketsystem.api.mail.inbound;

/**
 * A ticket reference found in an inbound subject: a normalised prefix and number, not a validated
 * reference. Only the reference module ({@code ReferenceFormat}, {@code ReferenceService.resolve})
 * decides whether it exists and builds the display form ({@code NWD-001}); this type deliberately
 * has no method that builds a reference string.
 *
 * @param prefix upper case, 2-6 letters or digits starting with a letter
 * @param number the number without leading zeros, at least 1
 */
public record ReferenceCandidate(String prefix, long number) {}
