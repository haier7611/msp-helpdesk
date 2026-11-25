-- WP-01 baseline: an empty schema marker so Flyway owns the database from the first deploy.
-- Real tables arrive in later work packages as V2, V3, ... (migrations are append-only).
SELECT 1;
