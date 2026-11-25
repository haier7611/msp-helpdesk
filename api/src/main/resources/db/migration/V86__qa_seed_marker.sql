-- ADR-030 (QA round): marks every row created by the host command ticketsys-qa-seed so that
-- ticketsys-qa-wipe removes only those rows and never anything else. Not client data: it holds
-- ids and a short synthetic key (an organisation prefix, a seeded e-mail address on a reserved
-- .example domain or a ticket key such as T01), never content.
CREATE TABLE qa_seed (
    id         BIGSERIAL    PRIMARY KEY,
    kind       VARCHAR(16)  NOT NULL CHECK (kind IN ('ORGANISATION', 'USER', 'TICKET', 'NOTE', 'STEP')),
    ref_id     BIGINT       NOT NULL,
    ref_key    VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT qa_seed_ref_uk UNIQUE (kind, ref_id),
    CONSTRAINT qa_seed_key_uk UNIQUE (kind, ref_key)
);
