-- WP-12 hardening and go-live (spec section 10): retention periods, personal-data erasure with an
-- erasure log, a narrow audit purge for the 1-year audit retention, and ON DELETE handling for
-- rows that point at a ticket (AUD-13 Low). Renumbered to V82 (main's highest + 1, ADR-026) when
-- rebased for the PR #22 audit fixes; never applied anywhere as V71.

-- Retention period per organisation (spec 8, 10): closed tickets older than this are deleted with
-- their attachments by the nightly retention job. Default 3 years; triage tickets (no
-- organisation) use the default.
ALTER TABLE organisation
    ADD COLUMN retention_months INTEGER NOT NULL DEFAULT 36
        CHECK (retention_months BETWEEN 12 AND 120);

-- AUD-13 Low: a deleted ticket no longer blocks on its inbound mail log rows or its mail
-- suppression flag. The retention job deletes those rows itself; these are the safety net.
ALTER TABLE inbound_mail DROP CONSTRAINT inbound_mail_ticket_id_fkey;
ALTER TABLE inbound_mail
    ADD CONSTRAINT inbound_mail_ticket_id_fkey
        FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE SET NULL;
ALTER TABLE ticket_mail_suppression DROP CONSTRAINT ticket_mail_suppression_ticket_id_fkey;
ALTER TABLE ticket_mail_suppression
    ADD CONSTRAINT ticket_mail_suppression_ticket_id_fkey
        FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE;

-- A reference issued to a ticket that retention deleted, but re-pointed to a surviving ticket (a
-- duplicate's references follow their target, spec 6), keeps resolving; only its "first issued
-- to" link is cleared.
ALTER TABLE ticket_reference ALTER COLUMN issued_ticket_id DROP NOT NULL;

-- Erasure (spec 10): an erased user keeps their row (ticket history still reads "Former user")
-- but has no email, no password and can never be reactivated.
ALTER TABLE app_user ALTER COLUMN email DROP NOT NULL;
ALTER TABLE app_user ADD COLUMN erased_at TIMESTAMPTZ;
ALTER TABLE app_user
    ADD CONSTRAINT app_user_email_unless_erased CHECK (email IS NOT NULL OR erased_at IS NOT NULL),
    ADD CONSTRAINT app_user_erased_is_final
        CHECK (erased_at IS NULL OR (status = 'DEACTIVATED' AND email IS NULL
                                     AND password_hash IS NULL));

-- The erasure log (spec 10): replayed after any backup restore so erased people stay erased.
-- Holds ids only, never the erased name or email.
CREATE TABLE user_erasure (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES app_user (id),
    actor_user_id BIGINT      REFERENCES app_user (id),
    erased_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX user_erasure_user_ix ON user_erasure (user_id);

-- Audit email scrub (spec 10, PR #22 audit M1). The application role cannot UPDATE audit_event (C14).
-- Removing an erased person's address from audit details is done by one narrow SECURITY DEFINER
-- function that takes a USER ID, never an address, and refuses unless that user is visibly erased
-- (erased_at set, email NULL). It cannot be pointed at an arbitrary address: the address it
-- removes comes from user_erasure_address, a write-once record captured by a trigger at the
-- moment of erasure, which the application role can neither read nor change. The scrub consumes
-- that record (so the address does not outlive the erase transaction) and writes its own
-- AUDIT_EMAIL_SCRUBBED event (user id and count only).
--
-- Both functions are owned by ticketsys_audit_scrub, a NOLOGIN role holding only the privileges
-- they need, so a flaw in them cannot reach anything else; search_path is pinned to pg_catalog
-- and pg_temp (last) and every object is schema-qualified.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketsys_audit_scrub') THEN
        CREATE ROLE ticketsys_audit_scrub NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO ticketsys_audit_scrub;

CREATE TABLE user_erasure_address (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    email       TEXT        NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX user_erasure_address_user_ix ON user_erasure_address (user_id);
-- The default privileges (V5) would give the app role full access; take all of it back.
REVOKE ALL ON user_erasure_address FROM PUBLIC, ticketsys_app;
REVOKE ALL ON SEQUENCE user_erasure_address_id_seq FROM PUBLIC, ticketsys_app;
GRANT SELECT, INSERT, DELETE ON user_erasure_address TO ticketsys_audit_scrub;
GRANT USAGE ON SEQUENCE user_erasure_address_id_seq TO ticketsys_audit_scrub;
GRANT SELECT (id, email, erased_at) ON app_user TO ticketsys_audit_scrub;
GRANT SELECT, INSERT ON audit_event TO ticketsys_audit_scrub;
GRANT UPDATE (details) ON audit_event TO ticketsys_audit_scrub;
GRANT USAGE ON SEQUENCE audit_event_id_seq TO ticketsys_audit_scrub;

-- Captures the address once, when erased_at goes from NULL to set. Fires for any writer.
CREATE FUNCTION capture_erased_address() RETURNS TRIGGER
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.erased_at IS NULL AND NEW.erased_at IS NOT NULL AND OLD.email IS NOT NULL THEN
        INSERT INTO public.user_erasure_address (user_id, email)
        VALUES (OLD.id, lower(OLD.email));
    END IF;
    RETURN NEW;
END
$$;
ALTER FUNCTION capture_erased_address() OWNER TO ticketsys_audit_scrub;
REVOKE ALL ON FUNCTION capture_erased_address() FROM PUBLIC;
CREATE TRIGGER app_user_capture_erased_address
    AFTER UPDATE OF erased_at ON app_user
    FOR EACH ROW EXECUTE FUNCTION capture_erased_address();

-- Removes the erased user's captured address from audit details: the allow-listed keys that can
-- hold an address ("email", and "rule" for a block or sender rule on that exact address), exact
-- value only. Raises for an unknown or not-erased user, changing nothing.
CREATE FUNCTION scrub_audit_email(p_user_id BIGINT) RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_erased TIMESTAMPTZ;
    v_email  TEXT;
    n        INTEGER;
    total    INTEGER := 0;
BEGIN
    SELECT u.erased_at, u.email INTO v_erased, v_email FROM public.app_user u WHERE u.id = p_user_id;
    IF NOT FOUND OR v_erased IS NULL OR v_email IS NOT NULL THEN
        RAISE EXCEPTION 'scrub_audit_email: user % is not erased', p_user_id
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    FOR v_email IN
        SELECT DISTINCT a.email FROM public.user_erasure_address a WHERE a.user_id = p_user_id
    LOOP
        UPDATE public.audit_event SET details = details - 'email' WHERE details ->> 'email' = v_email;
        GET DIAGNOSTICS n = ROW_COUNT;
        total := total + n;
        UPDATE public.audit_event SET details = details - 'rule' WHERE details ->> 'rule' = v_email;
        GET DIAGNOSTICS n = ROW_COUNT;
        total := total + n;
    END LOOP;
    DELETE FROM public.user_erasure_address WHERE user_id = p_user_id;
    INSERT INTO public.audit_event (occurred_at, type, target_user_id, details)
    VALUES (now(), 'AUDIT_EMAIL_SCRUBBED', p_user_id, jsonb_build_object('rows', total));
    RETURN total;
END
$$;
ALTER FUNCTION scrub_audit_email(BIGINT) OWNER TO ticketsys_audit_scrub;
REVOKE ALL ON FUNCTION scrub_audit_email(BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scrub_audit_email(BIGINT) TO ticketsys_app;

-- Audit retention (spec 10): deletes at most p_limit events older than p_before, but never one
-- younger than one year by the database clock, whatever the caller asks. The application role
-- still cannot DELETE from audit_event directly.
CREATE FUNCTION purge_audit_events(p_before TIMESTAMPTZ, p_limit INTEGER) RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_before TIMESTAMPTZ := LEAST(p_before, now() - INTERVAL '365 days');
    n INTEGER;
BEGIN
    DELETE FROM audit_event WHERE id IN (
        SELECT id FROM audit_event WHERE occurred_at < v_before ORDER BY id
        LIMIT GREATEST(LEAST(p_limit, 50000), 0));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;
REVOKE ALL ON FUNCTION purge_audit_events(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_audit_events(TIMESTAMPTZ, INTEGER) TO ticketsys_app;

-- Pseudonymises one user (spec 10). Used by the admin erase action and by the restore drill's
-- erasure-log replay, so both do exactly the same. Idempotent. Runs with the caller's rights.
-- Kept: the row (id, role, organisation), comment bodies, tickets, attachments.
-- Changed: name "Former user", email, password and security data removed, status DEACTIVATED,
-- every session, device, token, link, photo, profile line and notification removed, the address
-- removed from ticket reporter fields, comment author fields, participants, the inbound mail log
-- (and its raw copy, and the address inside its subject), the Authentication-Results kept on
-- their emailed notes and comments, outbox rows addressed only to them (queued ones are
-- cancelled, so nothing more is sent; rows being sent at that instant finish and age out after 90
-- days; cc and multi-recipient rows are kept, spec 10) and audit details (scrub_audit_email).
CREATE FUNCTION ts_erase_user(p_user_id BIGINT, p_at TIMESTAMPTZ) RETURNS BOOLEAN
    LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_email TEXT;
BEGIN
    SELECT lower(email) INTO v_email FROM app_user WHERE id = p_user_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    UPDATE app_user
       SET name = 'Former user', email = NULL, password_hash = NULL, status = 'DEACTIVATED',
           security_version = security_version + 1, erased_at = COALESCE(erased_at, p_at),
           updated_at = p_at
     WHERE id = p_user_id;
    DELETE FROM spring_session WHERE principal_name = p_user_id::text;
    DELETE FROM auth_token WHERE user_id = p_user_id;
    DELETE FROM user_device WHERE user_id = p_user_id;
    DELETE FROM mfa_recovery_code WHERE user_id = p_user_id;
    DELETE FROM user_mfa WHERE user_id = p_user_id;
    DELETE FROM external_identity WHERE user_id = p_user_id;
    DELETE FROM user_photo WHERE user_id = p_user_id;
    DELETE FROM supporter_profile WHERE user_id = p_user_id;
    DELETE FROM notification WHERE user_id = p_user_id;
    DELETE FROM notification_digest_log WHERE user_id = p_user_id;
    DELETE FROM ticket_participant WHERE user_id = p_user_id;
    UPDATE public_comment SET author_email = NULL, auth_results = NULL
     WHERE author_user_id = p_user_id;
    UPDATE internal_note SET auth_results = NULL WHERE author_user_id = p_user_id;
    IF v_email IS NOT NULL THEN
        DELETE FROM ticket_participant WHERE email = v_email;
        UPDATE ticket SET reporter_email = NULL WHERE lower(reporter_email) = v_email;
        UPDATE public_comment SET author_email = NULL, auth_results = NULL
         WHERE lower(author_email) = v_email;
        UPDATE public_comment SET auth_results = NULL
         WHERE strpos(lower(auth_results), v_email) > 0;
        UPDATE internal_note SET auth_results = NULL
         WHERE strpos(lower(auth_results), v_email) > 0;
        UPDATE inbound_mail SET from_address = NULL, raw_eml = NULL
         WHERE lower(from_address) = v_email;
        UPDATE inbound_mail
           -- '***=' makes the pattern a literal string (no regex characters in the address).
           SET subject = left(regexp_replace(subject, '***=' || v_email, '[removed]', 'gi'), 250)
         WHERE strpos(lower(subject), v_email) > 0;
        DELETE FROM outbox_message
         WHERE lower(to_addresses) = v_email AND status IN ('queued', 'sent', 'failed');
        -- The trigger captured the address when erased_at was set above.
        PERFORM scrub_audit_email(p_user_id);
    END IF;
    RETURN TRUE;
END
$$;
GRANT EXECUTE ON FUNCTION ts_erase_user(BIGINT, TIMESTAMPTZ) TO ticketsys_app;
