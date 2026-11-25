-- WP-16 (ADR-032): why an assignee changed, for the agent-only assignment lines in the ticket
-- timeline. Set only on ASSIGNEE rows written from this version on; older rows stay NULL (no
-- backfill). reason is a stored enum name (TicketHistoryRepository.AssignmentReason, append-only);
-- reason_ref_id is the organisation (DEFAULT_ORGANISATION, ORGANISATION_MOVED) or request type
-- (DEFAULT_REQUEST_TYPE) the reason refers to, or NULL. Ids only, never names.
ALTER TABLE ticket_event
    ADD COLUMN reason        VARCHAR(32),
    ADD COLUMN reason_ref_id BIGINT;
