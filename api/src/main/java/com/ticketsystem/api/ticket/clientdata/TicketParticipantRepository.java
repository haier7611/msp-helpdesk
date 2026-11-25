package com.ticketsystem.api.ticket.clientdata;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.access.ScopeSql;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ActorKind;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.Extra;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.HistoryType;
import com.ticketsystem.api.ticket.clientdata.TicketHistoryRepository.ParticipantReason;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_participant}: who besides the reporter gets client emails. The CC rules that add
 * them live elsewhere; this class stores them, copies them for duplicates and follow-ups, and
 * re-checks them when a ticket changes organisation. Every participant added or removed here is
 * also a ticket history row (agent-only timeline line).
 */
@Repository
public class TicketParticipantRepository {

  public record ParticipantRow(Long userId, String email, boolean confirmed) {}

  private final JdbcClient jdbc;
  private final TicketHistoryRepository history;

  public TicketParticipantRepository(JdbcClient jdbc, TicketHistoryRepository history) {
    this.jdbc = jdbc;
    this.history = history;
  }

  /**
   * Adds a participant (lower-cased email); no-op when already there. Used by email intake (To/Cc
   * of an email): the history row says so ({@link ParticipantReason#EMAIL_CC} or {@link
   * ParticipantReason#EMAIL_CC_UNCONFIRMED}). Returns whether the participant was added.
   */
  public boolean add(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      Long userId,
      String email,
      boolean confirmed,
      Instant now) {
    return add(
        scope,
        organisationId,
        ticketId,
        userId,
        email,
        confirmed,
        now,
        confirmed ? ParticipantReason.EMAIL_CC : ParticipantReason.EMAIL_CC_UNCONFIRMED);
  }

  /** As above with the reason kept in the ticket history. */
  public boolean add(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      Long userId,
      String email,
      boolean confirmed,
      Instant now,
      ParticipantReason reason) {
    TicketScopes.requireWritable(scope, organisationId);
    boolean added =
        ScopeSql.bind(
                    jdbc.sql(
                            "INSERT INTO ticket_participant (ticket_id, user_id, email, confirmed,"
                                + " created_at) SELECT :t, :u, lower(:e), :c, :now WHERE "
                                + ScopeSql.ticketVisible(scope, ":t")
                                + " ON CONFLICT ON CONSTRAINT ticket_participant_uk DO NOTHING")
                        .param("t", ticketId)
                        .param("u", userId)
                        .param("e", email)
                        .param("c", confirmed)
                        .param("now", Timestamp.from(now)),
                    scope)
                .update()
            == 1;
    if (!added) {
      requireVisible(scope, ticketId);
    }
    if (added) {
      recordParticipant(
          scope,
          organisationId,
          ticketId,
          HistoryType.PARTICIPANT_ADDED,
          userId,
          email,
          reason,
          now);
    }
    return added;
  }

  /**
   * An agent confirms an unconfirmed participant (an outside address from an email) in the reply
   * composer; a history row says so. Returns whether a row changed.
   */
  public boolean confirm(
      AccessScope scope, long organisationId, long ticketId, String email, Instant now) {
    scope.require(organisationId);
    boolean changed = confirmRow(scope, ticketId, email);
    if (changed) {
      recordParticipant(
          scope,
          organisationId,
          ticketId,
          HistoryType.PARTICIPANT_ADDED,
          null,
          email,
          ParticipantReason.AGENT_CONFIRMED_EXTERNAL,
          now);
    }
    return changed;
  }

  /**
   * An unconfirmed participant is CC'd again on a verified (DMARC pass) client email and becomes
   * confirmed; a history row ({@link ParticipantReason#EMAIL_CC_CONFIRMED}, actor CLIENT when the
   * poller writes it). {@code organisationId} null for a triage ticket. Returns whether a row
   * changed.
   */
  public boolean confirmFromEmail(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      Long userId,
      String email,
      Instant now) {
    TicketScopes.requireWritable(scope, organisationId);
    boolean changed = confirmRow(scope, ticketId, email);
    if (changed) {
      recordParticipant(
          scope,
          organisationId,
          ticketId,
          HistoryType.PARTICIPANT_ADDED,
          userId,
          email,
          ParticipantReason.EMAIL_CC_CONFIRMED,
          now);
    }
    return changed;
  }

  /**
   * An agent removes a participant in the reply composer; a history row with {@code reason}.
   * Returns whether a row was removed.
   */
  public boolean remove(
      AccessScope scope,
      long organisationId,
      long ticketId,
      String email,
      Instant now,
      ParticipantReason reason) {
    scope.require(organisationId);
    List<Long> removed =
        ScopeSql.bind(
                jdbc.sql(
                        "DELETE FROM ticket_participant p WHERE p.ticket_id = :t AND p.email ="
                            + " lower(:e) AND "
                            + ScopeSql.ticketVisible(scope, "p.ticket_id")
                            + " RETURNING COALESCE(p.user_id, 0)")
                    .param("t", ticketId)
                    .param("e", email),
                scope)
            .query(Long.class)
            .list();
    if (removed.isEmpty()) {
      requireVisible(scope, ticketId);
      return false;
    }
    Long userId = removed.get(0) == 0L ? null : removed.get(0);
    recordParticipant(
        scope,
        organisationId,
        ticketId,
        HistoryType.PARTICIPANT_REMOVED,
        userId,
        email,
        reason,
        now);
    return true;
  }

  /**
   * A history row for a one-reply-only CC change ({@link ParticipantReason#REPLY_ONLY} and {@link
   * ParticipantReason#REPLY_ONLY_EXTERNAL}); the participants do not change.
   */
  public void recordReplyOnly(
      AccessScope scope,
      long organisationId,
      long ticketId,
      boolean added,
      Long userId,
      String email,
      ParticipantReason reason,
      Instant now) {
    scope.require(organisationId);
    recordParticipant(
        scope,
        organisationId,
        ticketId,
        added ? HistoryType.PARTICIPANT_ADDED : HistoryType.PARTICIPANT_REMOVED,
        userId,
        email,
        reason,
        now);
  }

  public List<ParticipantRow> listForTicket(AccessScope scope, long ticketId) {
    return ScopeSql.bind(
            jdbc.sql(
                    "SELECT p.user_id, p.email, p.confirmed FROM ticket_participant p JOIN ticket"
                        + " t ON t.id = p.ticket_id WHERE p.ticket_id = :t AND "
                        + ScopeSql.organisationOrTriage(scope, "t.organisation_id")
                        + " ORDER BY p.id")
                .param("t", ticketId),
            scope)
        .query(
            (rs, n) ->
                new ParticipantRow(
                    rs.getObject("user_id", Long.class),
                    rs.getString("email"),
                    rs.getBoolean("confirmed")))
        .list();
  }

  /**
   * Copies {@code fromTicket}'s participants to {@code toTicket} (duplicate, follow-up); both in
   * {@code organisationId}. With {@code reason} (a duplicate merge), each participant actually
   * added is a history row on {@code toTicket}; a follow-up passes null (the follow-up line covers
   * it).
   */
  public int copy(
      AccessScope scope,
      long organisationId,
      long fromTicket,
      long toTicket,
      Instant now,
      ParticipantReason reason) {
    scope.require(organisationId);
    List<ParticipantRow> added =
        ScopeSql.bind(
                jdbc.sql(
                        "INSERT INTO ticket_participant (ticket_id, user_id, email, confirmed,"
                            + " created_at) SELECT :to, p.user_id, p.email, p.confirmed, :now FROM"
                            + " ticket_participant p JOIN ticket t ON t.id = p.ticket_id WHERE"
                            + " p.ticket_id = :from AND t.organisation_id = :org AND "
                            + ScopeSql.organisation(scope, "t.organisation_id")
                            + " AND "
                            + ScopeSql.ticketInOrganisation(scope, ":to", "org")
                            + " ON CONFLICT ON CONSTRAINT ticket_participant_uk DO NOTHING"
                            + " RETURNING user_id, email, confirmed")
                    .param("to", toTicket)
                    .param("from", fromTicket)
                    .param("org", organisationId)
                    .param("now", Timestamp.from(now)),
                scope)
            .query(
                (rs, n) ->
                    new ParticipantRow(
                        rs.getObject("user_id", Long.class),
                        rs.getString("email"),
                        rs.getBoolean("confirmed")))
            .list();
    if (added.isEmpty()
        && !ScopeSql.isTicketInOrganisation(jdbc, scope, toTicket, organisationId)) {
      throw AccessScope.notFound();
    }
    if (reason != null) {
      for (ParticipantRow p : added) {
        recordParticipant(
            scope,
            organisationId,
            toTicket,
            HistoryType.PARTICIPANT_ADDED,
            p.userId(),
            p.email(),
            reason,
            now);
      }
    }
    return added.size();
  }

  /**
   * Re-check after a move to {@code newOrganisationId} : participants who are customer accounts of
   * another organisation are removed (each one a history row); email-only participants must be
   * confirmed again by an agent before they get mail. Returns the number removed.
   */
  public int recheckAfterMove(
      AccessScope scope, long ticketId, long newOrganisationId, Instant now) {
    scope.require(newOrganisationId);
    requireVisible(scope, ticketId);
    List<Long> removed =
        ScopeSql.bind(
                jdbc.sql(
                        "DELETE FROM ticket_participant p USING app_user u WHERE p.ticket_id = :t"
                            + " AND u.id = p.user_id AND (u.role <> 'CUSTOMER' OR"
                            + " u.organisation_id <> :org) AND "
                            + ScopeSql.ticketVisible(scope, "p.ticket_id")
                            + " RETURNING p.user_id")
                    .param("t", ticketId)
                    .param("org", newOrganisationId),
                scope)
            .query(Long.class)
            .list();
    ScopeSql.bind(
            jdbc.sql(
                    "UPDATE ticket_participant p SET confirmed = FALSE WHERE p.ticket_id = :t AND"
                        + " p.user_id IS NULL AND "
                        + ScopeSql.ticketVisible(scope, "p.ticket_id"))
                .param("t", ticketId),
            scope)
        .update();
    for (Long userId : removed) {
      recordParticipant(
          scope,
          newOrganisationId,
          ticketId,
          HistoryType.PARTICIPANT_REMOVED,
          userId,
          null,
          ParticipantReason.ORGANISATION_MOVED,
          now);
    }
    return removed.size();
  }

  /**
   * An admin moved customer {@code userId} to {@code newOrganisationId}. The same rule as a ticket
   * move (participants are contacts of the ticket's organisation) is applied from the other side:
   * they stop being a participant of every ticket of another organisation, by account or by their
   * address ({@code email}, may be null), each removal a history row with {@link
   * ParticipantReason#CONTACT_MOVED}. Triage tickets (no organisation yet) are left alone;
   * assigning one re-checks its participants. Admin only. Returns the number of tickets changed.
   */
  public int removeMovedContact(
      AccessScope scope, long userId, String email, long newOrganisationId, Instant now) {
    scope.requireAdmin();
    List<long[]> removed =
        jdbc.sql(
                "DELETE FROM ticket_participant p USING ticket t WHERE t.id = p.ticket_id AND"
                    + " t.organisation_id IS NOT NULL AND t.organisation_id <> :org AND"
                    + " (p.user_id = :u OR (CAST(:e AS VARCHAR) IS NOT NULL AND p.email ="
                    + " lower(CAST(:e AS VARCHAR)))) RETURNING p.ticket_id, t.organisation_id")
            .param("org", newOrganisationId)
            .param("u", userId)
            .param("e", email)
            .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
            .list();
    Map<Long, Long> tickets = new LinkedHashMap<>();
    for (long[] r : removed) {
      tickets.putIfAbsent(r[0], r[1]);
    }
    tickets.forEach(
        (ticketId, organisationId) ->
            recordParticipant(
                scope,
                organisationId,
                ticketId,
                HistoryType.PARTICIPANT_REMOVED,
                userId,
                null,
                ParticipantReason.CONTACT_MOVED,
                now));
    return tickets.size();
  }

  /**
   * Confirms an unconfirmed participant of a ticket the scope sees; false when there was nothing to
   * confirm, 404 when the ticket is missing or not visible.
   */
  private boolean confirmRow(AccessScope scope, long ticketId, String email) {
    boolean changed =
        ScopeSql.bind(
                    jdbc.sql(
                            "UPDATE ticket_participant p SET confirmed = TRUE WHERE p.ticket_id ="
                                + " :t AND p.email = lower(:e) AND NOT p.confirmed AND "
                                + ScopeSql.ticketVisible(scope, "p.ticket_id"))
                        .param("t", ticketId)
                        .param("e", email),
                    scope)
                .update()
            == 1;
    if (!changed) {
      requireVisible(scope, ticketId);
    }
    return changed;
  }

  /**
   * 404 when the ticket is missing or the scope does not see it (after a write changed nothing).
   */
  private void requireVisible(AccessScope scope, long ticketId) {
    if (!ScopeSql.isTicketVisible(jdbc, scope, ticketId)) {
      throw AccessScope.notFound();
    }
  }

  /**
   * The participant's user id goes in {@code reason_ref_id}; only an email-only participant's
   * address is stored ({@code new_value}, scrubbed by erasure).
   */
  private void recordParticipant(
      AccessScope scope,
      Long organisationId,
      long ticketId,
      HistoryType type,
      Long userId,
      String email,
      ParticipantReason reason,
      Instant now) {
    boolean byEmail =
        reason == ParticipantReason.EMAIL_CC
            || reason == ParticipantReason.EMAIL_CC_UNCONFIRMED
            || reason == ParticipantReason.EMAIL_CC_CONFIRMED;
    Extra extra = Extra.reason(reason).withRef(userId);
    if (byEmail && scope.isSystem()) {
      // The poller adds the To/Cc of a client's email: the client's doing, not a job's.
      extra = extra.withActor(ActorKind.CLIENT);
    }
    history.record(
        scope,
        organisationId,
        ticketId,
        type,
        scope.isSupporter() ? scope.principalId().orElse(null) : null,
        null,
        userId == null && email != null ? email.toLowerCase(java.util.Locale.ROOT) : null,
        now,
        extra);
  }
}
