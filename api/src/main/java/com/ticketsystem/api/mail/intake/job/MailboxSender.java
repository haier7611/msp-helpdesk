package com.ticketsystem.api.mail.intake.job;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.crypto.DataCipher;
import com.ticketsystem.api.mail.ConfiguredSender;
import com.ticketsystem.api.mail.SmtpAccount;
import com.ticketsystem.api.mail.intake.MailboxService;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository;
import com.ticketsystem.api.mail.intake.clientdata.MailboxRepository.MailboxRow;
import com.ticketsystem.api.mail.intake.imap.MailboxAccessException;
import com.ticketsystem.api.ticket.agent.InternalNoteService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The admin-configured sending mailbox for the outbox worker: the enabled mailbox with SMTP
 * settings and a secret (lowest id). Its secret is decrypted, or exchanged for an OAuth2 token,
 * once per outbox round. A configured mailbox whose secret cannot be used makes the round wait
 * ({@link ConfiguredSender.Unavailable}) instead of falling back to the environment sender, so mail
 * never leaves from an address the admin replaced. The outbox worker is a background job, so this
 * lives in a {@code ..job..} package and uses a SYSTEM scope.
 *
 * <p>After such a failure the mailbox is not tried again for {@link #FIRST_WAIT}, doubling up to
 * {@link #MAX_WAIT}: no decrypt, no token request, no log line and no write every 15 s. Any change
 * to the mailbox's sending settings or secret ends the wait at once, so a fixed mailbox sends on
 * the next round.
 */
@Component
public class MailboxSender implements ConfiguredSender {

  static final Duration FIRST_WAIT = Duration.ofMinutes(1);
  static final Duration MAX_WAIT = Duration.ofMinutes(15);

  /** The system entry for recipients dropped by the reserved-domain guard. */
  public static final String NOT_EMAILED_KIND = "EMAIL_NOT_SENT";

  public static final String NOT_EMAILED_TEXT = "Not emailed: test address";

  private final MailboxRepository mailboxes;
  private final MailboxService service;
  private final InternalNoteService notes;
  private final Clock clock;

  /** The last failure, until the mailbox works again or its settings change. */
  private final AtomicReference<Failure> failure = new AtomicReference<>();

  private record Failure(
      long mailboxId, int settings, String category, Instant retryAt, Duration backoff) {}

  public MailboxSender(
      MailboxRepository mailboxes, MailboxService service, InternalNoteService notes, Clock clock) {
    this.notes = notes;
    this.mailboxes = mailboxes;
    this.service = service;
    this.clock = clock;
  }

  @Override
  public Optional<SmtpAccount> current() throws Unavailable {
    AccessScope scope = AccessScope.system("outbox sender");
    Optional<MailboxRow> found = mailboxes.sender(scope);
    if (found.isEmpty()) {
      failure.set(null);
      return Optional.empty();
    }
    MailboxRow m = found.get();
    int settings =
        settingsHash(
            m,
            mailboxes.secret(scope, m.id()).orElse(null),
            mailboxes.smtpSecret(scope, m.id()).orElse(null));
    Instant now = clock.instant();
    Failure last = failure.get();
    boolean same = last != null && last.mailboxId() == m.id() && last.settings() == settings;
    if (same && now.isBefore(last.retryAt())) {
      throw new Unavailable(m.id(), last.category(), true);
    }
    SmtpAccount account;
    try {
      account = service.smtpAccount(scope, m);
    } catch (MailboxAccessException e) {
      Duration wait = same ? min(last.backoff().multipliedBy(2), MAX_WAIT) : FIRST_WAIT;
      String category = e.category().label();
      failure.set(new Failure(m.id(), settings, category, now.plus(wait), wait));
      throw new Unavailable(m.id(), category);
    }
    failure.set(null);
    return Optional.of(account);
  }

  @Override
  public void recordResult(long mailboxId, String error) {
    mailboxes.recordSmtpResult(AccessScope.system("outbox sender"), mailboxId, error);
  }

  /** The hidden system entry for recipients on a reserved test domain. */
  @Override
  public void notEmailed(long ticketId) {
    notes.addSystemEntry(
        AccessScope.system("outbox sender"), ticketId, NOT_EMAILED_KIND, NOT_EMAILED_TEXT);
  }

  /**
   * Everything a send depends on; a change ends the back-off. Kept in memory only, never logged.
   */
  private static int settingsHash(
      MailboxRow m, DataCipher.Sealed secret, DataCipher.Sealed smtpSecret) {
    return Objects.hash(
        m.address(),
        m.smtpHost(),
        m.smtpPort(),
        m.smtpSecurity(),
        m.authType(),
        m.username(),
        m.oauthTokenUrl(),
        m.oauthClientId(),
        m.oauthScope(),
        secret == null ? null : secret.keyId(),
        secret == null ? 0 : Arrays.hashCode(secret.ciphertext()),
        m.smtpSameSignIn(),
        m.smtpUsername(),
        smtpSecret == null ? null : smtpSecret.keyId(),
        smtpSecret == null ? 0 : Arrays.hashCode(smtpSecret.ciphertext()));
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
