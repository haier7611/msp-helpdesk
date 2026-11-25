-- WP-04: tickets core - request types, tickets, links, participants, history, public comments,
-- internal notes, hidden system entries and attachments (spec sections 3, 4, 6, 8, 11a).
-- V21 so it sorts above WP-02c's V20 (merge order WP-03 -> WP-02c -> WP-04).
-- Public replies and internal notes are separate tables (ADR-009 C6, CLAUDE.md rule 7): there is
-- no shared body column with a visibility flag, so a query on one can never return the other.
-- Application role grants come from V5's default privileges.

-- Request types (admin CRUD is WP-11b). The type sets the starting priority (F6). Alert is hidden
-- from the customer portal (ADR-024).
CREATE TABLE request_type (
    id               BIGSERIAL    PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    default_priority VARCHAR(2)   NOT NULL CHECK (default_priority IN ('P1', 'P2', 'P3', 'P4')),
    portal_visible   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX request_type_name_lower_uk ON request_type (lower(name));

INSERT INTO request_type (name, default_priority, portal_visible, created_at) VALUES
    ('General', 'P3', TRUE, now()),
    ('Alert', 'P2', FALSE, now());

-- Tickets. organisation_id is NULL only while in the triage queue. SLA columns come in WP-09.
CREATE TABLE ticket (
    id                 BIGSERIAL    PRIMARY KEY,
    organisation_id    BIGINT       REFERENCES organisation (id),
    -- A customer account, or only an email address for triage mail from unknown senders.
    reporter_user_id   BIGINT       REFERENCES app_user (id),
    reporter_email     VARCHAR(320),
    assignee_user_id   BIGINT       REFERENCES app_user (id),
    request_type_id    BIGINT       NOT NULL REFERENCES request_type (id),
    priority           VARCHAR(2)   NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    impact             VARCHAR(16)  CHECK (impact IN ('JUST_ME', 'A_FEW_PEOPLE', 'WHOLE_OFFICE')),
    urgency            VARCHAR(16)  CHECK (urgency IN ('NUISANCE', 'PARTLY', 'WORK_STOPPED')),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('NEW', 'OPEN', 'PENDING_ON_CLIENT',
                                        'PENDING_ON_VENDOR', 'RESOLVED', 'CLOSED')),
    source             VARCHAR(8)   NOT NULL CHECK (source IN ('PORTAL', 'EMAIL', 'API', 'AGENT')),
    subject            VARCHAR(250) NOT NULL,
    external_id        VARCHAR(200),
    duplicate_of_id    BIGINT       REFERENCES ticket (id),
    follow_up_of_id    BIGINT       REFERENCES ticket (id),
    created_by_user_id BIGINT       REFERENCES app_user (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    status_changed_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ticket_not_duplicate_of_itself CHECK (duplicate_of_id IS NULL OR duplicate_of_id <> id),
    CONSTRAINT ticket_duplicate_is_closed CHECK (duplicate_of_id IS NULL OR status = 'CLOSED')
);

CREATE INDEX ticket_org_status_ix ON ticket (organisation_id, status, id DESC);
CREATE INDEX ticket_assignee_ix ON ticket (assignee_user_id) WHERE assignee_user_id IS NOT NULL;
CREATE INDEX ticket_triage_ix ON ticket (id DESC) WHERE organisation_id IS NULL;
CREATE INDEX ticket_external_ix ON ticket (organisation_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX ticket_duplicate_of_ix ON ticket (duplicate_of_id) WHERE duplicate_of_id IS NOT NULL;

-- References now point at real tickets. issued_ticket_id is the ticket a reference was first
-- issued to and never changes; ticket_id is where it resolves today (a duplicate's references are
-- re-pointed at the target, spec section 6).
ALTER TABLE ticket_reference ADD COLUMN issued_ticket_id BIGINT;
UPDATE ticket_reference SET issued_ticket_id = ticket_id;
ALTER TABLE ticket_reference
    ALTER COLUMN issued_ticket_id SET NOT NULL,
    ADD CONSTRAINT ticket_reference_ticket_fk FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    ADD CONSTRAINT ticket_reference_issued_ticket_fk
        FOREIGN KEY (issued_ticket_id) REFERENCES ticket (id);
CREATE INDEX ticket_reference_issued_ix ON ticket_reference (issued_ticket_id);

-- Links between two tickets of the same organisation, stored once per pair and kind with the
-- lower id first; read from either side ("both ways"). The direction of DUPLICATE and FOLLOW_UP
-- is on ticket.duplicate_of_id / ticket.follow_up_of_id.
CREATE TABLE ticket_link (
    id                 BIGSERIAL   PRIMARY KEY,
    organisation_id    BIGINT      NOT NULL REFERENCES organisation (id),
    low_ticket_id      BIGINT      NOT NULL REFERENCES ticket (id),
    high_ticket_id     BIGINT      NOT NULL REFERENCES ticket (id),
    kind               VARCHAR(16) NOT NULL CHECK (kind IN ('RELATED', 'DUPLICATE', 'FOLLOW_UP')),
    created_by_user_id BIGINT      REFERENCES app_user (id),
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ticket_link_ordered CHECK (low_ticket_id < high_ticket_id),
    CONSTRAINT ticket_link_uk UNIQUE (low_ticket_id, high_ticket_id, kind)
);

CREATE INDEX ticket_link_high_ix ON ticket_link (high_ticket_id);

-- People besides the reporter who receive client emails (the CC rules are WP-07). External
-- addresses need an agent's confirmation (confirmed = FALSE until then).
CREATE TABLE ticket_participant (
    id         BIGSERIAL    PRIMARY KEY,
    ticket_id  BIGINT       NOT NULL REFERENCES ticket (id),
    user_id    BIGINT       REFERENCES app_user (id),
    email      VARCHAR(320) NOT NULL CHECK (email = lower(email)),
    confirmed  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ticket_participant_uk UNIQUE (ticket_id, email)
);

-- Ticket history: status, priority, request type, assignee, organisation, links.
CREATE TABLE ticket_event (
    id            BIGSERIAL    PRIMARY KEY,
    ticket_id     BIGINT       NOT NULL REFERENCES ticket (id),
    type          VARCHAR(32)  NOT NULL,
    actor_user_id BIGINT       REFERENCES app_user (id),
    old_value     VARCHAR(200),
    new_value     VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ticket_event_ticket_ix ON ticket_event (ticket_id, id);

-- Public comments: a supporter's public reply or a client reply (portal description, email).
-- Sent public replies are immutable: the application has no update path for them.
CREATE TABLE public_comment (
    id                BIGSERIAL     PRIMARY KEY,
    ticket_id         BIGINT        NOT NULL REFERENCES ticket (id),
    author_kind       VARCHAR(16)   NOT NULL CHECK (author_kind IN ('SUPPORTER', 'CLIENT', 'API_KEY')),
    author_user_id    BIGINT        REFERENCES app_user (id),
    author_email      VARCHAR(320),
    body_html         TEXT          NOT NULL,
    received_by_email BOOLEAN       NOT NULL DEFAULT FALSE,
    message_id        VARCHAR(998),
    auth_results      VARCHAR(2000),
    created_at        TIMESTAMPTZ   NOT NULL
);

CREATE INDEX public_comment_ticket_ix ON public_comment (ticket_id, id);
CREATE UNIQUE INDEX public_comment_message_id_uk ON public_comment (message_id)
    WHERE message_id IS NOT NULL;

-- Internal notes: supporters only, never emailed to clients, never returned by portal or API-key
-- calls. A note can never become a public comment (no shared table, no conversion path).
CREATE TABLE internal_note (
    id                BIGSERIAL     PRIMARY KEY,
    ticket_id         BIGINT        NOT NULL REFERENCES ticket (id),
    author_kind       VARCHAR(16)   NOT NULL CHECK (author_kind IN ('SUPPORTER', 'SYSTEM')),
    author_user_id    BIGINT        REFERENCES app_user (id),
    body_html         TEXT          NOT NULL,
    received_by_email BOOLEAN       NOT NULL DEFAULT FALSE,
    message_id        VARCHAR(998),
    auth_results      VARCHAR(2000),
    created_at        TIMESTAMPTZ   NOT NULL
);

CREATE INDEX internal_note_ticket_ix ON internal_note (ticket_id, id);
CREATE UNIQUE INDEX internal_note_message_id_uk ON internal_note (message_id)
    WHERE message_id IS NOT NULL;

-- Hidden system entries (auto-replies stored at most as an entry, spec 6; automatic notes).
-- Supporters only.
CREATE TABLE system_entry (
    id         BIGSERIAL     PRIMARY KEY,
    ticket_id  BIGINT        NOT NULL REFERENCES ticket (id),
    kind       VARCHAR(32)   NOT NULL,
    body_text  VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX system_entry_ticket_ix ON system_entry (ticket_id, id);

-- Attachments (C6). Uploaded first as PENDING, owned by the uploader, expiring after 24 h unless
-- a comment binds them; a bound attachment takes the visibility of its comment. public_id is the
-- id in URLs; storage_key / thumbnail_key are BlobStore keys (UUIDs, never derived from input).
CREATE TABLE attachment (
    id                BIGSERIAL    PRIMARY KEY,
    public_id         UUID         NOT NULL UNIQUE,
    storage_key       UUID         NOT NULL UNIQUE,
    thumbnail_key     UUID         UNIQUE,
    uploader_user_id  BIGINT       NOT NULL REFERENCES app_user (id),
    filename          VARCHAR(255) NOT NULL,
    size_bytes        BIGINT       NOT NULL CHECK (size_bytes >= 0 AND size_bytes <= 20971520),
    content_type      VARCHAR(100) NOT NULL,
    is_image          BOOLEAN      NOT NULL,
    inline            BOOLEAN      NOT NULL DEFAULT FALSE,
    status            VARCHAR(8)   NOT NULL CHECK (status IN ('PENDING', 'BOUND')),
    ticket_id         BIGINT       REFERENCES ticket (id),
    public_comment_id BIGINT       REFERENCES public_comment (id),
    internal_note_id  BIGINT       REFERENCES internal_note (id),
    created_at        TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ,
    CONSTRAINT attachment_bound_to_one_comment CHECK (
        (status = 'PENDING' AND ticket_id IS NULL AND public_comment_id IS NULL
            AND internal_note_id IS NULL AND expires_at IS NOT NULL)
        OR (status = 'BOUND' AND ticket_id IS NOT NULL AND expires_at IS NULL
            AND ((public_comment_id IS NULL) <> (internal_note_id IS NULL))))
);

CREATE INDEX attachment_public_comment_ix ON attachment (public_comment_id) WHERE public_comment_id IS NOT NULL;
CREATE INDEX attachment_internal_note_ix ON attachment (internal_note_id) WHERE internal_note_id IS NOT NULL;
CREATE INDEX attachment_pending_ix ON attachment (expires_at) WHERE status = 'PENDING';
