# Runbook - rotate the data key (TS_DATA_KEY)

`TS_DATA_KEY` encrypts the secrets the helpdesk stores: agents' authenticator (MFA) secrets, mailbox
passwords and OAuth2 client secrets, and the Microsoft sign-in client secret (spec sections 9 and
10). Every stored value carries the id of the key that encrypted it, so a new key can be brought in
while the old one still reads the old values.

Rotate when the key may have been exposed (a copy of `.env` left somewhere, a person with host
access leaves), and otherwise once a year. Allow 15 minutes; the api restarts twice (about a minute
each).

Never paste a key into chat, tickets or a command line. Edit `.env` on the host only (mode 600).
Paths below follow [../deployment.md](../deployment.md): settings in `/opt/ticket-system/.env`, the
release in `/opt/ticket-system/current`.

## Steps (on the host, as root)

1. **Take a backup first**: `systemctl start ticketsys-backup.service` and check
   `cat /opt/ticket-system/backups/LAST_SUCCESS` shows the current time.
2. **Make a new key and add it, keeping the old one readable.** In `/opt/ticket-system/.env`:
   - note the current `TS_DATA_KEY` value and `TS_DATA_KEY_ID` (for example `k1`);
   - set `TS_DATA_OLD_KEYS=k1:<the old 64 hex characters>`;
   - set `TS_DATA_KEY` to a new value made with `openssl rand -hex 32` (write it straight into the
     file with an editor; do not echo it);
   - set `TS_DATA_KEY_ID` to a new id, for example `k2` (letters, digits, `_` or `-`, at most 32).
3. **Restart the api with both keys**:

   ```
   cd /opt/ticket-system/current
   docker compose --env-file ../.env up -d --wait api
   ```

   From now on new values are written with `k2`; old ones still read with `k1`.
4. **Re-encrypt everything**:

   ```
   docker compose --env-file ../.env exec api java -jar /app/app.jar rotate-data-key
   ```

   It prints how many values it re-encrypted and how many each key id now holds, never a key or a
   value. Exit 0 and "every stored secret is on key k2" means done.
5. **Check**: `docker compose --env-file ../.env exec api java -jar /app/app.jar rotate-data-key --check`
   must report only `k2`. Sign in as an agent with MFA, and if a mailbox is set up, run its
   **Test connection** in Admin - Email and signature.
6. **Remove the old key**: delete the `TS_DATA_OLD_KEYS` line from `.env`, restart the api as in
   step 3, and check `/actuator/health` and a sign-in again.
7. Store the new key where you keep the other host secrets (a password manager), and destroy old
   copies of the previous key once no backup needs it (below). Note the date and new key id in your ops log.

## When it does not finish

- **"N could not be decrypted and were left unchanged", exit 3**: the old key in `TS_DATA_OLD_KEYS`
  is missing or wrong (a typo, the wrong id). Nothing was changed or lost. Fix `TS_DATA_OLD_KEYS`,
  restart (step 3) and run step 4 again. Do not remove the old key while `--check` still lists it.
- **"changed by the running app meanwhile"**: someone set up MFA or saved a mailbox during the run;
  that value is already on the new key or is picked up by running step 4 again.
- **The old key is truly lost**: values on it cannot be recovered. MFA: reset each affected agent
  (Admin - Agents, or the break-glass `reset-mfa` command for the last admin, see
  [../30-reliability.md](../30-reliability.md)). Mailbox and Microsoft sign-in secrets: enter them
  again in Admin - Email and signature and Admin - Sign-in.

## Backups and old keys

Backups taken before the rotation hold values encrypted with the old key. If you may need to restore
one, keep the old key (in the password manager, marked with its id and the date it was retired) for
as long as those backups exist (30 days by default), then destroy it. After restoring such a backup,
start with the old key in `TS_DATA_OLD_KEYS` and run step 4 again.

## API keys

API keys are hashed, not encrypted, and do not depend on `TS_DATA_KEY`. Rotate an API key by issuing
a new key for the integration, switching the integration over, then revoking the old key in
Admin - API keys.
