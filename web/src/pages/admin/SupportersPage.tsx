import { useCallback, useEffect, useState, type ComponentProps, type FormEvent } from "react";
import { Link } from "react-router";
import { listOrganisations, type Organisation } from "../../api/organisations";
import {
  changeRole,
  deactivateSupporter,
  fetchActivity,
  fetchDeactivationPlan,
  grantMfaExemption,
  inviteSupporter,
  listSupporters,
  reactivateSupporter,
  resendInvitation,
  resetSupporterMfa,
  revokeInvitation,
  revokeMfaExemption,
  sendPasswordReset,
  setOrganisationAccess,
  signOutSupporterEverywhere,
  updateSupporter,
  type ActivityEvent,
  type DeactivationPlan,
  type InviteResult,
  type Supporter,
  type SupporterRole,
  type SupporterStatus,
} from "../../api/supporters";
import { Badge, SupporterStatusBadge } from "../../components/Badge";
import { DataTable, Identifier, type Column } from "../../components/DataTable";
import { initials } from "../../components/initials";
import { Button, Checkbox, RadioGroup, Select, TextField, Textarea } from "../../components/forms";
import {
  DEFAULT_EXEMPTION_DAYS,
  MAX_EXEMPTION_DAYS,
  MAX_EXEMPTION_REASON as MAX_REASON,
  addDays,
  exemptionDate,
  exemptionEndsAt,
  isoDay,
} from "../../exemption";
import { Modal } from "../../components/Modal";
import { SidePanel } from "../../components/SidePanel";
import { Tabs } from "../../components/Tabs";
import { useToast } from "../../components/toastContext";
import { Avatar, PageHeader } from "../../components/ui";
import { StepUpCancelled, useStepUp } from "../../components/useStepUp";
import { formatDateTime, formatShortDate, formatShortDateTime } from "../../format";
import { errorText } from "../auth/authHelpers";
import styles from "../pages.module.css";
import { useReportError } from "../../components/useReportError";

const ROLE_OPTIONS = [
  { value: "AGENT", label: "Agent - works tickets" },
  { value: "ADMIN", label: "Admin - everything, incl. settings" },
];

const ROLE_LABEL: Record<SupporterRole, string> = { AGENT: "Agent", ADMIN: "Admin" };
const STATUS_LABEL: Record<SupporterStatus, "Active" | "Invited" | "Deactivated"> = {
  ACTIVE: "Active",
  INVITED: "Invited",
  DEACTIVATED: "Deactivated",
};

const OWN_ACCOUNT = "Use My settings - Security for your own account.";

function when(iso: string | null): string {
  if (!iso) return "Never";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "-"
    : d.toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });
}

/** "19 Nov, 12:51" in the list (year only when not this year); the full date as a tooltip. */
function lastSignIn(iso: string | null) {
  if (!iso) return "Never";
  const short = formatShortDateTime(iso);
  if (!short) return "-";
  return (
    <time dateTime={iso} title={formatDateTime(iso)}>
      {short}
    </time>
  );
}

/**
 * The 2FA cell: "On" / "Off", or a compact "Paused to 26 Nov" badge while an exemption applies.
 * The full end date and time is the badge's accessible text and its tooltip.
 */
function TwoStepCell({ row }: { row: Supporter }) {
  if (!row.mfaExemptUntil) return <>{row.mfaEnrolled ? "On" : "Off"}</>;
  const until = formatDateTime(row.mfaExemptUntil) || row.mfaExemptUntil;
  const full = `Two-step sign-in paused until ${until}`;
  return (
    <span title={full}>
      <Badge tone="paused" label={full}>
        Paused to {formatShortDate(row.mfaExemptUntil) || until}
      </Badge>
    </span>
  );
}

function orgNames(person: Supporter, organisations: Organisation[]): string {
  if (person.role === "ADMIN" || person.allOrganisations) return "All organisations";
  const names = person.organisationIds.map(
    (id) => organisations.find((o) => o.id === id)?.name ?? `#${id}`,
  );
  return names.length ? names.join(", ") : "None";
}

/** A button that may be disabled by a server guard; the reason is shown under it. */
function GuardedButton({
  reason,
  children,
  ...rest
}: { reason: string | null } & ComponentProps<typeof Button>) {
  return (
    <div className={styles.actionBlock}>
      <Button {...rest} disabled={Boolean(reason) || rest.disabled}>
        {children}
      </Button>
      {reason && <p className={styles.reason}>{reason}</p>}
    </div>
  );
}

/** Readable text for one audit event. Every value is rendered as text (React escapes it). */
function describe(event: ActivityEvent): string {
  const label = event.type
    .toLowerCase()
    .split("_")
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(" ");
  const details = Object.entries(event.details)
    .map(([k, v]) => `${k.replace(/_/g, " ")}: ${String(v)}`)
    .join(", ");
  return details ? `${label} (${details})` : label;
}

function ActivityTab({ person }: { person: Supporter }) {
  const [events, setEvents] = useState<ActivityEvent[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    fetchActivity(person.id, controller.signal)
      .then(setEvents)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [person.id]);
  if (error) return <p role="alert">{error}</p>;
  if (!events) return <p>Loading...</p>;
  if (events.length === 0) return <p className={styles.subtle}>No activity recorded yet.</p>;
  return (
    <ul className={styles.activityList} aria-label="Activity">
      {events.map((e) => (
        <li key={e.id}>
          <span>{describe(e)}</span>
          <span className={styles.subtle}>
            {when(e.occurredAt)}
            {e.actorName && e.actorUserId !== person.id ? ` - by ${e.actorName}` : ""}
            {e.clientIp ? ` - ${e.clientIp}` : ""}
          </span>
        </li>
      ))}
    </ul>
  );
}

function OverviewTab({
  person,
  onChanged,
}: {
  person: Supporter;
  onChanged: (s: Supporter) => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(person.name);
  const [jobTitle, setJobTitle] = useState(person.jobTitle);
  const [saving, setSaving] = useState(false);

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      onChanged(await updateSupporter(person.id, name, jobTitle));
      toast.show("Details saved.", "success");
    } catch (err) {
      toast.showError(errorText(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={styles.tabBody}>
      <dl className={styles.facts}>
        <dt>Email</dt>
        <dd>{person.email}</dd>
        <dt>Status</dt>
        <dd>{STATUS_LABEL[person.status]}</dd>
        {person.status === "INVITED" ? (
          <>
            <dt>Invitation expires</dt>
            <dd>{when(person.invitationExpiresAt)}</dd>
          </>
        ) : (
          <>
            <dt>Last sign-in</dt>
            <dd>{when(person.lastSignInAt)}</dd>
          </>
        )}
        <dt>Open tickets</dt>
        <dd>{person.openTickets}</dd>
        <dt>Out of office</dt>
        <dd>
          {person.outOfOffice
            ? `Yes${person.outOfOfficeUntil ? ` until ${person.outOfOfficeUntil}` : ""}`
            : "No"}
        </dd>
      </dl>
      <form className={styles.tabBody} onSubmit={onSave}>
        <TextField
          label="Name"
          value={name}
          maxLength={80}
          onChange={(e) => setName(e.target.value)}
        />
        <TextField
          label="Job title"
          value={jobTitle}
          maxLength={80}
          onChange={(e) => setJobTitle(e.target.value)}
        />
        <Button type="submit" className={styles.alignStart} disabled={saving}>
          Save details
        </Button>
      </form>
    </div>
  );
}

function AccessTab({
  person,
  organisations,
  onChanged,
  run,
}: {
  person: Supporter;
  organisations: Organisation[];
  onChanged: (s: Supporter | null) => void;
  run: <T>(action: () => Promise<T>) => Promise<T>;
}) {
  const toast = useToast();
  const [role, setRole] = useState<SupporterRole>(person.role);
  const [all, setAll] = useState(person.allOrganisations);
  const [chosen, setChosen] = useState<number[]>(person.organisationIds);

  async function onRole() {
    try {
      onChanged(await run(() => changeRole(person.id, role)));
      toast.show(`Role changed to ${ROLE_LABEL[role]}. Their sessions have ended.`, "success");
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
    }
  }

  async function onAccess() {
    try {
      await setOrganisationAccess(person.id, all, all ? [] : chosen);
      onChanged(null);
      toast.show("Organisation access saved. Their sessions have ended.", "success");
    } catch (e) {
      toast.showError(errorText(e));
    }
  }

  return (
    <div className={styles.tabBody}>
      <RadioGroup
        legend="Role"
        name={`role-${person.id}`}
        options={ROLE_OPTIONS}
        value={role}
        onChange={(v) => setRole(v === "ADMIN" ? "ADMIN" : "AGENT")}
        hint="Changing the role asks for your two-step code again and signs them out."
      />
      <GuardedButton
        reason={person.roleChangeBlockedReason}
        onClick={onRole}
        disabled={role === person.role}
        className={styles.alignStart}
      >
        Change role
      </GuardedButton>
      {person.role === "ADMIN" ? (
        <p className={styles.subtle}>Admins always see every organisation.</p>
      ) : (
        <fieldset className={styles.plainFieldset}>
          <legend className={styles.panelName}>Organisations</legend>
          <Checkbox label="All organisations" checked={all} onChange={() => setAll((v) => !v)} />
          {!all &&
            organisations.map((o) => (
              <Checkbox
                key={o.id}
                label={`${o.name}${o.prefix ? ` (${o.prefix})` : ""}`}
                checked={chosen.includes(o.id)}
                onChange={() =>
                  setChosen((c) => (c.includes(o.id) ? c.filter((x) => x !== o.id) : [...c, o.id]))
                }
              />
            ))}
          <Button type="button" className={styles.alignStart} onClick={onAccess}>
            Save organisation access
          </Button>
        </fieldset>
      )}
    </div>
  );
}

/**
 * Two-step exemption for one engineer: reason and end date (7 days by default,
 * at most 30), step-up, and "End now". Admins are never exempt; the server enforces every rule.
 */
function ExemptionSection({
  person,
  onChanged,
  run,
}: {
  person: Supporter;
  onChanged: (s: Supporter | null) => void;
  run: <T>(action: () => Promise<T>) => Promise<T>;
}) {
  const toast = useToast();
  const report = useReportError();
  const today = new Date();
  const [reason, setReason] = useState("");
  const [day, setDay] = useState(isoDay(addDays(today, DEFAULT_EXEMPTION_DAYS)));
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  if (person.self) return null;
  if (person.role !== "AGENT") {
    return (
      <section className={styles.actionBlock} aria-labelledby="exemption-heading">
        <h3 id="exemption-heading">Pause two-step sign-in</h3>
        <p className={styles.reason}>Admins always use two-step sign-in.</p>
      </section>
    );
  }
  if (person.status !== "ACTIVE") return null;

  async function onGrant(event: FormEvent) {
    event.preventDefault();
    setError("");
    const text = reason.trim();
    if (!text) {
      setError("Give a reason.");
      return;
    }
    const endsAt = exemptionEndsAt(day);
    if (!endsAt) {
      setError(`Choose an end date within the next ${MAX_EXEMPTION_DAYS} days.`);
      return;
    }
    setBusy(true);
    try {
      await run(() => grantMfaExemption(person.id, text, endsAt));
      toast.show("Two-step sign-in paused. They have been sent an email.", "success");
      setReason("");
      onChanged(null);
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function onEnd() {
    setBusy(true);
    try {
      await run(() => revokeMfaExemption(person.id));
      toast.show("Two-step sign-in is back on. They have been signed out everywhere.", "success");
      onChanged(null);
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.actionBlock} aria-labelledby="exemption-heading">
      <h3 id="exemption-heading">Pause two-step sign-in</h3>
      {person.mfaExemptUntil ? (
        <>
          <p>
            <strong>Exempt until {exemptionDate(person.mfaExemptUntil)}</strong>
          </p>
          {person.mfaExemptReason && <p>Reason: {person.mfaExemptReason}</p>}
          <p className={styles.subtle}>
            They sign in with their password only until then. Ending it now signs them out
            everywhere.
          </p>
          <div className={styles.actions}>
            <Button variant="danger" disabled={busy} onClick={onEnd}>
              End exemption now
            </Button>
          </div>
        </>
      ) : (
        <form onSubmit={onGrant} noValidate>
          <p className={styles.subtle}>
            For an engineer who cannot use their authenticator for a while (for example a lost
            phone). They sign in with their password only until the end date. Use it sparingly:
            their password alone then protects every client.
          </p>
          <Textarea
            label="Reason"
            hint={`Required, up to ${MAX_REASON} characters. Shown to admins only.`}
            maxLength={MAX_REASON}
            rows={2}
            value={reason}
            onChange={(e) => setReason(e.target.value.replace(/[\r\n]+/g, " "))}
          />
          <TextField
            label="End date"
            type="date"
            fieldSize="sm"
            hint={`${DEFAULT_EXEMPTION_DAYS} days by default, at most ${MAX_EXEMPTION_DAYS}.`}
            min={isoDay(addDays(today, 1))}
            max={isoDay(addDays(today, MAX_EXEMPTION_DAYS))}
            value={day}
            onChange={(e) => setDay(e.target.value)}
          />
          {error && (
            <p role="alert" className={styles.errorText}>
              {error}
            </p>
          )}
          <div className={styles.actions}>
            <Button type="submit" disabled={busy}>
              Pause two-step sign-in
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

function SecurityTab({
  person,
  onChanged,
  onResent,
  run,
}: {
  person: Supporter;
  onChanged: (s: Supporter | null) => void;
  onResent: (r: InviteResult, email: string) => void;
  run: <T>(action: () => Promise<T>) => Promise<T>;
}) {
  const toast = useToast();
  const self = person.self ? OWN_ACCOUNT : null;

  async function act(action: () => Promise<unknown>, done: string, stepUp = false) {
    try {
      await (stepUp ? run(action) : action());
      toast.show(done, "success");
      onChanged(null);
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
    }
  }

  if (person.status === "INVITED") {
    return (
      <div className={styles.tabBody}>
        <p>This person has not accepted their invitation yet.</p>
        <div className={styles.actions}>
          <Button
            onClick={async () => {
              try {
                onResent(await run(() => resendInvitation(person.id)), person.email);
                onChanged(null);
              } catch (e) {
                if (!(e instanceof StepUpCancelled)) toast.showError(errorText(e));
              }
            }}
          >
            Resend invitation
          </Button>
          <Button
            variant="danger"
            onClick={() => act(() => revokeInvitation(person.id), "Invitation revoked.")}
          >
            Revoke invitation
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.tabBody}>
      <p>
        Two-step sign-in:{" "}
        {person.mfaExemptUntil
          ? `Exempt until ${exemptionDate(person.mfaExemptUntil)}`
          : person.mfaEnrolled
            ? "On"
            : "Not set up"}
      </p>
      {person.self && (
        <p>
          <Link to="/agent/settings/security">Open My settings - Security</Link>
        </p>
      )}
      <div className={styles.actions}>
        <GuardedButton
          reason={self}
          onClick={() =>
            act(
              () => resetSupporterMfa(person.id),
              "Two-step sign-in reset. They set it up again at their next sign-in.",
              true,
            )
          }
        >
          Reset two-step sign-in
        </GuardedButton>
        <GuardedButton
          reason={self}
          onClick={() =>
            act(() => signOutSupporterEverywhere(person.id), "Signed out everywhere.", true)
          }
        >
          Sign out everywhere
        </GuardedButton>
        <GuardedButton
          reason={self ?? (person.status !== "ACTIVE" ? "Only active agents." : null)}
          onClick={() =>
            act(() => sendPasswordReset(person.id), "Password reset link sent to their email.")
          }
        >
          Send password reset link
        </GuardedButton>
      </div>
      <ExemptionSection person={person} onChanged={onChanged} run={run} />
    </div>
  );
}

function DeactivateWizard({
  person,
  onClose,
  onDone,
}: {
  person: Supporter;
  onClose: () => void;
  onDone: (s: Supporter) => void;
}) {
  const toast = useToast();
  const report = useReportError();
  const [plan, setPlan] = useState<DeactivationPlan | null>(null);
  const [error, setError] = useState("");
  const [step, setStep] = useState<1 | 2>(1);
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchDeactivationPlan(person.id, controller.signal)
      .then(setPlan)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [person.id]);

  const targetName =
    target === "" ? "Unassigned" : plan?.reassignTo.find((s) => String(s.id) === target)?.name;

  async function confirm() {
    setBusy(true);
    try {
      const done = await deactivateSupporter(person.id, target === "" ? null : Number(target));
      toast.show(`${person.name} is deactivated and signed out everywhere.`, "success");
      onDone(done);
    } catch (e) {
      setError(errorText(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  const blocked = plan?.blockedReason ?? null;

  return (
    <Modal
      open
      title={`Deactivate ${person.name} - step ${step} of 2`}
      onClose={onClose}
      footer={
        step === 1 ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              variant="primary"
              disabled={!plan || Boolean(blocked)}
              onClick={() => setStep(2)}
            >
              Next
            </Button>
          </>
        ) : (
          <>
            <Button onClick={() => setStep(1)}>Back</Button>
            <Button variant="danger" disabled={busy} onClick={confirm}>
              Deactivate
            </Button>
          </>
        )
      }
    >
      {error && (
        <p role="alert" className={styles.errorText}>
          {error}
        </p>
      )}
      {!plan && !error && <p>Loading...</p>}
      {plan && step === 1 && (
        <>
          {blocked && <p className={styles.reason}>{blocked}</p>}
          <p>
            {plan.openTickets === 0
              ? "They have no open tickets."
              : `They have ${plan.openTickets} open ${plan.openTickets === 1 ? "ticket" : "tickets"}.`}
          </p>
          <Select
            label="Reassign their open tickets to"
            fieldSize="md"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            options={[
              { value: "", label: "Unassigned" },
              ...plan.reassignTo.map((s) => ({ value: String(s.id), label: s.name })),
            ]}
          />
          <p className={styles.formNote}>Default-assignee settings that name them are cleared.</p>
        </>
      )}
      {plan && step === 2 && (
        <>
          <p>
            {person.name} will be signed out everywhere at once and cannot sign in until an admin
            reactivates them. Nothing is deleted.
          </p>
          <p>
            Open tickets: {plan.openTickets}, moving to: {targetName}.
          </p>
        </>
      )}
    </Modal>
  );
}

function SupporterPanel({
  person,
  organisations,
  onChanged,
  onResent,
}: {
  person: Supporter;
  organisations: Organisation[];
  onChanged: (s: Supporter | null) => void;
  onResent: (r: InviteResult, email: string) => void;
}) {
  const toast = useToast();
  const { run, dialog } = useStepUp();
  const [wizard, setWizard] = useState(false);

  return (
    <SidePanel
      label="Agent detail"
      header={
        <div className={styles.panelHead}>
          <Avatar initials={initials(person.name)} size="lg" />
          <div>
            <h2 className={styles.panelName}>{person.name}</h2>
            <div className={styles.subtle}>
              {ROLE_LABEL[person.role]} - {STATUS_LABEL[person.status]}
              {person.jobTitle ? ` - ${person.jobTitle}` : ""}
            </div>
          </div>
        </div>
      }
      footer={
        person.status === "DEACTIVATED" ? (
          <Button
            onClick={async () => {
              try {
                onChanged(await reactivateSupporter(person.id));
                toast.show(`${person.name} can sign in again.`, "success");
              } catch (e) {
                toast.showError(errorText(e));
              }
            }}
          >
            Reactivate
          </Button>
        ) : person.status === "ACTIVE" ? (
          <GuardedButton
            variant="danger"
            reason={person.deactivateBlockedReason}
            onClick={() => setWizard(true)}
          >
            {person.openTickets > 0
              ? `Deactivate and reassign ${person.openTickets} tickets`
              : "Deactivate"}
          </GuardedButton>
        ) : undefined
      }
    >
      <Tabs
        key={person.id}
        label="Agent sections"
        items={[
          {
            id: "overview",
            label: "Overview",
            content: <OverviewTab key={person.id} person={person} onChanged={onChanged} />,
          },
          {
            id: "access",
            label: "Access",
            content: (
              <AccessTab
                key={`${person.id}-${person.role}`}
                person={person}
                organisations={organisations}
                onChanged={onChanged}
                run={run}
              />
            ),
          },
          {
            id: "security",
            label: "Security",
            content: (
              <SecurityTab person={person} onChanged={onChanged} onResent={onResent} run={run} />
            ),
          },
          { id: "activity", label: "Activity", content: <ActivityTab person={person} /> },
        ]}
      />
      {wizard && (
        <DeactivateWizard
          person={person}
          onClose={() => setWizard(false)}
          onDone={(s) => {
            setWizard(false);
            onChanged(s);
          }}
        />
      )}
      {dialog}
    </SidePanel>
  );
}

function InviteModal({
  open,
  organisations,
  onClose,
  onInvited,
}: {
  open: boolean;
  organisations: Organisation[];
  onClose: () => void;
  onInvited: (r: InviteResult, email: string) => void;
}) {
  const report = useReportError();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<SupporterRole>("AGENT");
  const [all, setAll] = useState(true);
  const [chosen, setChosen] = useState<number[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const { run, dialog } = useStepUp();

  function reset() {
    setEmail("");
    setName("");
    setRole("AGENT");
    setAll(true);
    setChosen([]);
    setError("");
  }

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    setError("");
    if (!email.trim() || !name.trim()) {
      setError("Enter an email address and a name.");
      return;
    }
    if (role === "AGENT" && !all && chosen.length === 0) {
      setError("Choose at least one organisation, or all organisations.");
      return;
    }
    setBusy(true);
    try {
      // Inviting needs a fresh step-up: the dialog asks for a code and retries.
      const body = {
        email: email.trim(),
        name: name.trim(),
        role,
        allOrganisations: role === "ADMIN" || all,
        organisationIds: role === "ADMIN" || all ? [] : chosen,
      };
      const result = await run(() => inviteSupporter(body));
      const invited = email.trim();
      reset();
      onInvited(result, invited);
    } catch (err) {
      if (!(err instanceof StepUpCancelled)) setError(errorText(err));
      report(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Modal
        open={open}
        title="Invite agent"
        onClose={() => {
          reset();
          onClose();
        }}
        footer={
          <>
            <Button
              onClick={() => {
                reset();
                onClose();
              }}
            >
              Cancel
            </Button>
            <Button variant="primary" disabled={busy} onClick={() => submit()}>
              Send invitation
            </Button>
          </>
        }
      >
        <form onSubmit={submit} className={styles.tabBody}>
          {error && (
            <p role="alert" className={styles.errorText}>
              {error}
            </p>
          )}
          <TextField
            label="Email"
            type="email"
            fieldSize="md"
            autoComplete="off"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <TextField
            label="Name"
            autoComplete="off"
            maxLength={80}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <RadioGroup
            legend="Role"
            name="invite-role"
            options={ROLE_OPTIONS}
            value={role}
            onChange={(v) => setRole(v === "ADMIN" ? "ADMIN" : "AGENT")}
          />
          {role === "AGENT" && (
            <fieldset className={styles.plainFieldset}>
              <legend className={styles.panelName}>Organisations</legend>
              <Checkbox
                label="All organisations"
                checked={all}
                onChange={() => setAll((v) => !v)}
              />
              {!all &&
                organisations.map((o) => (
                  <Checkbox
                    key={o.id}
                    label={`${o.name}${o.prefix ? ` (${o.prefix})` : ""}`}
                    checked={chosen.includes(o.id)}
                    onChange={() =>
                      setChosen((c) =>
                        c.includes(o.id) ? c.filter((x) => x !== o.id) : [...c, o.id],
                      )
                    }
                  />
                ))}
            </fieldset>
          )}
          <p className={styles.reason}>
            We email them a link that is valid for 48 hours and works once. Opening it and choosing
            a password is enough to accept.
          </p>
        </form>
      </Modal>
      {dialog}
    </>
  );
}

function InviteSentModal({
  invite,
  onClose,
}: {
  invite: { result: InviteResult; email: string } | null;
  onClose: () => void;
}) {
  return (
    <Modal
      open={invite !== null}
      title="Invitation sent"
      onClose={onClose}
      footer={
        <Button variant="primary" onClick={onClose}>
          Done
        </Button>
      }
    >
      {invite && (
        <>
          <p>
            We emailed a link to {invite.email}. They open it and choose a password to accept. Any
            earlier link for this person no longer works.
          </p>
          <p className={styles.reason}>Link valid until {when(invite.result.expiresAt)}.</p>
        </>
      )}
    </Modal>
  );
}

/** Admin - Manage supporters (C18), on the real API. */
export function SupportersPage() {
  const report = useReportError();
  const [roleFilter, setRoleFilter] = useState<SupporterRole | "">("");
  const [statusFilter, setStatusFilter] = useState<SupporterStatus | "">("");
  const [rows, setRows] = useState<Supporter[] | null>(null);
  const [error, setError] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [invite, setInvite] = useState<{ result: InviteResult; email: string } | null>(null);
  const [reloads, setReloads] = useState(0);
  const reload = useCallback(() => setReloads((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    listSupporters({ role: roleFilter, status: statusFilter }, controller.signal)
      .then((list) => {
        setRows(list);
        setError("");
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [roleFilter, statusFilter, reloads]);

  useEffect(() => {
    const controller = new AbortController();
    listOrganisations(controller.signal)
      .then(setOrganisations)
      .catch((e: unknown) => {
        setOrganisations([]);
        report(e, "Organisations could not be loaded.");
      });
    return () => controller.abort();
  }, [report]);

  const list = rows ?? [];
  const selected = list.find((s) => s.id === selectedId) ?? null;
  const active = list.filter((s) => s.status === "ACTIVE").length;
  const invited = list.filter((s) => s.status === "INVITED").length;

  function onChanged(updated: Supporter | null) {
    if (updated) {
      setRows((r) => (r ? r.map((s) => (s.id === updated.id ? updated : s)) : r));
    }
    reload();
  }

  const columns: Column<Supporter>[] = [
    {
      key: "name",
      header: "Name",
      render: (row) => (
        <div className={styles.personCell}>
          <button
            type="button"
            className={styles.personName}
            aria-pressed={row.id === selectedId}
            onClick={() => setSelectedId(row.id)}
          >
            {row.name}
          </button>
          <div className={styles.subtle}>
            <Identifier value={row.email} />
          </div>
        </div>
      ),
    },
    { key: "role", header: "Role", nowrap: true, render: (row) => ROLE_LABEL[row.role] },
    {
      key: "status",
      header: "Status",
      nowrap: true,
      render: (row) => <SupporterStatusBadge status={STATUS_LABEL[row.status]} />,
    },
    {
      key: "orgs",
      header: "Organisations",
      muted: true,
      render: (row) => <span className={styles.orgsCell}>{orgNames(row, organisations)}</span>,
    },
    {
      key: "mfa",
      header: "2FA",
      nowrap: true,
      render: (row) => <TwoStepCell row={row} />,
    },
    {
      key: "last",
      header: <span className={styles.noWrap}>Last sign-in</span>,
      label: "Last sign-in",
      muted: true,
      nowrap: true,
      render: (row) => (row.status === "INVITED" ? "-" : lastSignIn(row.lastSignInAt)),
    },
    { key: "open", header: "Open", nowrap: true, render: (row) => row.openTickets },
    {
      key: "ooo",
      header: "Away",
      render: (row) => (row.outOfOffice ? "Out of office" : ""),
    },
  ];

  return (
    <>
      <div className={styles.split}>
        <div className={styles.columnNarrow}>
          <PageHeader
            title="Agents"
            meta={`${active} active, ${invited} invited`}
            actions={
              <Button variant="primary" onClick={() => setInviteOpen(true)}>
                Invite agent
              </Button>
            }
          />
          <div className={styles.filtersRow}>
            <Select
              label="Role"
              value={roleFilter}
              onChange={(e) =>
                setRoleFilter(
                  e.target.value === "AGENT" || e.target.value === "ADMIN" ? e.target.value : "",
                )
              }
              options={[
                { value: "", label: "All roles" },
                { value: "AGENT", label: "Agents" },
                { value: "ADMIN", label: "Admins" },
              ]}
            />
            <Select
              label="Status"
              value={statusFilter}
              onChange={(e) => {
                const v = e.target.value;
                setStatusFilter(v === "ACTIVE" || v === "INVITED" || v === "DEACTIVATED" ? v : "");
              }}
              options={[
                { value: "", label: "All statuses" },
                { value: "ACTIVE", label: "Active" },
                { value: "INVITED", label: "Invited" },
                { value: "DEACTIVATED", label: "Deactivated" },
              ]}
            />
          </div>
          {error && (
            <p role="alert" className={styles.errorText}>
              {error}
            </p>
          )}
          <DataTable
            caption="Agents"
            columns={columns}
            rows={list}
            loading={rows === null && !error}
            rowKey={(row) => String(row.id)}
            selectedKey={selectedId === null ? undefined : String(selectedId)}
            emptyMessage="No agents match these filters."
            minWidth={860}
            stackOnPhone
          />
        </div>
        {selected && (
          <SupporterPanel
            key={selected.id}
            person={selected}
            organisations={organisations}
            onChanged={onChanged}
            onResent={(result, email) => setInvite({ result, email })}
          />
        )}
      </div>

      <InviteModal
        open={inviteOpen}
        organisations={organisations}
        onClose={() => setInviteOpen(false)}
        onInvited={(result, email) => {
          setInviteOpen(false);
          setInvite({ result, email });
          setSelectedId(result.userId);
          reload();
        }}
      />
      <InviteSentModal invite={invite} onClose={() => setInvite(null)} />
    </>
  );
}
