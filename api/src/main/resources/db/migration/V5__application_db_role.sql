-- WP-02b (C14): split the database roles. Flyway runs as the owner role (POSTGRES_USER); the
-- running application connects as ticketsys_app, which reads and writes the application tables
-- but can only INSERT and SELECT the audit log, and cannot touch Flyway's history.
-- The role is created here without a password; ApplicationRolePasswordCallback sets it after
-- every migrate from the application's datasource password, hashed client-side with SCRAM, so no
-- plain password reaches SQL text or the server log.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketsys_app') THEN
        CREATE ROLE ticketsys_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ticketsys_app;

-- Existing tables (incl. WP-02a's outbox) and every table later migrations create.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ticketsys_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ticketsys_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ticketsys_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ticketsys_app;

-- The audit log is append-only for the application.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM ticketsys_app;

-- Only Flyway (the owner role) changes the schema history.
REVOKE ALL ON flyway_schema_history FROM ticketsys_app;
