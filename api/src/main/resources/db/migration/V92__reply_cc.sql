-- ADR-035: agents choose the CC of a public reply in the composer. When they do, the reply keeps the
-- exact CC list it was sent with (cc_chosen = TRUE plus one public_comment_cc row per address), so
-- the email goes to that list and not to whatever the participants are at send time. one_off marks
-- an address CC'd on this reply only ("Keep on future replies" off). Replies without a choice
-- (email replies, older rows) keep cc_chosen = FALSE and go to the confirmed participants as before.
-- Addresses are stored lower case; user_id is set for a contact of the ticket's organisation.
ALTER TABLE public_comment
    ADD COLUMN cc_chosen BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE public_comment_cc (
    comment_id BIGINT       NOT NULL REFERENCES public_comment (id) ON DELETE CASCADE,
    email      VARCHAR(320) NOT NULL,
    user_id    BIGINT       REFERENCES app_user (id),
    one_off    BOOLEAN      NOT NULL,
    PRIMARY KEY (comment_id, email)
);

CREATE INDEX public_comment_cc_user_ix ON public_comment_cc (user_id) WHERE user_id IS NOT NULL;

-- ts_erase_user (V90) plus two statements: an erased person's address also leaves the CC lists of
-- public replies. Everything else is unchanged.
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
    DELETE FROM public_comment_cc WHERE user_id = p_user_id;
    UPDATE public_comment SET author_email = NULL, auth_results = NULL
     WHERE author_user_id = p_user_id;
    UPDATE internal_note SET auth_results = NULL WHERE author_user_id = p_user_id;
    IF v_email IS NOT NULL THEN
        DELETE FROM ticket_participant WHERE email = v_email;
        -- ADR-035: the CC chosen for one public reply.
        DELETE FROM public_comment_cc WHERE email = v_email;
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
