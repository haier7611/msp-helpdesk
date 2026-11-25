package com.ticketsystem.api.mail.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.mail.inbound.BlockListMatcher.BlockMatch;
import com.ticketsystem.api.mail.inbound.PlusAddress.MailboxTarget;
import com.ticketsystem.api.mail.intake.InboundRouter.Decision;
import com.ticketsystem.api.mail.intake.InboundRouter.Facts;
import com.ticketsystem.api.mail.intake.InboundRouter.ReplyMode;
import com.ticketsystem.api.mail.intake.InboundRouter.RuleMatch;
import com.ticketsystem.api.mail.intake.InboundRouter.SenderHistory;
import com.ticketsystem.api.mail.intake.InboundRouter.SenderKind;
import com.ticketsystem.api.mail.intake.InboundRouter.TicketMatch;
import com.ticketsystem.api.mail.intake.InboundRouter.TriageReason;
import com.ticketsystem.api.security.SecurityGoLive;
import com.ticketsystem.api.sla.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every routing outcome of spec 4 flow 2, 6 and 11b as pure logic. */
@SecurityGoLive({5})
class InboundRouterTest {

  static final long ACME = 10L;
  static final long BETA = 20L;

  /** A builder over the Facts record, defaulting to "known contact, DMARC pass, new mail". */
  static final class F {
    boolean unparseable;
    BlockMatch block;
    MailboxTarget target = MailboxTarget.SUPPORT;
    boolean dmarc = true;
    boolean automated;
    TicketMatch match;
    boolean refNotFound;
    SenderKind kind = SenderKind.CONTACT;
    Long supporter;
    Long contact = 5L;
    Long contactOrg = ACME;
    ReplyMode mode = ReplyMode.INTERNAL_ONLY;
    // This mail's own authentication class ...
    int recent;
    Long latest;
    boolean latestTriage;
    // ... and the other class (DMARC pass vs not) of mail from the same address.
    SenderHistory other = SenderHistory.NONE;
    RuleMatch rule;
    Long domainOrg;

    Decision decide() {
      SenderHistory own = new SenderHistory(recent, latest, latestTriage);
      return InboundRouter.decide(
          new Facts(
              unparseable,
              block,
              target,
              dmarc,
              automated,
              match,
              refNotFound,
              kind,
              supporter,
              contact,
              contactOrg,
              mode,
              dmarc ? own : other,
              dmarc ? other : own,
              rule,
              domainOrg));
    }

    F supporter() {
      kind = SenderKind.SUPPORTER;
      supporter = 7L;
      contact = null;
      contactOrg = null;
      return this;
    }

    F unknown() {
      kind = SenderKind.UNKNOWN;
      contact = null;
      contactOrg = null;
      return this;
    }

    F onTicket(Long org, boolean participant, boolean supporterAccess) {
      match = new TicketMatch(100L, org, TicketStatus.OPEN, false, participant, supporterAccess);
      return this;
    }
  }

  static Decision.Triage triage(TriageReason r) {
    return new Decision.Triage(r);
  }

  @Test
  void blockedThenUnparseableComeFirst() {
    F f = new F();
    f.unparseable = true;
    // A mail too large to parse whose From header is blocked stays blocked (WP-07b, AUD-13 Low).
    f.block = new BlockMatch(BlockMatch.Kind.DOMAIN, "spam.example");
    assertThat(f.decide()).isInstanceOf(Decision.Block.class);
    f.block = null;
    assertThat(f.decide()).isEqualTo(triage(TriageReason.UNPARSEABLE));
    f.unparseable = false;
    f.block = new BlockMatch(BlockMatch.Kind.DOMAIN, "spam.example");
    assertThat(f.decide()).isInstanceOf(Decision.Block.class);
    // A block also applies to replies on existing tickets (spec 11b).
    f.onTicket(ACME, true, false);
    assertThat(f.decide()).isInstanceOf(Decision.Block.class);
  }

  @Test
  void participantReplyWithDmarcPassIsAClientReply() {
    assertThat(new F().onTicket(ACME, true, false).decide())
        .isEqualTo(new Decision.ClientReply(100L));
  }

  @Test
  void securityTest5Guards() {
    // Non-participant reply is not attached.
    F nonParticipant = new F().unknown().onTicket(ACME, false, false);
    assertThat(nonParticipant.decide()).isEqualTo(triage(TriageReason.NOT_A_PARTICIPANT));
    // A participant failing DMARC goes to triage.
    F spoofed = new F().onTicket(ACME, true, false);
    spoofed.dmarc = false;
    assertThat(spoofed.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
    // Unknown sender on a verified domain without DMARC creates nothing (triage only).
    F domain = new F().unknown();
    domain.domainOrg = ACME;
    domain.dmarc = false;
    assertThat(domain.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
    // Auto-replies on a ticket become a hidden entry, never a reply.
    F auto = new F().onTicket(ACME, true, false);
    auto.automated = true;
    assertThat(auto.decide()).isEqualTo(new Decision.AutoReplyEntry(100L));
  }

  @Test
  void aContactQuotingAnotherOrganisationsReferenceGoesToTriage() {
    F f = new F().onTicket(BETA, false, false);
    assertThat(f.decide()).isEqualTo(triage(TriageReason.REFERENCE_OTHER_ORGANISATION));
  }

  @Test
  void aContactOfAnotherOrganisationWhoIsAnOutsideParticipantRepliesOnThatTicket() {
    // ADR-050 change 2026-11-24: a confirmed outside CC of that ticket, like any outside address.
    F f = new F().onTicket(BETA, true, false);
    assertThat(f.decide()).isEqualTo(new Decision.ClientReply(100L));
    f.dmarc = false;
    assertThat(f.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
  }

  @Test
  void anUnknownReferenceGoesToTriage() {
    F f = new F();
    f.refNotFound = true;
    assertThat(f.decide()).isEqualTo(triage(TriageReason.REFERENCE_NOT_FOUND));
  }

  @ParameterizedTest
  @EnumSource(ReplyMode.class)
  void supporterReplyToTheInternalAddress(ReplyMode mode) {
    F f = new F().supporter().onTicket(ACME, false, true);
    f.target = MailboxTarget.INTERNAL;
    f.mode = mode;
    Decision d = f.decide();
    if (mode == ReplyMode.OFF) {
      assertThat(d).isEqualTo(triage(TriageReason.SUPPORTER_REPLIES_OFF));
    } else {
      assertThat(d).isEqualTo(new Decision.InternalNote(100L, 7L));
    }
  }

  @ParameterizedTest
  @EnumSource(ReplyMode.class)
  void supporterReplyToAClientThread(ReplyMode mode) {
    F f = new F().supporter().onTicket(ACME, false, true);
    f.mode = mode;
    Decision d = f.decide();
    switch (mode) {
      case OFF -> assertThat(d).isEqualTo(triage(TriageReason.SUPPORTER_REPLIES_OFF));
      case INTERNAL_ONLY ->
          assertThat(d).isEqualTo(triage(TriageReason.SUPPORTER_PUBLIC_REPLIES_OFF));
      case NOTES_AND_PUBLIC -> assertThat(d).isEqualTo(new Decision.PublicReply(100L, 7L));
    }
  }

  @Test
  void aReplyThreadedOntoAnInternalNotificationIsNeverPublic() {
    F f = new F().supporter();
    f.match = new TicketMatch(100L, ACME, TicketStatus.OPEN, true, false, true);
    f.mode = ReplyMode.NOTES_AND_PUBLIC;
    assertThat(f.decide()).isEqualTo(new Decision.InternalNote(100L, 7L));
  }

  @Test
  void supporterGuards() {
    F noAccess = new F().supporter().onTicket(BETA, false, false);
    noAccess.target = MailboxTarget.INTERNAL;
    assertThat(noAccess.decide()).isEqualTo(triage(TriageReason.NOT_A_SUPPORTER));
    F noDmarc = new F().supporter().onTicket(ACME, false, true);
    noDmarc.target = MailboxTarget.INTERNAL;
    noDmarc.dmarc = false;
    assertThat(noDmarc.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
    F closed = new F().supporter();
    closed.match = new TicketMatch(100L, ACME, TicketStatus.CLOSED, false, false, true);
    closed.mode = ReplyMode.NOTES_AND_PUBLIC;
    assertThat(closed.decide()).isEqualTo(triage(TriageReason.TICKET_CLOSED));
  }

  @Test
  void theInternalAddressFromANonSupporterGoesToTriage() {
    F withTicket = new F().onTicket(ACME, true, false);
    withTicket.target = MailboxTarget.INTERNAL;
    assertThat(withTicket.decide()).isEqualTo(triage(TriageReason.NOT_A_SUPPORTER));
    F noTicket = new F();
    noTicket.target = MailboxTarget.INTERNAL;
    assertThat(noTicket.decide()).isEqualTo(triage(TriageReason.NOT_A_SUPPORTER));
  }

  @Test
  void newMailRouting() {
    assertThat(new F().decide()).isEqualTo(new Decision.Create(ACME, 5L, false, null));
    F domain = new F().unknown();
    domain.domainOrg = ACME;
    assertThat(domain.decide()).isEqualTo(new Decision.Create(ACME, null, true, null));
    assertThat(new F().unknown().decide()).isEqualTo(triage(TriageReason.UNKNOWN_SENDER));
    F rule = new F().unknown();
    rule.rule = new RuleMatch(BETA, 2L);
    assertThat(rule.decide()).isEqualTo(new Decision.Create(BETA, null, false, 2L));
    rule.dmarc = false;
    assertThat(rule.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
    F contactNoDmarc = new F();
    contactNoDmarc.dmarc = false;
    assertThat(contactNoDmarc.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
  }

  @Test
  void the21stNewMailInAnHourFolds() {
    F f = new F();
    f.recent = 19;
    f.latest = 55L;
    assertThat(f.decide()).isInstanceOf(Decision.Create.class);
    f.recent = 20;
    assertThat(f.decide()).isEqualTo(new Decision.Fold(55L));
  }

  @Test
  void anUnauthenticatedFloodNeverFoldsIntoAClientTicket() {
    F f = new F();
    f.recent = 25;
    f.latest = 55L;
    f.dmarc = false;
    assertThat(f.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
    f.latestTriage = true;
    assertThat(f.decide()).isEqualTo(new Decision.Fold(55L));
  }

  @Test
  void twentyForgedMailsDoNotCaptureTheGenuineSendersNextMail() {
    // AUD-13 M1: 20 DMARC-failing mails "from" a client address made 20 triage tickets; the real
    // client's next DMARC-pass mail is counted in its own class and opens a normal ticket.
    F genuine = new F();
    genuine.other = new SenderHistory(20, 66L, true);
    assertThat(genuine.decide()).isEqualTo(new Decision.Create(ACME, 5L, false, null));
  }

  @Test
  void aGenuineFloodNeverLetsForgedMailFoldIntoTheClientsTicket() {
    F forged = new F();
    forged.dmarc = false;
    forged.other = new SenderHistory(25, 55L, false);
    assertThat(forged.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
  }

  @Test
  void aSupportersAutomaticReplyIsAHiddenEntryNeverANote() {
    F f = new F().supporter().onTicket(ACME, false, true);
    f.target = MailboxTarget.INTERNAL;
    f.automated = true;
    assertThat(f.decide()).isEqualTo(new Decision.AutoReplyEntry(100L));
    f.mode = ReplyMode.NOTES_AND_PUBLIC;
    f.target = MailboxTarget.SUPPORT;
    assertThat(f.decide()).isEqualTo(new Decision.AutoReplyEntry(100L));
    // Forged (DMARC-failing) still goes to triage first.
    f.dmarc = false;
    assertThat(f.decide()).isEqualTo(triage(TriageReason.NOT_AUTHENTICATED));
  }
}
