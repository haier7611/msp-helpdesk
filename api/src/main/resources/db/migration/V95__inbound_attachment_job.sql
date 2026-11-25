-- ADR-039 step 1: ticket first, attachments after. The poller commits the ticket or comment an
-- email becomes straight away (inline images as "[image]" placeholders) and records here that the
-- email's files still have to be checked, stored and bound to that comment. A worker in the same
-- JVM does that right after the commit; a job a crash or restart interrupted is picked up again
-- from the kept raw .eml (inbound_mail.raw_eml, 90 days), so no file is lost.
--
-- owner_user_id: null = the files are the client's (SYSTEM scope, no uploader); a supporter's id =
-- their emailed internal note's files, stored with their own scope as before.
-- reply_only: the comment holds only the reply part (quoted history stripped).
-- lease_until: a RUNNING job whose lease ran out (the JVM died) may be claimed again.
CREATE TABLE inbound_attachment_job (
    id                BIGSERIAL    PRIMARY KEY,
    inbound_mail_id   BIGINT       NOT NULL REFERENCES inbound_mail (id) ON DELETE CASCADE,
    ticket_id         BIGINT       NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    public_comment_id BIGINT       REFERENCES public_comment (id) ON DELETE CASCADE,
    internal_note_id  BIGINT       REFERENCES internal_note (id) ON DELETE CASCADE,
    owner_user_id     BIGINT       REFERENCES app_user (id) ON DELETE CASCADE,
    reply_only        BOOLEAN      NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    lease_until       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ,
    CHECK ((public_comment_id IS NULL) <> (internal_note_id IS NULL))
);

-- The worker's sweep: open jobs only.
CREATE INDEX inbound_attachment_job_open_ix ON inbound_attachment_job (next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');

-- The agent ticket view: "Processing attachments..." on the comments of one ticket.
CREATE INDEX inbound_attachment_job_ticket_ix ON inbound_attachment_job (ticket_id)
    WHERE status IN ('PENDING', 'RUNNING');
