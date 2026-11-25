-- WP-08: integrations API keys and alert tickets (spec 2, 4 flow 3, 7 "API"; ADR-009 C16; ADR-024).
-- WP-08 owns V85-V89 (ADR-026 Flyway blocks). Application role grants come from V5's default
-- privileges.

-- One row per key, bound to exactly one organisation. The key itself is never stored: only
-- SHA-256 of the full key string. key_id is the random visible part of the key
-- ("tsk_<key_id>_<secret>") used to find the row before the constant-time hash compare.
CREATE TABLE api_key (
    id                 BIGSERIAL    PRIMARY KEY,
    organisation_id    BIGINT       NOT NULL REFERENCES organisation (id),
    name               VARCHAR(100) NOT NULL,
    key_id             VARCHAR(8)   NOT NULL UNIQUE CHECK (key_id ~ '^[a-z0-9]{8}$'),
    secret_hash        BYTEA        NOT NULL CHECK (octet_length(secret_hash) = 32),
    created_by_user_id BIGINT       REFERENCES app_user (id),
    created_at         TIMESTAMPTZ  NOT NULL,
    expires_at         TIMESTAMPTZ,
    last_used_at       TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    revoked_by_user_id BIGINT       REFERENCES app_user (id)
);

CREATE INDEX api_key_org_ix ON api_key (organisation_id);

-- The opt-in for client emails about API alert tickets is WP-09's
-- organisation_sla_settings.api_alert_client_emails (default off); WP-08 adds no column for it.

-- Which key raised an API ticket or wrote an API comment (audit trail; no content).
ALTER TABLE ticket ADD COLUMN created_by_api_key_id BIGINT REFERENCES api_key (id);
ALTER TABLE public_comment ADD COLUMN author_api_key_id BIGINT REFERENCES api_key (id);

-- Dedup lookups: the newest API ticket of an organisation with an external_id.
CREATE INDEX ticket_api_external_ix ON ticket (organisation_id, external_id, id DESC)
    WHERE source = 'API' AND external_id IS NOT NULL;
