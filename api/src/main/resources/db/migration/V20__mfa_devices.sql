-- WP-02c: TOTP MFA, recovery codes and known/trusted devices.
-- Numbered V20 (not V6) so it cannot clash with WP-03, which is built in parallel on top of WP-02b
-- (ADR-023); later items continue from the highest number on main.

-- One row per supporter who has started or finished TOTP enrolment. Secrets are AES-256-GCM
-- ciphertext (12-byte IV || ciphertext || tag) bound to the user id as associated data; *_key_id
-- names the server key that encrypted them (spec section 10, key rotation). The plain secret is
-- never stored. pending_* holds an enrolment that is not confirmed yet (first enrolment or
-- re-enrolment); the active secret stays in force until the new one is confirmed.
CREATE TABLE user_mfa (
    user_id             BIGINT       PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    secret_ciphertext   BYTEA,
    secret_key_id       VARCHAR(32),
    confirmed_at        TIMESTAMPTZ,
    -- Highest TOTP time step accepted so far: a code is accepted only for a later step (no replay).
    last_used_step      BIGINT       NOT NULL DEFAULT 0,
    pending_ciphertext  BYTEA,
    pending_key_id      VARCHAR(32),
    pending_created_at  TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT user_mfa_confirmed_has_secret
        CHECK (confirmed_at IS NULL OR (secret_ciphertext IS NOT NULL AND secret_key_id IS NOT NULL)),
    CONSTRAINT user_mfa_pending_complete
        CHECK ((pending_ciphertext IS NULL) = (pending_key_id IS NULL))
);

-- Ten one-time recovery codes per enrolled supporter. Only SHA-256 of the user id and the
-- normalised 60-bit code is stored. used_at is set by a conditional UPDATE, so a code works once.
CREATE TABLE mfa_recovery_code (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash   CHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX mfa_recovery_code_user_hash_uk ON mfa_recovery_code (user_id, code_hash);

-- Browsers a user has fully signed in on (any role). The browser holds a random 256-bit token in
-- the __Host-TS_DEVICE cookie; only its SHA-256 is stored. A known device exempts its user from
-- the account-wide sign-in back-off and suppresses the "new device" notice. trusted_until is set
-- when a supporter ticks "trust this device for 30 days" (skips the sign-in TOTP, never step-up);
-- it is cleared on removal and on password change, MFA change, role change and deactivation.
CREATE TABLE user_device (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash     CHAR(64)     NOT NULL,
    label          VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    last_used_at   TIMESTAMPTZ  NOT NULL,
    last_ip        VARCHAR(64),
    trusted_until  TIMESTAMPTZ
);

CREATE UNIQUE INDEX user_device_token_hash_uk ON user_device (token_hash);
CREATE INDEX user_device_user_ix ON user_device (user_id);
CREATE INDEX user_device_last_used_ix ON user_device (last_used_at);
