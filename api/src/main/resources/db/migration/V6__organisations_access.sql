-- WP-03: client organisations, ticket prefixes and counters, issued references and aliases,
-- verified email domains, and the user-to-organisation links (spec sections 2, 6, 8, 11a, 11b).
-- Every client-owned row carries organisation_id (ADR-002). Application role grants come from
-- V5's default privileges.

CREATE TABLE organisation (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    -- IANA zone id, checked by the application (ZoneId region ids only).
    timezone        VARCHAR(64)  NOT NULL,
    -- Stored only; WP-10 uses it as the organisation's allowed Entra tenant.
    entra_tenant_id UUID,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE')),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

-- Two organisations with the same name would be indistinguishable in every picker.
CREATE UNIQUE INDEX organisation_name_lower_uk ON organisation (lower(name));

-- Ticket prefixes (ADR-014). Unique across active, retired and reserved rows, compared
-- case-insensitively; stored upper case. A retired prefix stays reserved to its organisation for
-- ever. TRI is reserved for the triage queue and belongs to no organisation.
CREATE TABLE organisation_prefix (
    prefix          VARCHAR(6)  PRIMARY KEY CHECK (prefix ~ '^[A-Z][A-Z0-9]{1,5}$'),
    organisation_id BIGINT      REFERENCES organisation (id) ON DELETE CASCADE,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED', 'RESERVED')),
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT organisation_prefix_reserved_has_no_org
        CHECK ((status = 'RESERVED') = (organisation_id IS NULL))
);

CREATE UNIQUE INDEX organisation_prefix_upper_uk ON organisation_prefix (upper(prefix));
CREATE UNIQUE INDEX organisation_prefix_one_active_uk
    ON organisation_prefix (organisation_id) WHERE status = 'ACTIVE';

INSERT INTO organisation_prefix (prefix, organisation_id, status, created_at)
VALUES ('TRI', NULL, 'RESERVED', now());

-- One number sequence per organisation plus one for TRI. Taken with a row lock in the
-- transaction that creates the ticket (never max()+1); a rolled-back ticket rolls back its number.
CREATE TABLE ticket_counter (
    counter_key     VARCHAR(32) PRIMARY KEY,
    organisation_id BIGINT      UNIQUE REFERENCES organisation (id) ON DELETE CASCADE,
    next_number     BIGINT      NOT NULL DEFAULT 1 CHECK (next_number >= 1),
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ticket_counter_key_matches CHECK (
        (counter_key = 'TRI' AND organisation_id IS NULL)
        OR (organisation_id IS NOT NULL AND counter_key = 'ORG-' || organisation_id::text))
);

INSERT INTO ticket_counter (counter_key, organisation_id, next_number, updated_at)
VALUES ('TRI', NULL, 1, now());

-- Every reference ever issued (spec section 6 "Aliases"): one PRIMARY per ticket plus any number
-- of ALIAS rows (old prefix, TRI- after triage, a duplicate's source). A reference never changes
-- and keeps resolving for ever. organisation_id is the ticket's current organisation (NULL while
-- in triage), so lookups are scoped like every other client-owned row.
-- ticket_id has no foreign key yet: the ticket table arrives in WP-04, which adds it.
CREATE TABLE ticket_reference (
    id              BIGSERIAL   PRIMARY KEY,
    prefix          VARCHAR(6)  NOT NULL REFERENCES organisation_prefix (prefix),
    number          BIGINT      NOT NULL CHECK (number >= 1),
    ticket_id       BIGINT      NOT NULL,
    organisation_id BIGINT      REFERENCES organisation (id),
    kind            VARCHAR(8)  NOT NULL CHECK (kind IN ('PRIMARY', 'ALIAS')),
    reason          VARCHAR(16) NOT NULL CHECK (reason IN ('ISSUED', 'TRIAGE', 'MOVED', 'DUPLICATE')),
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ticket_reference_uk UNIQUE (prefix, number)
);

CREATE UNIQUE INDEX ticket_reference_one_primary_uk
    ON ticket_reference (ticket_id) WHERE kind = 'PRIMARY';
CREATE INDEX ticket_reference_ticket_ix ON ticket_reference (ticket_id);
CREATE INDEX ticket_reference_org_ix ON ticket_reference (organisation_id);

-- Verified email domains (spec 11b). Primary key = one domain belongs to one organisation only
-- (ADR-019). Public mail domains are refused by the application (maintained list).
CREATE TABLE organisation_domain (
    domain          VARCHAR(253) PRIMARY KEY CHECK (domain = lower(domain)),
    organisation_id BIGINT       NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX organisation_domain_org_ix ON organisation_domain (organisation_id);

-- Users: a customer belongs to exactly one organisation; supporters to none. Agents see every
-- organisation by default (spec section 2) or only the ones listed in supporter_organisation.
-- Admins always see every organisation.
ALTER TABLE app_user
    ADD COLUMN organisation_id   BIGINT  REFERENCES organisation (id) ON DELETE RESTRICT,
    ADD COLUMN all_organisations BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT app_user_customer_has_org
        CHECK ((role = 'CUSTOMER') = (organisation_id IS NOT NULL));

CREATE INDEX app_user_org_ix ON app_user (organisation_id);

CREATE TABLE supporter_organisation (
    user_id         BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    organisation_id BIGINT      NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, organisation_id)
);

CREATE INDEX supporter_organisation_org_ix ON supporter_organisation (organisation_id);
