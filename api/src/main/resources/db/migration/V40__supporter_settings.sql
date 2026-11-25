-- WP-11a: supporter profile (signature fields), profile photo, preferences, notification choices,
-- out of office, and the built-in default company signature template.
-- Numbered V40 so it cannot clash with WP-04 (V21+), built in parallel (ADR-023, ADR-026).
-- Application role grants come from V5's default privileges.

-- Signature fields (ADR-011, spec section 5). The display name is app_user.name. Plain text only;
-- the application trims and validates lengths and characters (no HTML is accepted).
CREATE TABLE supporter_profile (
    user_id     BIGINT       PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    job_title   VARCHAR(80)  NOT NULL DEFAULT '',
    phone       VARCHAR(30)  NOT NULL DEFAULT '',
    mobile      VARCHAR(30)  NOT NULL DEFAULT '',
    free_line   VARCHAR(100) NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ  NOT NULL
);

-- Profile photo (ADR-013): always a re-encoded 256 x 256 PNG (no EXIF/GPS survives the
-- re-encode). Shown in the app only, never in emails.
CREATE TABLE user_photo (
    user_id     BIGINT      PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    png         BYTEA       NOT NULL CHECK (octet_length(png) <= 524288),
    updated_at  TIMESTAMPTZ NOT NULL
);

-- My settings - Preferences and Out of office. Out of office is stored here; auto-assignment
-- (skip while out of office) is WP-09.
CREATE TABLE supporter_preferences (
    user_id          BIGINT      PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    composer_tab     VARCHAR(16) NOT NULL DEFAULT 'INTERNAL_NOTE'
        CHECK (composer_tab IN ('REPLY', 'INTERNAL_NOTE')),
    -- A string key for now; validated against WP-05's saved views once they exist.
    queue_view       VARCHAR(40) NOT NULL DEFAULT 'my-open',
    -- IANA zone id, checked by the application. Default: the MSP's zone (ADR-024).
    timezone         VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    date_format      VARCHAR(16) NOT NULL DEFAULT 'DMY'
        CHECK (date_format IN ('DMY', 'MDY', 'YMD')),
    theme            VARCHAR(8)  NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM')),
    daily_digest     BOOLEAN     NOT NULL DEFAULT FALSE,
    out_of_office    BOOLEAN     NOT NULL DEFAULT FALSE,
    ooo_starts_on    DATE,
    ooo_ends_on      DATE,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT supporter_preferences_ooo_dates
        CHECK (ooo_starts_on IS NULL OR ooo_ends_on IS NULL OR ooo_starts_on <= ooo_ends_on)
);

-- Notification choices per event (spec section 7). Storage only; sending is WP-09. A missing row
-- means the default (email and in-app on).
CREATE TABLE supporter_notification (
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    event       VARCHAR(32) NOT NULL CHECK (event IN (
        'TICKET_ASSIGNED', 'CLIENT_REPLIED', 'MENTIONED', 'INTERNAL_NOTE_ADDED',
        'SLA_WARNING', 'SLA_BREACHED')),
    email       BOOLEAN     NOT NULL,
    in_app      BOOLEAN     NOT NULL,
    PRIMARY KEY (user_id, event)
);

-- The company signature template (spec section 5). One row; editing it is WP-11b. Seeded with
-- synthetic values only (CLAUDE.md rule 9) - the demo's real branding is entered on the server.
-- email_mode: SUPPORTER = the E: line shows the supporter's own address (default, ADR-015);
-- SUPPORT_MAILBOX = it shows support_email.
CREATE TABLE signature_template (
    id             SMALLINT     PRIMARY KEY CHECK (id = 1),
    company_name   VARCHAR(100) NOT NULL,
    address        VARCHAR(200) NOT NULL DEFAULT '',
    brand_colour   VARCHAR(7)   NOT NULL CHECK (brand_colour ~ '^#[0-9A-Fa-f]{6}$'),
    email_mode     VARCHAR(16)  NOT NULL DEFAULT 'SUPPORTER'
        CHECK (email_mode IN ('SUPPORTER', 'SUPPORT_MAILBOX')),
    support_email  VARCHAR(320) NOT NULL DEFAULT '',
    logo_alt       VARCHAR(100) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

INSERT INTO signature_template
    (id, company_name, address, brand_colour, email_mode, support_email, logo_alt, updated_at)
VALUES
    (1, 'Example MSP', '1 Example Street, Example City', '#2B5797', 'SUPPORTER',
     'support@helpdesk.example', 'Example MSP logo', now());
