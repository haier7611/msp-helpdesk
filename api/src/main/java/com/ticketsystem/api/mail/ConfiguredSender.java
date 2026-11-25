package com.ticketsystem.api.mail;

import java.util.Optional;

/**
 * The admin-configured outbound mailbox, looked up by the outbox worker at the start of every
 * round. Implemented by the mail intake module, which owns the mailbox table and its encrypted
 * credentials. When a mailbox is configured it sends every kind of mail: ticket mail and account
 * and security mail ({@link MailKind#SYSTEM}); the server-wide sender is the fallback only when no
 * mailbox is configured for sending.
 */
public interface ConfiguredSender {

  /**
   * The account to send with, or empty when no mailbox is configured for sending.
   *
   * @throws Unavailable when a mailbox is configured but cannot be used right now (its secret no
   *     longer decrypts, the OAuth2 token request failed); mail then stays queued - it never falls
   *     back to the environment sender
   */
  Optional<SmtpAccount> current() throws Unavailable;

  /** Records the outcome of a round on the mailbox: {@code error} null for success. */
  void recordResult(long mailboxId, String error);

  /**
   * Notes on the ticket that some recipients were not emailed because they are on a reserved test
   * domain: a hidden system entry "Not emailed: test address".
   */
  void notEmailed(long ticketId);

  /**
   * A configured mailbox that cannot send now; {@code category} is short and safe to log. {@code
   * backingOff} is true when the mailbox failed recently and is not retried yet: that failure was
   * already logged and recorded, so the worker stays quiet.
   */
  final class Unavailable extends Exception {
    private static final long serialVersionUID = 1L;

    private final long mailboxId;
    private final String category;
    private final boolean backingOff;

    public Unavailable(long mailboxId, String category) {
      this(mailboxId, category, false);
    }

    public Unavailable(long mailboxId, String category, boolean backingOff) {
      super(category, null, false, false);
      this.mailboxId = mailboxId;
      this.category = category;
      this.backingOff = backingOff;
    }

    public boolean backingOff() {
      return backingOff;
    }

    public long mailboxId() {
      return mailboxId;
    }

    public String category() {
      return category;
    }
  }
}
