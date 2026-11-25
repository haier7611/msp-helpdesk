package com.ticketsystem.api.reference;

/**
 * A ticket reference such as {@code NWD-001}: an upper-case prefix and a number of at least 1. Only
 * the reference module creates these (enforced by the ArchUnit rule in {@code ArchitectureTest}):
 * use {@link ReferenceFormat#parse} for text and the counter for new numbers. {@link #toString()}
 * is the display form.
 *
 * @param prefix upper case, 2-6 letters or digits starting with a letter
 * @param number at least 1
 */
public record TicketReference(String prefix, long number) {

  public TicketReference {
    if (prefix == null || !ReferenceFormat.isValidPrefix(prefix)) {
      throw new IllegalArgumentException("invalid prefix");
    }
    if (number < 1) {
      throw new IllegalArgumentException("number must be at least 1");
    }
  }

  /** The display form, e.g. {@code NWD-001} or {@code NWD-1000}. */
  @Override
  public String toString() {
    return ReferenceFormat.format(this);
  }
}
