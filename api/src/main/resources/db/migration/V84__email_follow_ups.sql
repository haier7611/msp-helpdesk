-- WP-07b: email follow-ups (spec sections 5, 6, 8, 11b; ADR-011, ADR-015, ADR-020). The lead
-- renames this file at merge time if main is higher (ADR-026 renumber rule).

-- Outbound mail through the admin-configured mailbox (ADR-020): a mailbox with SMTP settings that
-- is enabled sends every outbound email instead of the environment bootstrap sender. It signs in
-- with the same user name and encrypted secret as for IMAP (password, or the OAuth2 client
-- credentials for an XOAUTH2 token). smtp_last_error is a short category ("SMTP 535", "login
-- failed"), never server text or secrets.
ALTER TABLE mailbox
    ADD COLUMN smtp_host       VARCHAR(253),
    ADD COLUMN smtp_port       INTEGER CHECK (smtp_port BETWEEN 1 AND 65535),
    ADD COLUMN smtp_security   VARCHAR(8) CHECK (smtp_security IN ('TLS', 'STARTTLS', 'NONE')),
    ADD COLUMN smtp_last_error VARCHAR(200),
    ADD CONSTRAINT mailbox_smtp_complete CHECK (
        (smtp_host IS NULL) = (smtp_port IS NULL) AND (smtp_host IS NULL) = (smtp_security IS NULL));

-- Inbound email attachments become ticket attachments through WP-04's pipeline. A file taken from
-- a client's email is stored by the poller (SYSTEM scope), which has no user: its uploader is
-- empty, and only a SYSTEM scope can bind such a pending file (AttachmentRepository). Files from a
-- supporter's emailed reply are owned by that supporter, as an upload would be.
ALTER TABLE attachment ALTER COLUMN uploader_user_id DROP NOT NULL;
