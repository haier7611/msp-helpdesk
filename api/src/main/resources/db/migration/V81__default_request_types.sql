-- WP-06, ADR-027 (change 2026-11-18): the default IT helpdesk request types.
-- 8 client-visible types plus the hidden Alert. No type defaults to P1, so a client's choice never
-- starts the 24/7 P1 clock; agents set the final priority (F6). Admin editing is WP-11b.

-- Short client-facing helper text shown under each type in the portal form, and an explicit
-- display order ("Other or general question" last among the visible types, Alert after it).
ALTER TABLE request_type
    ADD COLUMN IF NOT EXISTS description VARCHAR(200),
    ADD COLUMN IF NOT EXISTS sort_order  INTEGER NOT NULL DEFAULT 1000;

-- "General" (V21) is renamed in place so its id - and every ticket that uses it - is kept.
UPDATE request_type
SET name = 'Other or general question', default_priority = 'P4', description = 'Anything else',
    sort_order = 80
WHERE name = 'General';

-- Alert stays hidden from the portal at P2; only its position is set.
UPDATE request_type SET sort_order = 900 WHERE name = 'Alert';

-- Idempotent: a type an admin (or an earlier run) already created with the same name is left as is.
INSERT INTO request_type (name, default_priority, portal_visible, description, sort_order, created_at)
VALUES
    ('Account and access',         'P3', TRUE, 'Passwords, locked accounts, permissions', 10, now()),
    ('Email and Microsoft 365',    'P3', TRUE, 'Outlook, Teams, OneDrive, SharePoint',    20, now()),
    ('Computers and devices',      'P3', TRUE, 'Laptops, phones, printers, scanners',     30, now()),
    ('Network and internet',       'P2', TRUE, 'Wi-Fi, VPN, internet down',               40, now()),
    ('Software and applications',  'P3', TRUE, 'Installs, errors, licences',              50, now()),
    ('New starter or leaver',      'P3', TRUE, 'Setting people up or removing them',      60, now()),
    ('Security concern',           'P2', TRUE, 'Phishing, viruses, suspicious email',     70, now()),
    ('Other or general question',  'P4', TRUE, 'Anything else',                           80, now())
ON CONFLICT ((lower(name))) DO NOTHING;
