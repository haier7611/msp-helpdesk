-- WP-18 (ADR-032 change "every change in the timeline"): every ticket change is an agent-only
-- timeline line. ticket_event gains who acted (actor_kind) and, for an organisation move, the
-- ticket reference before and after. reason (V87) is now a general stored code: the assignment
-- reason (ASSIGNEE), the status trigger (STATUS, TicketTransitions.Trigger name), or the
-- participant / link / reporter reason. Set only on rows written from this version on; older rows
-- stay NULL (no backfill). Ids and codes only; the one exception is an email-only participant's
-- address (PARTICIPANT_ADDED / PARTICIPANT_REMOVED new_value), which erasure now scrubs below.
ALTER TABLE ticket_event
    ADD COLUMN actor_kind    VARCHAR(16)
        CHECK (actor_kind IN ('AGENT', 'CLIENT', 'SYSTEM', 'API_KEY')),
    ADD COLUMN old_reference VARCHAR(32),
    ADD COLUMN new_reference VARCHAR(32);

-- ts_erase_user (V83) plus one statement: an erased person's address leaves the participant rows of
-- the ticket history too. Everything else is unchanged.
CREATE OR REPLACE FUNCTION ts_erase_user(p_user_id BIGINT, p_at TIMESTAMPTZ) RETURNS BOOLEAN
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
        -- WP-18: an email-only participant's address in the ticket history.
        UPDATE ticket_event SET new_value = NULL
         WHERE type IN ('PARTICIPANT_ADDED', 'PARTICIPANT_REMOVED') AND lower(new_value) = v_email;
        -- The trigger captured the address when erased_at was set above.
        PERFORM scrub_audit_email(p_user_id);
    END IF;
    RETURN TRUE;
END
$$;
