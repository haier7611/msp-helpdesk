package com.ticketsystem.api.reference;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The one place ticket references and prefixes are formatted and parsed.
 *
 * <ul>
 *   <li>Format: {@code PREFIX-} plus the number zero-padded to at least 3 digits ({@code NWD-001},
 *       {@code ACD-042}, {@code NWD-1000}).
 *   <li>Parse: any letter case, surrounding white space, missing or extra leading zeros ({@code
 *       nwd-1}, {@code NWD-0001} and {@code NWD-001} are all {@code NWD-001}); at most 9 digits
 *       after the zeros; the number must be at least 1.
 *   <li>Prefix: 2-6 characters, upper-case letters and digits, starting with a letter; {@code TRI}
 *       is reserved for the triage queue.
 * </ul>
 *
 * <p>This is the canonical normaliser and the only copy of the reference pattern. The {@code
 * com.ticketsystem.api.mail.inbound.ReferenceExtractor} uses {@link #parse} and {@link
 * #findBracketed} rather than its own pattern.
 */
public final class ReferenceFormat {

  /** The triage queue's reserved prefix. */
  public static final String TRIAGE_PREFIX = "TRI";

  private static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9]{1,5}");
  private static final String TOKEN_REGEX = "([A-Za-z][A-Za-z0-9]{1,5})-0*([0-9]{1,9})";
  private static final Pattern TOKEN = Pattern.compile(TOKEN_REGEX);
  private static final Pattern BRACKETED = Pattern.compile("\\[[ \\t]*" + TOKEN_REGEX + "[ \\t]*]");

  private ReferenceFormat() {}

  /** {@code NWD-001}: the display form used in subjects, footers and the UI. */
  public static String format(TicketReference reference) {
    return reference.prefix() + "-" + String.format(Locale.ROOT, "%03d", reference.number());
  }

  /** Parses a bare reference such as {@code nwd-1}; empty when it is not one. */
  public static Optional<TicketReference> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = TOKEN.matcher(text.strip());
    if (!m.matches()) {
      return Optional.empty();
    }
    return toReference(m);
  }

  /**
   * Bracketed references ({@code [NWD-001]}, spaces or tabs allowed inside the brackets, same
   * tolerance as {@link #parse}) in order of appearance, duplicates included. The stream is lazy,
   * so a caller can stop after a few matches on hostile text.
   */
  public static Stream<TicketReference> findBracketed(CharSequence text) {
    if (text == null) {
      return Stream.empty();
    }
    return BRACKETED.matcher(text).results().flatMap(m -> toReference(m).stream());
  }

  private static Optional<TicketReference> toReference(MatchResult m) {
    long number = Long.parseLong(m.group(2));
    if (number < 1) {
      return Optional.empty();
    }
    return Optional.of(new TicketReference(m.group(1).toUpperCase(Locale.ROOT), number));
  }

  /** True for an already-normalised (upper-case) prefix of the right shape, TRI included. */
  public static boolean isValidPrefix(String prefix) {
    return prefix != null && PREFIX.matcher(prefix).matches();
  }

  /** The outcome of checking a prefix an admin typed. */
  public sealed interface PrefixCheck {
    record Valid(String prefix) implements PrefixCheck {}

    record Invalid(String message) implements PrefixCheck {}
  }

  /**
   * Checks and upper-cases a prefix for an organisation ({@code acd} -> {@code ACD}). Uniqueness
   * against existing and retired prefixes is the database's job (unique index on {@code
   * upper(prefix)}).
   */
  public static PrefixCheck checkOrganisationPrefix(String raw) {
    String prefix = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (!PREFIX.matcher(prefix).matches()) {
      return new PrefixCheck.Invalid(
          "A ticket prefix is 2 to 6 letters or digits and starts with a letter, for example"
              + " ACD.");
    }
    if (TRIAGE_PREFIX.equals(prefix)) {
      return new PrefixCheck.Invalid(
          "TRI is reserved for the triage queue. Choose another prefix.");
    }
    return new PrefixCheck.Valid(prefix);
  }
}
