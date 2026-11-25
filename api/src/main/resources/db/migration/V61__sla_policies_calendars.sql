-- WP-09: SLA policies per organisation and request type, business-hours calendars and holidays per
-- organisation, per-organisation timer settings and default assignee, request-type default
-- assignee, and the SLA columns stored on each ticket (spec sections 3, 4.6, 4.7, 7, 11a; ADR-012,
-- ADR-013, ADR-017). Numbered V61-V62 (renumbered from V50-V51 after WP-10 merged V60, ADR-026).
-- Application role grants come from V5's default privileges.

-- Per-organisation timers (spec 3 and 11a), API alert client emails (spec 3, default off) and the
-- organisation's default assignee (spec 4.6). One row per organisation; a missing row reads as
-- these defaults.
CREATE TABLE organisation_sla_settings (
    organisation_id                BIGINT      PRIMARY KEY REFERENCES organisation (id) ON DELETE CASCADE,
    pending_reminder_enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    pending_reminder_business_days INTEGER     NOT NULL DEFAULT 3
        CHECK (pending_reminder_business_days BETWEEN 1 AND 60),
    auto_resolve_enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    auto_resolve_business_days     INTEGER     NOT NULL DEFAULT 7
        CHECK (auto_resolve_business_days BETWEEN 1 AND 120),
    auto_close_enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    auto_close_calendar_days       INTEGER     NOT NULL DEFAULT 5
        CHECK (auto_close_calendar_days BETWEEN 1 AND 90),
    api_alert_client_emails        BOOLEAN     NOT NULL DEFAULT FALSE,
    default_assignee_user_id       BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at                     TIMESTAMPTZ NOT NULL
);

-- Weekly business hours (ISO day 1 = Monday). closes_at 00:00 means "until midnight". An
-- organisation without rows uses Monday-Friday 08:30-17:30 (spec 4.7).
CREATE TABLE organisation_business_hours (
    organisation_id BIGINT   NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    day_of_week     SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at        TIME     NOT NULL,
    closes_at       TIME     NOT NULL,
    PRIMARY KEY (organisation_id, day_of_week),
    CONSTRAINT organisation_business_hours_order
        CHECK (closes_at = TIME '00:00' OR closes_at > opens_at)
);

CREATE TABLE organisation_holiday (
    organisation_id BIGINT       NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    holiday_date    DATE         NOT NULL,
    name            VARCHAR(100) NOT NULL,
    PRIMARY KEY (organisation_id, holiday_date)
);

-- One default policy per organisation (request_type_id NULL) plus optional per-request-type
-- overrides (spec 7 "SLA policies: targets per request type and priority").
CREATE TABLE sla_policy (
    id                       BIGSERIAL   PRIMARY KEY,
    organisation_id          BIGINT      NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    request_type_id          BIGINT      REFERENCES request_type (id) ON DELETE CASCADE,
    pending_on_vendor_pauses BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX sla_policy_org_type_uk ON sla_policy (organisation_id, COALESCE(request_type_id, 0));

CREATE TABLE sla_policy_target (
    policy_id             BIGINT      NOT NULL REFERENCES sla_policy (id) ON DELETE CASCADE,
    priority              VARCHAR(2)  NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    first_response_amount INTEGER     NOT NULL CHECK (first_response_amount BETWEEN 1 AND 100000),
    first_response_unit   VARCHAR(8)  NOT NULL CHECK (first_response_unit IN ('MINUTES', 'HOURS', 'DAYS')),
    resolution_amount     INTEGER     NOT NULL CHECK (resolution_amount BETWEEN 1 AND 100000),
    resolution_unit       VARCHAR(8)  NOT NULL CHECK (resolution_unit IN ('MINUTES', 'HOURS', 'DAYS')),
    clock                 VARCHAR(20) NOT NULL CHECK (clock IN ('TWENTY_FOUR_SEVEN', 'BUSINESS_HOURS')),
    PRIMARY KEY (policy_id, priority),
    -- ADR-017: "24/7 for P1 always".
    CONSTRAINT sla_policy_target_p1_always_247 CHECK (priority <> 'P1' OR clock = 'TWENTY_FOUR_SEVEN')
);

-- Request-type default assignee (spec 4.6: the request type's default wins over the organisation's).
ALTER TABLE request_type
    ADD COLUMN default_assignee_user_id BIGINT REFERENCES app_user (id) ON DELETE SET NULL;

-- Seed the ADR-017 defaults for every existing organisation (new ones are seeded by the
-- application when they are created).
INSERT INTO organisation_sla_settings (organisation_id, updated_at)
SELECT id, now() FROM organisation;

INSERT INTO organisation_business_hours (organisation_id, day_of_week, opens_at, closes_at)
SELECT o.id, d, TIME '08:30', TIME '17:30' FROM organisation o CROSS JOIN generate_series(1, 5) AS d;

INSERT INTO sla_policy (organisation_id, request_type_id, pending_on_vendor_pauses, created_at, updated_at)
SELECT id, NULL, FALSE, now(), now() FROM organisation;

INSERT INTO sla_policy_target (policy_id, priority, first_response_amount, first_response_unit,
                               resolution_amount, resolution_unit, clock)
SELECT p.id, t.priority, t.fr_amount, t.fr_unit, t.res_amount, t.res_unit, t.clock
FROM sla_policy p
CROSS JOIN (VALUES
    ('P1', 30, 'MINUTES', 4, 'HOURS', 'TWENTY_FOUR_SEVEN'),
    ('P2', 1, 'HOURS', 8, 'HOURS', 'BUSINESS_HOURS'),
    ('P3', 4, 'HOURS', 3, 'DAYS', 'BUSINESS_HOURS'),
    ('P4', 1, 'DAYS', 10, 'DAYS', 'BUSINESS_HOURS')
) AS t (priority, fr_amount, fr_unit, res_amount, res_unit, clock);

-- SLA values stored on each ticket (spec 4.7 "Stored on each ticket"); internal only, never in a
-- customer response. States are the WP-09a TargetState names. Due times are empty while paused or
-- stopped before the target (ADR-017 change 2026-11-18). warn_at is when 80% will be used if the
-- clock keeps running; sla_next_check_at is the earliest instant the minute job must look again
-- (80% or breach) without any event. The *_notified_at columns make each 80% and breach crossing
-- notify exactly once (cleared again only when a recalculation drops below the level).
ALTER TABLE ticket
    ADD COLUMN sla_clock                          VARCHAR(20)
        CHECK (sla_clock IN ('TWENTY_FOUR_SEVEN', 'BUSINESS_HOURS')),
    ADD COLUMN sla_policy_fallback                BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN first_response_target_minutes      BIGINT,
    ADD COLUMN first_response_due_at              TIMESTAMPTZ,
    ADD COLUMN first_response_warn_at             TIMESTAMPTZ,
    ADD COLUMN first_responded_at                 TIMESTAMPTZ,
    ADD COLUMN first_response_used_minutes        BIGINT,
    ADD COLUMN first_response_state               VARCHAR(8)
        CHECK (first_response_state IN ('RUNNING', 'PAUSED', 'STOPPED', 'MET', 'BREACHED')),
    ADD COLUMN resolution_target_minutes          BIGINT,
    ADD COLUMN resolution_due_at                  TIMESTAMPTZ,
    ADD COLUMN resolution_warn_at                 TIMESTAMPTZ,
    ADD COLUMN resolved_at                        TIMESTAMPTZ,
    ADD COLUMN resolution_used_minutes            BIGINT,
    ADD COLUMN resolution_state                   VARCHAR(8)
        CHECK (resolution_state IN ('RUNNING', 'PAUSED', 'STOPPED', 'MET', 'BREACHED')),
    ADD COLUMN sla_paused_minutes                 BIGINT,
    ADD COLUMN sla_warning                        VARCHAR(8)  NOT NULL DEFAULT 'NONE'
        CHECK (sla_warning IN ('NONE', 'AT_RISK', 'BREACHED')),
    ADD COLUMN sla_calculated_at                  TIMESTAMPTZ,
    ADD COLUMN sla_next_check_at                  TIMESTAMPTZ,
    ADD COLUMN first_response_at_risk_notified_at TIMESTAMPTZ,
    ADD COLUMN first_response_breach_notified_at  TIMESTAMPTZ,
    ADD COLUMN resolution_at_risk_notified_at     TIMESTAMPTZ,
    ADD COLUMN resolution_breach_notified_at      TIMESTAMPTZ,
    -- P1 alert to every supporter (at creation, or when raised to P1), once.
    ADD COLUMN p1_alerted_at                      TIMESTAMPTZ,
    -- Auto-assignment (spec 4.6) runs once per ticket; NULL = not yet checked.
    ADD COLUMN auto_assign_checked_at             TIMESTAMPTZ,
    -- Pending-on-client reminder for the current Pending spell (earlier than status_changed_at =
    -- an earlier spell, so a new one may remind again).
    ADD COLUMN pending_reminder_sent_at           TIMESTAMPTZ;

-- Tickets that existed before WP-09 are never auto-assigned retroactively.
UPDATE ticket SET auto_assign_checked_at = now();

-- The minute job's work list: tickets whose SLA must be (re)calculated or whose next 80%/breach
-- instant has come.
CREATE INDEX ticket_sla_next_check_ix ON ticket (sla_next_check_at)
    WHERE sla_next_check_at IS NOT NULL AND status <> 'CLOSED';
CREATE INDEX ticket_sla_stale_ix ON ticket (id)
    WHERE organisation_id IS NOT NULL AND status <> 'CLOSED'
      AND (sla_calculated_at IS NULL OR sla_calculated_at <= updated_at);
CREATE INDEX ticket_auto_assign_ix ON ticket (id) WHERE auto_assign_checked_at IS NULL;
CREATE INDEX ticket_status_changed_ix ON ticket (status, status_changed_at)
    WHERE status IN ('PENDING_ON_CLIENT', 'RESOLVED');
CREATE INDEX ticket_created_ix ON ticket (created_at);
CREATE INDEX ticket_resolved_ix ON ticket (resolved_at) WHERE resolved_at IS NOT NULL;
