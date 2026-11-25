-- ADR-048: admins can turn client emails on or off per organisation (Admin - Organisations -
-- "Client emails"). Four switches: Ticket received (the confirmation), Resolved notice, Closed
-- notice and Pending reminder. The Pending reminder switch already exists
-- (organisation_sla_settings.pending_reminder_enabled, V61) and keeps its stored value; the other
-- three are new columns on the same one-row-per-organisation table.
--
-- Default on: ADD COLUMN ... DEFAULT TRUE fills every existing row with TRUE, and an organisation
-- without a settings row reads as all on in the application (as for the V61 timers), so nothing
-- changes until an admin switches one off. Agent public replies and the agent-opened ticket email
-- are never switchable and have no column. Numbered V99 per ADR-049's migration order note (ADR-049
-- takes V100 and merges after this one).
ALTER TABLE organisation_sla_settings
    ADD COLUMN ticket_received_email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN resolved_email_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN closed_email_enabled          BOOLEAN NOT NULL DEFAULT TRUE;
