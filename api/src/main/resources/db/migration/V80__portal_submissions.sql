-- WP-06: the customer portal's duplicate-submit guard (ADR-018, spec 4 flow 1). The New ticket page
-- makes one random key per form; a second POST with the same key (double click, retry on a slow
-- network) answers with the first ticket's reference instead of raising a second ticket.
-- WP-06 owns V80-V84 (ADR-026 Flyway blocks). Application role grants come from V5's default
-- privileges.
--
-- The row is inserted first (ticket_id still NULL) with ON CONFLICT DO NOTHING, so a concurrent
-- second request waits on the unique index until the first commits and then reads its ticket.
CREATE TABLE portal_submission (
    id              BIGSERIAL   PRIMARY KEY,
    organisation_id BIGINT      NOT NULL REFERENCES organisation (id),
    user_id         BIGINT      NOT NULL REFERENCES app_user (id),
    idempotency_key UUID        NOT NULL,
    ticket_id       BIGINT      REFERENCES ticket (id),
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT portal_submission_uk UNIQUE (user_id, idempotency_key)
);

CREATE INDEX portal_submission_created_ix ON portal_submission (created_at);
