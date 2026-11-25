-- WP-15 (ADR-031): two-step sign-in - trusted device after a recovery code, and a time-limited
-- exemption for one named engineer (role AGENT only). The lead may rename this file to main's
-- highest version plus 1 at merge (ADR-026). Application role grants come from V5's default
-- privileges.

-- One row per exempt engineer. The row is the exemption: revoking or ending it deletes the row.
-- The reason is free text an admin typed (it may mention a person's circumstances), so it lives
-- here, where it is deleted with the exemption, and never in the append-only audit log.
CREATE TABLE mfa_exemption (
    user_id             BIGINT       PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    reason              VARCHAR(200) NOT NULL CHECK (length(btrim(reason)) > 0),
    ends_at             TIMESTAMPTZ  NOT NULL,
    granted_by_user_id  BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    granted_at          TIMESTAMPTZ  NOT NULL,
    -- Never longer than 30 days (the service checks the same with the application clock; the
    -- few minutes of slack only cover the time between that check and this insert).
    CONSTRAINT mfa_exemption_period_ck
        CHECK (ends_at > granted_at AND ends_at <= granted_at + INTERVAL '30 days 5 minutes')
);

-- Safety net for "the exemption is removed when the engineer becomes an admin or is
-- deactivated": the services delete it first (with an audit event); this also covers any other
-- path that changes role or status (for example the erase function).
CREATE FUNCTION ts_clear_mfa_exemption() RETURNS TRIGGER
    LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    DELETE FROM mfa_exemption WHERE user_id = NEW.id;
    RETURN NEW;
END
$$;

CREATE TRIGGER app_user_clear_mfa_exemption
    AFTER UPDATE OF role, status ON app_user
    FOR EACH ROW
    WHEN (NEW.role <> 'AGENT' OR NEW.status <> 'ACTIVE')
    EXECUTE FUNCTION ts_clear_mfa_exemption();

-- Trusted device: its own random token in its own cookie (__Host-TS_TRUST, Max-Age 30 days), so
-- the trust cookie and the record both last exactly 30 days. The device cookie (__Host-TS_DEVICE,
-- 400 days) only says "known device" (new-device notice, back-off) and no longer skips the code.
ALTER TABLE user_device ADD COLUMN trust_token_hash CHAR(64);
CREATE UNIQUE INDEX user_device_trust_token_uk ON user_device (trust_token_hash)
    WHERE trust_token_hash IS NOT NULL;

-- Trust given before this change has no trust cookie, so it could never be used again: clear it
-- rather than list devices that no longer skip the code. People tick the box again once.
UPDATE user_device SET trusted_until = NULL WHERE trusted_until IS NOT NULL;
