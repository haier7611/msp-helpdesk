-- ADR-034: ticket email can be sent through the support mailbox (per-mailbox SMTP). The lead may
-- rename this file to main's highest version plus 1 at merge (ADR-026).
--
-- WP-07b (V84) already added smtp_host / smtp_port / smtp_security / smtp_last_error and signed in
-- to SMTP with the IMAP user name and secret. ADR-034 adds the choice "use the same sign-in as
-- incoming" (the default, so existing rows keep working) or a separate SMTP user name and
-- password. The SMTP password is encrypted with the same data key as the IMAP secret (DataCipher,
-- associated data "mailbox-smtp-secret:<id>"), is never returned by the API and is re-encrypted by
-- the data-key rotation like every other secret.
ALTER TABLE mailbox
    ADD COLUMN smtp_same_sign_in      BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN smtp_username          VARCHAR(320),
    ADD COLUMN smtp_secret_ciphertext BYTEA,
    ADD COLUMN smtp_secret_key_id     VARCHAR(32),
    ADD CONSTRAINT mailbox_smtp_secret_complete
        CHECK ((smtp_secret_ciphertext IS NULL) = (smtp_secret_key_id IS NULL)),
    -- A separate sign-in has its own user name; the same sign-in keeps no SMTP user or password.
    ADD CONSTRAINT mailbox_smtp_sign_in_complete CHECK (
        (smtp_same_sign_in AND smtp_username IS NULL AND smtp_secret_ciphertext IS NULL)
        OR (NOT smtp_same_sign_in AND smtp_host IS NOT NULL AND smtp_username IS NOT NULL));
