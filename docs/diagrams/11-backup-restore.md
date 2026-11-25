# 11 - Backup and restore

What this shows: how data is backed up every night, how long things are kept, how a restore is proved (the drill) and how the system is moved to another host. The backup unit writes to the host disk; copy the files to a separate store so a lost host does not take the backups with it.

Reflects: [ADR-016](../10-decision-log.md) hosting, [ADR-028](../10-decision-log.md) production on the same stack (RPO 24 h, RTO one working day). Spec: [section 10](../15-product-spec.md) (erasure, backups, retention). Docs: [docs/30-reliability.md](../30-reliability.md) (Backups, Restore drill, Full restore, Full export), [move runbook](../runbooks/move-domain-or-server.md), [rotate-data-key runbook](../runbooks/rotate-data-key.md). Scripts: `scripts/backup/`.

## Nightly backup and retention

```mermaid
flowchart TD
  T["ticketsys-backup.timer<br/>02:30 UTC plus up to 15 min"] --> BK["ticketsys-backup.service"]
  BK --> DB["pg_dump -Fc to backups/db-UTC.dump<br/>checked with pg_restore --list"]
  BK --> AT["tar of data/attachments to backups/attachments-UTC.tar.gz"]
  BK --> PR["delete files older than 30 days"]
  BK -->|"success"| LS["write backups/LAST_SUCCESS,<br/>remove BACKUP_FAILED"]
  BK -->|"any error"| FL["OnFailure: ticketsys-backup-failed<br/>writes backups/BACKUP_FAILED"]
  RJ["RetentionJob 03:40 UTC<br/>runs after the backup"] -.->|"deleted data stays in that night's backup 30 days"| PR
  OFF["Copy to a separate store<br/>operator's choice, NOT BUILT IN"]:::nb
  BK -.-> OFF
  classDef nb stroke-dasharray: 5 5
```

The 30-day backup window is also the erasure lag (spec section 10). Alerting on a missing or stale (over 36 h) backup is not built in: watch the `LAST_SUCCESS` and `BACKUP_FAILED` markers.

## Restore drill and real restore

```mermaid
flowchart TD
  subgraph Drill["Restore drill: monthly, after backup changes, before real data"]
    D1["ticketsys-restore-drill"] --> D2["restore newest dump into scratch database<br/>ticketsys_restore_drill"]
    D2 --> D3["row counts equal the dump's own counts"]
    D3 --> D4["attachments archive of the same night<br/>holds every file the rows point at"]
    D4 --> D5["replay user_erasure with ts_erase_user,<br/>no erased person keeps a name or email"]
    D5 --> D6["drop scratch db, write RESTORE_DRILL_LAST"]
  end
  subgraph Real["Full restore: real incident"]
    R1["stop the api"] --> R2["take a fresh backup first"]
    R2 --> R3["pg_restore --clean into the live database<br/>hand audit-scrub functions back to their owner"]
    R3 --> R4["restore attachments archive, fix ownership"]
    R4 --> R5["replay the erasure log"]
    R5 --> R6["start the api, health check"]
  end
```

The drill never writes to the live database.

## Export and move

```mermaid
flowchart LR
  EX["ticketsys-export on the host<br/>root only"] --> AR["ticketsys-export-UTC.tar, mode 600<br/>dump without sessions, sign-in links, devices<br/>attachment store, non-secret settings,<br/>manifest with counts and checksums"]
  AR -->|"encrypted transfer only"| NH["New host: install stack, import,<br/>restore attachments"]
  KEY["TS_DATA_KEY travels separately"] --> NH
  NH --> DN["New DNS name and edge,<br/>TS_PUBLIC_BASE_URL, Entra redirect URI,<br/>mailbox and branding"]
  DN --> DEL["Delete the archive after the move"]
```

Case A (new address, same server) and case B (new server) are in the [move runbook](../runbooks/move-domain-or-server.md). The export holds password hashes and encrypted secrets, so it is not a sanitised dataset ([security](../35-security.md), accepted risks). Key rotation (`rotate-data-key`) re-encrypts MFA, mailbox and Microsoft secrets under the current key and fails safe.
