#!/usr/bin/env bash
# Run by ticketsys-backup-failed.service (OnFailure= of ticketsys-backup.service). Leaves a
# marker that your monitoring and the runbook check (docs/30-reliability.md); the next good backup removes it.
set -euo pipefail
umask 077
DIR=/opt/ticket-system/backups
install -d -m 700 "$DIR"
printf '%s ticketsys-backup.service failed - see: journalctl -u ticketsys-backup -n 50\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$DIR/BACKUP_FAILED"
echo "backup: FAILED - marker written to $DIR/BACKUP_FAILED" >&2
