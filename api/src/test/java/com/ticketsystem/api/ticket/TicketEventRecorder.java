package com.ticketsystem.api.ticket;

import com.ticketsystem.api.ticket.event.TicketEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.event.TransactionalEventListener;

/** Records ticket events as an after-commit listener would see them (WP-07/WP-09 consumers). */
public class TicketEventRecorder {

  private final List<TicketEvent> events = new ArrayList<>();

  @TransactionalEventListener
  public synchronized void on(TicketEvent event) {
    events.add(event);
  }

  public synchronized List<TicketEvent> forTicket(long ticketId) {
    return events.stream().filter(e -> e.ticketId() == ticketId).toList();
  }

  public synchronized List<TicketEvent> all() {
    return List.copyOf(events);
  }

  public synchronized void clear() {
    events.clear();
  }
}
