-- WP-05: saved replies (spec F3, "canned responses") inserted with "/" in the agent composer.
-- owner_user_id NULL = shared by every supporter (admin management screen is WP-11b); otherwise
-- personal to that supporter. Bodies are sanitised HTML with {placeholders}; no client data.
-- Application role grants come from V5's default privileges. Flyway block for WP-05: V70-V79.

CREATE TABLE saved_reply (
    id            BIGSERIAL    PRIMARY KEY,
    owner_user_id BIGINT       REFERENCES app_user (id) ON DELETE CASCADE,
    title         VARCHAR(120) NOT NULL,
    body_html     TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX saved_reply_owner_ix ON saved_reply (owner_user_id);
