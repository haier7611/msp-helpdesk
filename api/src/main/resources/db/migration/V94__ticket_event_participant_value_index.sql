-- AUD-38 Low (hardening batch 1): ts_erase_user (V90, V92) clears an erased person's address from
-- the participant timeline lines with
--   UPDATE ticket_event SET new_value = NULL
--    WHERE type IN ('PARTICIPANT_ADDED', 'PARTICIPANT_REMOVED') AND lower(new_value) = v_email;
-- which scanned the whole ticket_event table on every erasure. This partial expression index holds
-- only the participant rows (a small share of the table) keyed on lower(new_value), so the UPDATE
-- becomes an index lookup and every other ticket_event insert pays nothing. The WHERE clause
-- matches the function's predicate exactly, so the planner can prove the index applies.
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs it in the migration transaction and the table
-- is small on the single demo instance.
CREATE INDEX ticket_event_participant_value_ix
    ON ticket_event (lower(new_value))
    WHERE type IN ('PARTICIPANT_ADDED', 'PARTICIPANT_REMOVED');
