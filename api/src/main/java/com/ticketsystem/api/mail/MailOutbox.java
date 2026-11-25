package com.ticketsystem.api.mail;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way to send email. {@link #enqueue} writes an outbox row inside the caller's transaction
 * (it refuses to run without one), so an email exists exactly when the business change that caused
 * it commits. After the commit the worker is nudged and sends it within seconds; the worker's poll
 * picks up anything the nudge missed.
 */
@Service
public class MailOutbox {

  private final OutboxRepository repository;
  private final MailSettings settings;
  private final SupportName supportName;
  private final ApplicationEventPublisher events;

  MailOutbox(
      OutboxRepository repository,
      MailSettings settings,
      SupportName supportName,
      ApplicationEventPublisher events) {
    this.repository = repository;
    this.settings = settings;
    this.supportName = supportName;
    this.events = events;
  }

  /** Stores the email for sending after the surrounding transaction commits. */
  @Transactional(propagation = Propagation.MANDATORY)
  public QueuedEmail enqueue(OutboundEmail email) {
    String messageId = "<" + UUID.randomUUID() + "@" + settings.messageIdDomain() + ">";
    String fromName = email.fromName() == null ? supportName.get() : email.fromName();
    long id = repository.insert(email, messageId, fromName, email.fullSubject());
    events.publishEvent(new OutboxMessageQueued(id));
    return new QueuedEmail(id, messageId);
  }

  /**
   * The ticket an inbound reply threads onto through {@code In-Reply-To} / {@code References} (spec
   * 6 step 2): the first of {@code messageIds} (In-Reply-To first, then References newest first)
   * that is one of our own outbox messages for a ticket.
   */
  @Transactional(readOnly = true)
  public Optional<ThreadMatch> findThread(List<String> messageIds) {
    List<String> ids =
        messageIds.stream().filter(id -> id != null && id.length() <= 255).limit(50).toList();
    Map<String, OutboxRepository.ThreadRow> rows = repository.threadRows(ids);
    for (String id : ids) {
      OutboxRepository.ThreadRow row = rows.get(id);
      if (row != null) {
        return Optional.of(new ThreadMatch(row.ticketId(), row.kind()));
      }
    }
    return Optional.empty();
  }

  /** Message-IDs of client-facing mail for a ticket, newest first (threading headers). */
  @Transactional(readOnly = true)
  public List<String> clientThreadMessageIds(long ticketId, int limit) {
    return repository.clientThreadMessageIds(ticketId, limit);
  }

  /** An inbound reply's thread: our message's ticket, and whether that message was agent-only. */
  public record ThreadMatch(long ticketId, MailKind kind) {}

  /** The stored row id and the Message-ID the email will carry (for threading). */
  public record QueuedEmail(long id, String messageId) {}

  /** Published inside the enqueuing transaction; acted on only after it commits. */
  record OutboxMessageQueued(long id) {}
}
