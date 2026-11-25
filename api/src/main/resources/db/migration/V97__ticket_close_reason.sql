-- ADR-041: closing a ticket needs a reason; admins can reopen a closed ticket.
--
-- ticket.close_reason / close_note: the reason of the ticket's CURRENT close (CloseReason name; the
-- note only for OTHER). Set on every close, cleared when an admin reopens it. The CSV export and
-- reports read it from here without walking the history. Older closed tickets stay NULL (no
-- backfill: the reason was never asked). TicketRepository clears both on any status other than
-- Closed, in the same UPDATE.
--
-- ticket_event.close_reason / note: the same values on the STATUS row of each close, so the agent
-- timeline keeps every close ("Closed by <agent>: <reason>") even after a reopen and a second
-- close. ticket_event.note also holds an admin's reopen reason (ADMIN_REOPEN status row). Both are
-- agent-only (timeline); old_value/new_value stay ids and codes (200 characters), so the note has
-- its own column. Free text written by agents, like an internal note: retention deletes it with
-- the ticket (RetentionRepository already deletes ticket_event rows).
ALTER TABLE ticket
    ADD COLUMN close_reason VARCHAR(32)
        CHECK (close_reason IN ('DUPLICATE', 'SPAM', 'RESOLVED_NO_FOLLOW_UP', 'CLIENT_CANCELLED',
                                'NO_RESPONSE', 'OTHER', 'AUTO_CLOSED_NO_REPLY')),
    ADD COLUMN close_note   VARCHAR(500),
    ADD CONSTRAINT ticket_close_note_only_for_other
        CHECK (close_note IS NULL OR close_reason = 'OTHER');

ALTER TABLE ticket_event
    ADD COLUMN close_reason VARCHAR(32)
        CHECK (close_reason IN ('DUPLICATE', 'SPAM', 'RESOLVED_NO_FOLLOW_UP', 'CLIENT_CANCELLED',
                                'NO_RESPONSE', 'OTHER', 'AUTO_CLOSED_NO_REPLY')),
    ADD COLUMN note         VARCHAR(500);
