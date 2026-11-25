-- ADR-049: SLA automation rules defined by admins (v1), spec 4.8.
--
-- sla_automation_rule: one rule = a trigger, optional conditions and one or more actions, run in
-- position order when the SLA evaluation stores a state change. conditions and actions are JSON
-- written and validated by SlaAutomationAdminService only (ids, codes and the admin's fixed note
-- text; never ticket content). Organisation, request type and agent ids inside them are not
-- foreign keys on purpose: when an organisation is deleted its id stays in the rule and simply
-- never matches again, so a rule never widens to "any organisation" by losing its last condition.
--
-- sla_automation_firing: the record that a rule fired for a ticket, a target and a trigger. It is
-- inserted first, in the same transaction (savepoint) as the rule's actions, and the unique key
-- makes a second firing a no-op - so restarts, recalculations and a priority raise by the rule
-- itself never repeat it. Deleting a rule deletes its firing rows; the ticket timeline and the
-- audit log keep its history. Deleting a ticket (retention) deletes its rows.
CREATE TABLE sla_automation_rule (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order INTEGER      NOT NULL,
    trigger_type VARCHAR(32) NOT NULL
        CHECK (trigger_type IN ('FIRST_RESPONSE_AT_RISK', 'FIRST_RESPONSE_BREACHED',
                           'FIRST_RESPONSE_MET', 'RESOLUTION_AT_RISK', 'RESOLUTION_BREACHED',
                           'RESOLUTION_MET')),
    conditions JSONB        NOT NULL DEFAULT '{}'::jsonb,
    actions    JSONB        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX sla_automation_rule_order_ix ON sla_automation_rule (sort_order, id);

CREATE TABLE sla_automation_firing (
    id        BIGSERIAL   PRIMARY KEY,
    rule_id   BIGINT      NOT NULL REFERENCES sla_automation_rule (id) ON DELETE CASCADE,
    ticket_id BIGINT      NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    target    VARCHAR(16) NOT NULL CHECK (target IN ('FIRST_RESPONSE', 'RESOLUTION')),
    trigger_type VARCHAR(32) NOT NULL,
    fired_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT sla_automation_firing_uk UNIQUE (rule_id, ticket_id, target, trigger_type)
);

CREATE INDEX sla_automation_firing_ticket_ix ON sla_automation_firing (ticket_id);
