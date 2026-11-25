-- WP-11b: admin configuration (spec 5, 7 "Admin - other settings", 11a, 11b; ADR-011, ADR-015,
-- ADR-020, ADR-027). Numbered above main's highest (V70); the lead renames it at merge time if main
-- is higher by then (ADR-026). Application role grants come from V5's default privileges.

-- Request types: client-facing description, display order and archive ("hidden"). PR #14 (WP-06,
-- ADR-027) adds description and sort_order too; IF NOT EXISTS lets either migration run first.
ALTER TABLE request_type
    ADD COLUMN IF NOT EXISTS description VARCHAR(200),
    ADD COLUMN IF NOT EXISTS sort_order  INTEGER NOT NULL DEFAULT 1000,
    -- Archived types are no longer offered for new tickets or sender rules; tickets that already
    -- use one keep it (a type in use is archived, never deleted - WP-11b open question).
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Types nobody has ordered yet keep their creation order.
UPDATE request_type SET sort_order = id * 10 WHERE sort_order = 1000;

-- Company signature template and branding (spec 5, 11a "From name"). One MSP-wide row.
-- logo_png: the admin's logo, always re-encoded by the server to a PNG of at most 30 KB (ADR-015
-- target); NULL = the built-in placeholder. logo_width/height are the display size in the email.
-- support_name: Admin - Branding; NULL = MAIL_SUPPORT_NAME from the environment.
ALTER TABLE signature_template
    ADD COLUMN confidentiality_line VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN logo_png             BYTEA CHECK (logo_png IS NULL OR octet_length(logo_png) <= 30720),
    ADD COLUMN logo_width           SMALLINT CHECK (logo_width IS NULL OR logo_width BETWEEN 1 AND 300),
    ADD COLUMN logo_height          SMALLINT CHECK (logo_height IS NULL OR logo_height BETWEEN 1 AND 150),
    ADD COLUMN support_name         VARCHAR(100),
    ADD CONSTRAINT signature_template_logo_complete
        CHECK ((logo_png IS NULL) = (logo_width IS NULL) AND (logo_png IS NULL) = (logo_height IS NULL));

-- Audit log viewer filters by organisation (details.organisation) and actor.
CREATE INDEX audit_event_actor_ix ON audit_event (actor_user_id, occurred_at);
CREATE INDEX audit_event_organisation_ix ON audit_event ((details ->> 'organisation'), occurred_at);
