import type { ImapSecurity } from "../../api/admin";
import { Checkbox, FieldRow, HelpToggle, Select, TextField } from "../../components/forms";
import styles from "./admin.module.css";
import type { OutgoingDraft } from "./outgoingEmail";

/**
 * Admin - Email - Mailboxes - Add/Edit: "Outgoing (SMTP)", the right-hand section of the
 * wide mailbox dialog. With an SMTP server, ticket mail (replies, confirmations, status
 * emails, agent alerts) goes out through this mailbox, from the support address. Account and
 * security mail always uses the server's mail settings. The SMTP password is write-only, like the
 * incoming one. Helpers live in outgoingEmail.ts.
 */
export function OutgoingEmailFields({
  value,
  onChange,
}: {
  value: OutgoingDraft;
  onChange: (next: OutgoingDraft) => void;
}) {
  const set = (patch: Partial<OutgoingDraft>) => onChange({ ...value, ...patch });
  const hasServer = value.smtpHost.trim() !== "";
  return (
    <fieldset className={styles.section}>
      <legend className={styles.sectionLegend}>Outgoing (SMTP)</legend>
      <HelpToggle
        topic="outgoing email"
        detail="With an SMTP server, all email is sent through this mailbox from the support address: replies and other ticket emails, and also invitations, password resets, sign-in links and security notices. If several mailboxes have an SMTP server, the first one added is used."
      >
        Leave empty to keep using the server&apos;s mail settings.
      </HelpToggle>
      <TextField
        label="SMTP server"
        autoComplete="off"
        placeholder="smtp.gmail.com"
        value={value.smtpHost}
        onChange={(e) => set({ smtpHost: e.target.value })}
      />
      {hasServer && (
        <>
          <FieldRow>
            <TextField
              label="SMTP port"
              inputMode="numeric"
              fieldSize="xs"
              placeholder={value.smtpSecurity === "STARTTLS" ? "587" : "465"}
              value={value.smtpPort}
              onChange={(e) => set({ smtpPort: e.target.value })}
            />
            <Select
              label="SMTP security"
              fieldSize="sm"
              value={value.smtpSecurity}
              options={[
                { value: "TLS", label: "TLS" },
                { value: "STARTTLS", label: "STARTTLS" },
                { value: "NONE", label: "None (local test only)" },
              ]}
              onChange={(e) => set({ smtpSecurity: e.target.value as ImapSecurity })}
            />
          </FieldRow>
          <Checkbox
            label="Use the same sign-in as incoming"
            checked={value.smtpSameSignIn}
            onChange={(e) => set({ smtpSameSignIn: e.target.checked })}
          />
          {!value.smtpSameSignIn && (
            <>
              <TextField
                label="SMTP user name"
                autoComplete="off"
                value={value.smtpUsername}
                onChange={(e) => set({ smtpUsername: e.target.value })}
              />
              <TextField
                label="SMTP password"
                type="password"
                autoComplete="new-password"
                hint={
                  value.smtpSecretSet
                    ? "Saved and encrypted. Enter a new one only to replace it."
                    : "Stored encrypted. For Gmail, use an app password."
                }
                value={value.smtpSecret}
                onChange={(e) => set({ smtpSecret: e.target.value })}
              />
            </>
          )}
        </>
      )}
    </fieldset>
  );
}
