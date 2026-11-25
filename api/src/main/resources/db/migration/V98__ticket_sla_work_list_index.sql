-- ADR-047 Decision 2: the minute job's "needs (re)calculating" work list now also covers triage
-- tickets (organisation_id IS NULL), including a never-calculated Closed triage ticket, so the V61
-- partial index ticket_sla_stale_ix (organisation_id IS NOT NULL AND status <> 'CLOSED' AND ...)
-- can no longer serve it and the query would scan the whole ticket table every minute.
--
-- This partial index's predicate is written exactly as TicketSlaRepository.STALE_WORK_LIST_SQL's
-- WHERE clause, so the planner proves it trivially; TicketSlaWorkListIndexIntegrationTest fails if
-- the two drift apart. It holds only open tickets changed since their last calculation plus
-- never-calculated triage tickets, so it stays small. ticket_sla_stale_ix had no other user and is
-- dropped.
CREATE INDEX ticket_sla_work_ix ON ticket (id)
    WHERE (status <> 'CLOSED' AND (sla_calculated_at IS NULL OR sla_calculated_at <= updated_at))
       OR (organisation_id IS NULL AND sla_calculated_at IS NULL);
DROP INDEX ticket_sla_stale_ix;
