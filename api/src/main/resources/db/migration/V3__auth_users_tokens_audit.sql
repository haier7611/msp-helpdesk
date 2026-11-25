-- WP-02b core auth: users, single-use link tokens and the audit log.
-- V2 is WP-02a's outbox (developed in parallel, ADR-023); this item starts at V3.

-- Users of every role. Email is stored trimmed and lower-cased by the application and is unique
-- case-insensitively. The organisation link for customers is added in WP-03.
CREATE TABLE app_user (
    id               BIGSERIAL    PRIMARY KEY,
    email            VARCHAR(320) NOT NULL,
    name             VARCHAR(200) NOT NULL,
    role             VARCHAR(16)  NOT NULL CHECK (role IN ('CUSTOMER', 'AGENT', 'ADMIN')),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DEACTIVATED')),
    -- {argon2}... from DelegatingPasswordEncoder; NULL until an invitation is accepted.
    password_hash    VARCHAR(255),
    -- Bumped on password change and reset; WP-02c checks it on every request.
    security_version INTEGER      NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT app_user_active_has_password CHECK (status <> 'ACTIVE' OR password_hash IS NOT NULL)
);

CREATE UNIQUE INDEX app_user_email_lower_uk ON app_user (lower(email));
CREATE INDEX app_user_role_status_ix ON app_user (role, status);

-- Every link token (invitation, password reset, magic link). Only the SHA-256 of the 256-bit
-- token is stored. Single use: used_at is set in the same transaction that acts on the token.
-- A newer token of the same purpose for the same user deletes the older unused ones.
CREATE TABLE auth_token (
    id                   BIGSERIAL   PRIMARY KEY,
    purpose              VARCHAR(32) NOT NULL
        CHECK (purpose IN ('INVITATION', 'PASSWORD_RESET', 'MAGIC_LINK')),
    user_id              BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash           CHAR(64)    NOT NULL,
    -- Supporter invitations only: SHA-256 of the 6-digit code, salted with the token hash.
    code_hash            CHAR(64),
    failed_code_attempts INTEGER     NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    used_at              TIMESTAMPTZ,
    revoked_at           TIMESTAMPTZ
);

CREATE UNIQUE INDEX auth_token_hash_uk ON auth_token (token_hash);
CREATE INDEX auth_token_user_purpose_ix ON auth_token (user_id, purpose);
CREATE INDEX auth_token_expires_ix ON auth_token (expires_at);

-- Security audit log (C14). Insert and read only for the application role (V5). details holds
-- only allow-listed fields (AuditService); never passwords, tokens, codes or hashes.
CREATE TABLE audit_event (
    id             BIGSERIAL   PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL,
    type           VARCHAR(64) NOT NULL,
    actor_user_id  BIGINT,
    target_user_id BIGINT,
    client_ip      VARCHAR(64),
    details        JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_event_occurred_ix ON audit_event (occurred_at);
CREATE INDEX audit_event_type_ix ON audit_event (type, occurred_at);
CREATE INDEX audit_event_target_ix ON audit_event (target_user_id, occurred_at);
