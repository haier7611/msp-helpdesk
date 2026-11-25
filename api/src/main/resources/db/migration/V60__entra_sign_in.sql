-- WP-10: Microsoft (Entra) sign-in, configured by an admin (ADR-025, spec 7 and 11c).
-- Numbered V60 so it cannot clash with items built in parallel (WP-04 V21, WP-11a V40+); later
-- items continue from the highest number on main. Application role grants come from V5's default
-- privileges.

-- The single settings row. Off by default. The client secret is AES-256-GCM ciphertext
-- (DataCipher, associated data "entra_settings:client_secret") with the id of the key that made
-- it; the plain secret is never stored and never returned by any API. settings_version goes up on
-- every change to the client ID, secret or MSP tenant; tested_version is the version that last
-- passed "Test sign-in", and the switch can only be turned on while they are equal.
CREATE TABLE entra_settings (
    id                      SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled                 BOOLEAN      NOT NULL DEFAULT FALSE,
    client_id               UUID,
    msp_tenant_id           UUID,
    secret_ciphertext       BYTEA,
    secret_key_id           VARCHAR(32),
    -- Entered by the admin from the Entra portal; drives the 30-day warning.
    secret_expires_on       DATE,
    settings_version        INTEGER      NOT NULL DEFAULT 1,
    tested_version          INTEGER,
    tested_at               TIMESTAMPTZ,
    -- When the secret-expiry warning was last sent (WP-09 notifier); cleared when the secret or
    -- its expiry date changes.
    expiry_warning_sent_at  TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT entra_settings_secret_complete
        CHECK ((secret_ciphertext IS NULL) = (secret_key_id IS NULL)),
    CONSTRAINT entra_settings_enabled_needs_config
        CHECK (NOT enabled OR (client_id IS NOT NULL AND msp_tenant_id IS NOT NULL
                               AND secret_ciphertext IS NOT NULL AND tested_version IS NOT NULL))
);

INSERT INTO entra_settings (id, updated_at) VALUES (1, now());

-- A user's linked Microsoft account: Entra tenant id + object id (tid, oid), never an email
-- (ADR-009 C1). One Microsoft account per user and one user per (tid, oid).
CREATE TABLE external_identity (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    tenant_id       UUID         NOT NULL,
    object_id       UUID         NOT NULL,
    linked_at       TIMESTAMPTZ  NOT NULL,
    last_sign_in_at TIMESTAMPTZ,
    CONSTRAINT external_identity_user_uk UNIQUE (user_id),
    CONSTRAINT external_identity_tid_oid_uk UNIQUE (tenant_id, object_id)
);
