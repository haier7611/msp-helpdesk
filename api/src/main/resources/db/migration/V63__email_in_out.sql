-- WP-07: email in and out wired to tickets (spec sections 3, 4 flow 2, 5, 6, 8, 9, 11b; ADR-005,
-- ADR-009 C9-C11, ADR-014, ADR-015, ADR-020, ADR-022). Renumbered V25 -> V55 -> V63 so it applies after
-- WP-09's V62 (ADR-026 renumber rule); the lead renames it again at merge time if main is higher.
-- Application role grants come from V5's default privileges.

-- Admin-configured IMAP mailboxes (ADR-020). The admin screen is WP-11b; this item stores the
-- settings and polls them. The password or OAuth2 client secret is AES-256-GCM ciphertext from
-- DataCipher (spec 9/10), bound to the mailbox id, with the id of the key that made it; it is
-- never returned by any endpoint or logged. uid_validity + last_uid track what the poller has
-- processed (C11): last_uid moves in the same transaction as the mail it covers.
CREATE TABLE mailbox (
    id                   BIGSERIAL    PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    -- The support address, e.g. support@helpdesk.example (plain local@domain, no plus tag).
    address              VARCHAR(320) NOT NULL CHECK (address = lower(address) AND address NOT LIKE '%+%'),
    enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    imap_host            VARCHAR(253) NOT NULL,
    imap_port            INTEGER      NOT NULL CHECK (imap_port BETWEEN 1 AND 65535),
    imap_security        VARCHAR(8)   NOT NULL CHECK (imap_security IN ('TLS', 'STARTTLS', 'NONE')),
    imap_folder          VARCHAR(200) NOT NULL DEFAULT 'INBOX',
    auth_type            VARCHAR(8)   NOT NULL CHECK (auth_type IN ('PASSWORD', 'OAUTH2')),
    username             VARCHAR(320) NOT NULL,
    secret_ciphertext    BYTEA,
    secret_key_id        VARCHAR(32),
    -- OAuth2 client credentials (Microsoft 365 IMAP.AccessAsApp): token endpoint, client id, scope.
    oauth_token_url      VARCHAR(500),
    oauth_client_id      VARCHAR(200),
    oauth_scope          VARCHAR(500),
    -- authserv-ids of our own receiving server whose Authentication-Results we trust (WP-07a),
    -- comma separated; '(none)' is AuthenticationResultsParser.NO_AUTHSERV_ID for Microsoft 365.
    trusted_authserv_ids VARCHAR(1000) NOT NULL DEFAULT '',
    uid_validity         BIGINT,
    last_uid             BIGINT       NOT NULL DEFAULT 0 CHECK (last_uid >= 0),
    last_poll_at         TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    -- A short category only ("login failed", "token request failed"), never server text or secrets.
    last_error           VARCHAR(200),
    consecutive_failures INTEGER      NOT NULL DEFAULT 0,
    next_poll_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT mailbox_secret_complete CHECK ((secret_ciphertext IS NULL) = (secret_key_id IS NULL)),
    CONSTRAINT mailbox_oauth_complete CHECK (
        auth_type <> 'OAUTH2' OR (oauth_token_url IS NOT NULL AND oauth_client_id IS NOT NULL
            AND oauth_scope IS NOT NULL))
);

CREATE UNIQUE INDEX mailbox_address_uk ON mailbox (address);

-- Installation-wide email settings (one row). supporter_reply_mode is the admin's three-way
-- "Supporter replies by email" setting (spec 6, ADR-015): OFF / INTERNAL_ONLY (default) /
-- NOTES_AND_PUBLIC. Changed only with step-up (endpoint here, screen in WP-11b).
CREATE TABLE email_settings (
    id                   SMALLINT    PRIMARY KEY CHECK (id = 1),
    supporter_reply_mode VARCHAR(20) NOT NULL DEFAULT 'INTERNAL_ONLY'
        CHECK (supporter_reply_mode IN ('OFF', 'INTERNAL_ONLY', 'NOTES_AND_PUBLIC')),
    updated_by_user_id   BIGINT      REFERENCES app_user (id),
    updated_at           TIMESTAMPTZ NOT NULL
);

INSERT INTO email_settings (id, supporter_reply_mode, updated_at) VALUES (1, 'INTERNAL_ONLY', now());

-- The inbound mail log (spec 8): one row per mail read, with the outcome. The unique Message-ID
-- (C11, partial: DUPLICATE rows may repeat it) means a mail delivered twice is processed once - a
-- second poller's insert conflicts and its whole transaction rolls back; (mailbox, UIDVALIDITY,
-- UID) records where it came from. A repeat delivery gets a DUPLICATE row without raw content, so
-- it can still be found by sender. Mail without a Message-ID gets a synthetic one from the SHA-256
-- of its bytes. raw_eml is the original message (a mail over 30 MB: only its first 256 KB),
-- cleared after 90 days (blocked mail: the whole row after 30 days, the Blocked mail log of 11b).
CREATE TABLE inbound_mail (
    id                 BIGSERIAL    PRIMARY KEY,
    mailbox_id         BIGINT       REFERENCES mailbox (id) ON DELETE SET NULL,
    uid_validity       BIGINT,
    uid                BIGINT,
    message_id         VARCHAR(998) NOT NULL,
    raw_sha256         CHAR(64)     NOT NULL,
    raw_size           BIGINT       NOT NULL CHECK (raw_size >= 0),
    raw_eml            BYTEA,
    raw_purge_after    TIMESTAMPTZ  NOT NULL,
    from_address       VARCHAR(320),
    subject            VARCHAR(250),
    received_at        TIMESTAMPTZ  NOT NULL,
    outcome            VARCHAR(16)  NOT NULL CHECK (outcome IN ('CREATED', 'TRIAGE', 'CLIENT_REPLY',
                           'SYSTEM_ENTRY', 'NOTE', 'PUBLIC_REPLY', 'FOLDED', 'BLOCKED', 'PROBE',
                           'DUPLICATE')),
    reason             VARCHAR(40),
    ticket_id          BIGINT       REFERENCES ticket (id),
    -- This mail opened a new ticket (counts towards the per-sender 20-per-hour cap).
    new_ticket         BOOLEAN      NOT NULL DEFAULT FALSE,
    -- DMARC pass from a trusted authserv-id. The 20-per-hour cap and the fold-in target are counted
    -- per authentication class, so forged mail never shares a bucket with the real sender's.
    authenticated      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Blocked mail only: the rule that matched, and the restore (spec 11b).
    block_rule_kind    VARCHAR(8)   CHECK (block_rule_kind IN ('ADDRESS', 'DOMAIN')),
    block_rule         VARCHAR(320),
    restored_at        TIMESTAMPTZ,
    restored_by_user_id BIGINT      REFERENCES app_user (id),
    CONSTRAINT inbound_mail_uid_uk UNIQUE (mailbox_id, uid_validity, uid),
    CONSTRAINT inbound_mail_blocked_has_rule
        CHECK ((outcome = 'BLOCKED') = (block_rule IS NOT NULL))
);

CREATE UNIQUE INDEX inbound_mail_message_id_uk ON inbound_mail (message_id)
    WHERE outcome <> 'DUPLICATE';
CREATE INDEX inbound_mail_sender_rate_ix ON inbound_mail (from_address, authenticated, received_at)
    WHERE new_ticket;
CREATE INDEX inbound_mail_purge_ix ON inbound_mail (raw_purge_after) WHERE raw_eml IS NOT NULL;
CREATE INDEX inbound_mail_blocked_ix ON inbound_mail (received_at DESC) WHERE outcome = 'BLOCKED';
CREATE INDEX inbound_mail_ticket_ix ON inbound_mail (ticket_id) WHERE ticket_id IS NOT NULL;

-- The admin block list (spec 11b): the only exception to "every email becomes a ticket".
CREATE TABLE mail_block_rule (
    id                 BIGSERIAL    PRIMARY KEY,
    kind               VARCHAR(8)   NOT NULL CHECK (kind IN ('ADDRESS', 'DOMAIN')),
    value              VARCHAR(320) NOT NULL CHECK (value = lower(value)),
    created_by_user_id BIGINT       REFERENCES app_user (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT mail_block_rule_uk UNIQUE (kind, value)
);

-- Sender rules (spec 11b): always create the ticket for an organisation as a request type. Rules
-- only route; there is no ignore rule.
CREATE TABLE sender_rule (
    id                 BIGSERIAL    PRIMARY KEY,
    kind               VARCHAR(8)   NOT NULL CHECK (kind IN ('ADDRESS', 'DOMAIN')),
    value              VARCHAR(320) NOT NULL CHECK (value = lower(value)),
    organisation_id    BIGINT       NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    request_type_id    BIGINT       NOT NULL REFERENCES request_type (id),
    created_by_user_id BIGINT       REFERENCES app_user (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sender_rule_uk UNIQUE (kind, value)
);

CREATE INDEX sender_rule_org_ix ON sender_rule (organisation_id);

-- Loop safety (spec 11b): tickets opened by bulk or automated senders get no automatic client
-- email (confirmation, resolved and closed notices, reminders). Human public replies still go.
CREATE TABLE ticket_mail_suppression (
    ticket_id  BIGINT      PRIMARY KEY REFERENCES ticket (id),
    reason     VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Inline and attached MIME parts of an outbox message: the signature logo (cid, ADR-015), inline
-- images and files of a public reply. Deleted with their message.
CREATE TABLE outbox_part (
    id                BIGSERIAL    PRIMARY KEY,
    outbox_message_id BIGINT       NOT NULL REFERENCES outbox_message (id) ON DELETE CASCADE,
    position          INTEGER      NOT NULL,
    content_id        VARCHAR(100),
    filename          VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    inline            BOOLEAN      NOT NULL,
    content           BYTEA        NOT NULL CHECK (octet_length(content) <= 20971520),
    CONSTRAINT outbox_part_uk UNIQUE (outbox_message_id, position),
    CONSTRAINT outbox_part_inline_has_cid CHECK (NOT inline OR content_id IS NOT NULL)
);

-- The poller lease (C11, "one poller at a time"): a ShedLock-style row. A poller takes it with a
-- conditional UPDATE, renews it while it works and releases it at the end; a crashed poller's
-- lease simply expires.
CREATE TABLE mail_poller_lock (
    name         VARCHAR(32)  PRIMARY KEY,
    locked_by    VARCHAR(100),
    locked_until TIMESTAMPTZ  NOT NULL
);

INSERT INTO mail_poller_lock (name, locked_by, locked_until) VALUES ('imap', NULL, now());

-- Mailbox test connection (spec 6): a probe mail sent to support+internal@ carries a random token;
-- only the SHA-256 of the token is stored. The poller records a matching probe as PROBE (no
-- ticket) and the test reads seen_at.
CREATE TABLE mailbox_probe (
    id         BIGSERIAL   PRIMARY KEY,
    mailbox_id BIGINT      NOT NULL REFERENCES mailbox (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    seen_at    TIMESTAMPTZ
);
