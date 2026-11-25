-- WP-02a: transactional outbox for every outbound email (spec section 8 "Outbox").
-- Business code inserts a row in its own transaction (MailOutbox.enqueue); OutboxWorker claims
-- due rows with FOR UPDATE SKIP LOCKED, sends them over SMTP and records the result.
CREATE TABLE outbox_message (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Generated at enqueue as <uuid@domain>; inbound threading (WP-07, ADR-014) matches
    -- In-Reply-To / References against it.
    message_id      VARCHAR(255) NOT NULL,
    kind            VARCHAR(20)  NOT NULL,
    -- No foreign key yet: the ticket table arrives in WP-04, which adds the constraint.
    ticket_id       BIGINT,
    -- Bare addresses, comma separated, validated at enqueue.
    to_addresses    TEXT         NOT NULL,
    cc_addresses    TEXT,
    from_name       VARCHAR(255) NOT NULL,
    reply_to        VARCHAR(320),
    subject         VARCHAR(998) NOT NULL,
    html_body       TEXT         NOT NULL,
    text_body       TEXT         NOT NULL,
    -- Extra headers (e.g. In-Reply-To, References), one "Name: value" per line, validated at
    -- enqueue (no CR/LF in values, reserved names refused).
    extra_headers   TEXT         NOT NULL DEFAULT '',
    status          VARCHAR(10)  NOT NULL DEFAULT 'queued',
    -- Sensitive mail (invitation, reset, magic link) holds a live token: its bodies are blanked
    -- once the row is sent or failed, and it is never sent after expires_at.
    sensitive       BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at      TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    -- Short reason only (SMTP code or exception type), never server text, bodies or secrets.
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT outbox_message_message_id_key UNIQUE (message_id),
    CONSTRAINT outbox_message_kind_check CHECK (kind IN ('system', 'client_facing', 'internal')),
    CONSTRAINT outbox_message_status_check CHECK (status IN ('queued', 'sending', 'sent', 'failed')),
    CONSTRAINT outbox_message_attempts_check CHECK (attempts >= 0)
);

-- The worker's claim query: due queued rows in order.
CREATE INDEX outbox_message_due_idx ON outbox_message (next_attempt_at, id) WHERE status = 'queued';
-- Stale-claim recovery: rows left in 'sending' by a crash.
CREATE INDEX outbox_message_sending_idx ON outbox_message (claimed_at) WHERE status = 'sending';
-- Ticket thread lookups (WP-07/WP-09).
CREATE INDEX outbox_message_ticket_idx ON outbox_message (ticket_id) WHERE ticket_id IS NOT NULL;
