package com.ticketsystem.api.mail.inbound;

import com.ticketsystem.api.reference.ReferenceFormat;
import com.ticketsystem.api.reference.TicketReference;
import java.util.List;
import java.util.Optional;

/**
 * Finds bracketed ticket references ({@code [PREFIX-number]}) anywhere in a subject (spec section
 * 6, inbound matching step 1). Tolerant of reply/forward prefixes ({@code RE:}, {@code FW:}, {@code
 * AW:}, {@code SV:} ... - irrelevant because the whole subject is searched), extra brackets ({@code
 * [[NWD-001]]}), spaces inside the brackets, any letter case and missing or extra leading zeros
 * ({@code [nwd-1]} gives NWD / 1). A reference without brackets ({@code ACD-042}) or without a
 * number ({@code [ACD-]}) is not matched.
 *
 * <p>It returns candidates only; whether one exists, and to which organisation it belongs, is
 * decided by the reference module.
 */
public final class ReferenceExtractor {

  /** Most candidates returned for one subject, so a hostile subject cannot cause many lookups. */
  static final int MAX_CANDIDATES = 10;

  private ReferenceExtractor() {}

  /** Candidates in order of appearance, without duplicates, at most {@value #MAX_CANDIDATES}. */
  public static List<ReferenceCandidate> extract(String subject) {
    return ReferenceFormat.findBracketed(subject)
        .map(ReferenceExtractor::toCandidate)
        .distinct()
        .limit(MAX_CANDIDATES)
        .toList();
  }

  /**
   * Normalises one bare token such as {@code nwd-1} or {@code NWD-0001} to prefix and number. It
   * delegates to the reference module's canonical {@link ReferenceFormat#parse} (any case, leading
   * zeros ignored, number at least 1), so the two cannot drift.
   */
  public static Optional<ReferenceCandidate> normaliseToken(String token) {
    return ReferenceFormat.parse(token).map(ReferenceExtractor::toCandidate);
  }

  private static ReferenceCandidate toCandidate(TicketReference reference) {
    return new ReferenceCandidate(reference.prefix(), reference.number());
  }
}
