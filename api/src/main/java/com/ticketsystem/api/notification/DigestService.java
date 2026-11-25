package com.ticketsystem.api.notification;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.clientdata.DigestRepository;
import com.ticketsystem.api.notification.clientdata.DigestRepository.DigestTicket;
import com.ticketsystem.api.notification.clientdata.SupporterDirectoryRepository.SupporterRow;
import com.ticketsystem.api.reference.ReferenceService;
import com.ticketsystem.api.reference.TicketReference;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds and queues one supporter's daily digest: their open tickets and the at- risk or breached
 * tickets of organisations they can see, read through their own scope.
 */
@Service
public class DigestService {

  static final int MAX_LINES = 30;

  private final DigestRepository digests;
  private final ReferenceService references;
  private final SupporterMailer mailer;
  private final Clock clock;

  public DigestService(
      DigestRepository digests, ReferenceService references, SupporterMailer mailer, Clock clock) {
    this.digests = digests;
    this.references = references;
    this.mailer = mailer;
    this.clock = clock;
  }

  /**
   * Sends the digest of {@code localDate} unless it was already sent.
   *
   * @param system the job's scope (for the once-a-day log)
   * @param own the supporter's own scope (for the content)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean send(AccessScope system, AccessScope own, SupporterRow s, LocalDate localDate) {
    if (!digests.claim(system, s.userId(), localDate, clock.instant())) {
      return false;
    }
    List<DigestTicket> rows = digests.openAndBreaching(own, MAX_LINES + 1);
    Map<Long, TicketReference> refs =
        references.displayReferences(own, rows.stream().map(DigestTicket::id).toList());
    long mine = rows.stream().filter(DigestTicket::mine).count();
    long breaching = rows.stream().filter(r -> !"NONE".equals(r.slaWarning())).count();
    String summary =
        (rows.size() > MAX_LINES ? "More than " + MAX_LINES : "" + rows.size())
            + " tickets to look at: "
            + mine
            + " open and assigned to you, "
            + breaching
            + " about to breach or breached.";
    StringBuilder items = new StringBuilder();
    for (DigestTicket t : rows.subList(0, Math.min(rows.size(), MAX_LINES))) {
      TicketReference ref = refs.get(t.id());
      items
          .append(ref == null ? "#" + t.id() : ref.toString())
          .append(" - ")
          .append(t.priority())
          .append(" - ")
          .append(label(t.slaWarning()))
          .append(t.mine() ? " - yours" : "")
          .append(" - ")
          .append(t.subject())
          .append('\n');
    }
    mailer.sendDigest(system, s.email(), s.name(), summary, items.toString().strip());
    return true;
  }

  private static String label(String warning) {
    return switch (warning) {
      case "BREACHED" -> "Breached";
      case "AT_RISK" -> "About to breach";
      default -> "Open";
    };
  }
}
