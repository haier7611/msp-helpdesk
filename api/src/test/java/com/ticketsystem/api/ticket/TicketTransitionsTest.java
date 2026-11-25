package com.ticketsystem.api.ticket;

import static com.ticketsystem.api.sla.TicketStatus.CLOSED;
import static com.ticketsystem.api.sla.TicketStatus.NEW;
import static com.ticketsystem.api.sla.TicketStatus.OPEN;
import static com.ticketsystem.api.sla.TicketStatus.PENDING_ON_CLIENT;
import static com.ticketsystem.api.sla.TicketStatus.PENDING_ON_VENDOR;
import static com.ticketsystem.api.sla.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.api.auth.ApiException;
import com.ticketsystem.api.sla.TicketStatus;
import com.ticketsystem.api.ticket.TicketTransitions.Trigger;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Every (from, to) pair of the status table in spec section 3, legal and illegal. */
class TicketTransitionsTest {

  /** The expected agent table, written out independently of the implementation. */
  private static final Map<TicketStatus, Set<TicketStatus>> AGENT_MAY_SET =
      Map.of(
          NEW, EnumSet.of(NEW, OPEN, PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED),
          OPEN, EnumSet.of(OPEN, PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED),
          PENDING_ON_CLIENT, EnumSet.of(PENDING_ON_CLIENT, OPEN, PENDING_ON_VENDOR, RESOLVED),
          PENDING_ON_VENDOR, EnumSet.of(PENDING_ON_VENDOR, OPEN, PENDING_ON_CLIENT, RESOLVED),
          RESOLVED, EnumSet.of(RESOLVED, OPEN, CLOSED),
          CLOSED, EnumSet.noneOf(TicketStatus.class));

  @Test
  void everyAgentTransitionIsAllowedOrRefusedAsSpecified() {
    int checked = 0;
    for (TicketStatus from : TicketStatus.values()) {
      for (TicketStatus to : TicketStatus.values()) {
        boolean expected = AGENT_MAY_SET.get(from).contains(to);
        assertThat(TicketTransitions.agentMaySet(from, to))
            .as(from + " -> " + to)
            .isEqualTo(expected);
        if (expected) {
          assertThat(TicketTransitions.next(from, Trigger.AGENT_SET, to)).isEqualTo(to);
        } else {
          assertThatThrownBy(() -> TicketTransitions.next(from, Trigger.AGENT_SET, to))
              .as(from + " -> " + to)
              .isInstanceOfSatisfying(
                  ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
        checked++;
      }
    }
    assertThat(checked).isEqualTo(36);
  }

  @Test
  void nothingEverMovesBackToNewAndClosedIsReadOnly() {
    for (TicketStatus from : EnumSet.complementOf(EnumSet.of(NEW))) {
      assertThat(TicketTransitions.agentMaySet(from, NEW)).isFalse();
    }
    for (TicketStatus to : TicketStatus.values()) {
      assertThat(TicketTransitions.agentMaySet(CLOSED, to)).isFalse();
    }
  }

  @Test
  void agentSetWithoutAStatusIsABadRequest() {
    assertThatThrownBy(() -> TicketTransitions.next(OPEN, Trigger.AGENT_SET, null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void pickUpAndPublicReplyMoveNewToOpenOnly() {
    for (Trigger t : new Trigger[] {Trigger.PICK_UP, Trigger.SUPPORTER_PUBLIC_REPLY}) {
      assertThat(TicketTransitions.next(NEW, t, null)).isEqualTo(OPEN);
      for (TicketStatus s : EnumSet.of(OPEN, PENDING_ON_CLIENT, PENDING_ON_VENDOR, RESOLVED)) {
        assertThat(TicketTransitions.next(s, t, null)).as(t + " on " + s).isEqualTo(s);
      }
      assertClosedRefused(t);
    }
  }

  @Test
  void clientReplyReopensPendingAndResolvedTickets() {
    assertThat(TicketTransitions.next(PENDING_ON_CLIENT, Trigger.CLIENT_REPLY, null))
        .isEqualTo(OPEN);
    assertThat(TicketTransitions.next(PENDING_ON_VENDOR, Trigger.CLIENT_REPLY, null))
        .isEqualTo(OPEN);
    assertThat(TicketTransitions.next(RESOLVED, Trigger.CLIENT_REPLY, null)).isEqualTo(OPEN);
    assertThat(TicketTransitions.next(NEW, Trigger.CLIENT_REPLY, null)).isEqualTo(NEW);
    assertThat(TicketTransitions.next(OPEN, Trigger.CLIENT_REPLY, null)).isEqualTo(OPEN);
    assertClosedRefused(Trigger.CLIENT_REPLY);
  }

  @Test
  void markDuplicateClosesAnyOpenTicket() {
    for (TicketStatus s : EnumSet.complementOf(EnumSet.of(CLOSED))) {
      assertThat(TicketTransitions.next(s, Trigger.MARK_DUPLICATE, null)).isEqualTo(CLOSED);
    }
    assertClosedRefused(Trigger.MARK_DUPLICATE);
  }

  @Test
  void jobTriggersOnlyFromTheirOneStatus() {
    for (TicketStatus s : TicketStatus.values()) {
      if (s == PENDING_ON_CLIENT) {
        assertThat(TicketTransitions.next(s, Trigger.AUTO_RESOLVE, null)).isEqualTo(RESOLVED);
      } else {
        assertThatThrownBy(() -> TicketTransitions.next(s, Trigger.AUTO_RESOLVE, null))
            .as("auto-resolve from " + s)
            .isInstanceOf(ApiException.class);
      }
      if (s == RESOLVED) {
        assertThat(TicketTransitions.next(s, Trigger.AUTO_CLOSE, null)).isEqualTo(CLOSED);
      } else {
        assertThatThrownBy(() -> TicketTransitions.next(s, Trigger.AUTO_CLOSE, null))
            .as("auto-close from " + s)
            .isInstanceOf(ApiException.class);
      }
    }
  }

  @Test
  void adminReopenMovesOnlyClosedToOpen() {
    assertThat(TicketTransitions.next(CLOSED, Trigger.ADMIN_REOPEN, null)).isEqualTo(OPEN);
    for (TicketStatus s : EnumSet.complementOf(EnumSet.of(CLOSED))) {
      assertThatThrownBy(() -> TicketTransitions.next(s, Trigger.ADMIN_REOPEN, null))
          .as(s.name())
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo("not_closed"));
    }
  }

  @Test
  void labelsAreThePlainEnglishNames() {
    assertThat(TicketTransitions.label(PENDING_ON_CLIENT)).isEqualTo("Pending on client");
    assertThat(TicketTransitions.label(PENDING_ON_VENDOR)).isEqualTo("Pending on vendor");
  }

  private static void assertClosedRefused(Trigger t) {
    assertThatThrownBy(() -> TicketTransitions.next(CLOSED, t, null))
        .as(t + " on Closed")
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo("ticket_closed");
            });
  }
}
