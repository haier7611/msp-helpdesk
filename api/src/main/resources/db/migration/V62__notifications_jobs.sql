-- WP-09: in-app notifications (bell), the daily digest log and the
-- database lock that keeps scheduled jobs to a single runner (spec sections 4.6, 4.7, 7; ADR-013,
-- ADR-024). Out of office, the digest choice, timezones and per-event notification choices are
-- WP-11a's (V40: supporter_preferences, supporter_notification). Application role grants come
-- from V5's default privileges.

-- One row per notification and recipient. No ticket content is stored (subject and reference are
-- read through the recipient's AccessScope when listed), so a supporter who loses access to an
-- organisation stops seeing its notifications. dedupe_key makes repeated deliveries of the same
-- event a no-op.
CREATE TABLE notification (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    organisation_id BIGINT       REFERENCES organisation (id) ON DELETE CASCADE,
    ticket_id       BIGINT       REFERENCES ticket (id) ON DELETE CASCADE,
    kind            VARCHAR(32)  NOT NULL,
    detail          VARCHAR(32),
    dedupe_key      VARCHAR(120),
    created_at      TIMESTAMPTZ  NOT NULL,
    read_at         TIMESTAMPTZ
);

CREATE INDEX notification_user_ix ON notification (user_id, id DESC);
CREATE INDEX notification_unread_ix ON notification (user_id) WHERE read_at IS NULL;
CREATE INDEX notification_ticket_ix ON notification (ticket_id);
CREATE UNIQUE INDEX notification_dedupe_uk ON notification (user_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;

-- One daily digest per supporter per local date (ADR-024: 08:00 in their own timezone).
CREATE TABLE notification_digest_log (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    local_date DATE        NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, local_date)
);

-- Single-runner lock for scheduled jobs (ShedLock-style): a runner takes the row when the lease
-- has run out and gives it back when done. Leases use the database clock, never the
-- application's, so a test clock cannot leave a lock held.
CREATE TABLE scheduled_job_lock (
    name         VARCHAR(64)  PRIMARY KEY,
    locked_until TIMESTAMPTZ  NOT NULL,
    locked_at    TIMESTAMPTZ  NOT NULL,
    locked_by    VARCHAR(100) NOT NULL
);
