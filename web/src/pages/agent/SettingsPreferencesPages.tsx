import { useEffect, useState, type FormEvent } from "react";
import {
  fetchNotifications,
  fetchOutOfOffice,
  fetchPreferences,
  saveNotifications,
  saveOutOfOffice,
  savePreferences,
  type NotificationChoice,
  type NotificationEvent,
  type Notifications,
  type OutOfOffice,
  type Preferences,
} from "../../api/settings";
import { Button, Checkbox, RadioGroup, Select, TextField } from "../../components/forms";
import { useToast } from "../../components/toastContext";
import { setTheme } from "../../theme";
import { errorText } from "../auth/authHelpers";
import styles from "../pages.module.css";

/** Loads one settings resource; shows loading and error states the same way on every tab. */
function useLoaded<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal)
      .then(setValue)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [load]);
  return { value, setValue, error };
}

const QUEUE_VIEWS = [
  { value: "my-open", label: "My open tickets" },
  { value: "unassigned", label: "Unassigned" },
  { value: "all-open", label: "All open tickets" },
  { value: "triage", label: "Email triage" },
];

const TIMEZONES = [
  "Asia/Ho_Chi_Minh",
  "Asia/Singapore",
  "Asia/Bangkok",
  "Asia/Tokyo",
  "Australia/Sydney",
  "Europe/London",
  "Europe/Berlin",
  "America/New_York",
  "America/Los_Angeles",
  "UTC",
];

const DATE_FORMATS = [
  { value: "DMY", label: "18/11/2026 (day first)" },
  { value: "MDY", label: "11/18/2026 (month first)" },
  { value: "YMD", label: "2026-11-18 (year first)" },
];

/** My settings - Preferences. */
export function SettingsPreferencesPage() {
  const toast = useToast();
  const { value, setValue, error } = useLoaded(fetchPreferences);
  const [saving, setSaving] = useState(false);

  if (error) return <LoadError title="Preferences" message={error} />;
  if (!value) return <Loading title="Preferences" />;
  const prefs = value;
  const update = (patch: Partial<Preferences>) => setValue({ ...prefs, ...patch });
  const zones = TIMEZONES.includes(prefs.timezone) ? TIMEZONES : [prefs.timezone, ...TIMEZONES];
  const views = QUEUE_VIEWS.some((v) => v.value === prefs.queueView)
    ? QUEUE_VIEWS
    : [{ value: prefs.queueView, label: prefs.queueView }, ...QUEUE_VIEWS];

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      const saved = await savePreferences(prefs);
      setValue(saved);
      setTheme(saved.theme);
      toast.show("Preferences saved.", "success");
    } catch (e) {
      toast.showError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.settingsCard} onSubmit={onSubmit}>
      <h1>Preferences</h1>
      <RadioGroup
        inline
        legend="Default composer tab"
        name="composer-tab"
        value={prefs.composerTab}
        onChange={(v) => update({ composerTab: v === "REPLY" ? "REPLY" : "INTERNAL_NOTE" })}
        options={[
          { value: "INTERNAL_NOTE", label: "Internal note" },
          { value: "REPLY", label: "Reply to client" },
        ]}
        hint="The composer opens on this tab for every ticket."
      />
      <Select
        label="Default queue view"
        fieldSize="md"
        value={prefs.queueView}
        onChange={(e) => update({ queueView: e.target.value })}
        options={views}
      />
      <Select
        label="Time zone"
        fieldSize="md"
        value={prefs.timezone}
        onChange={(e) => update({ timezone: e.target.value })}
        options={zones}
        hint="Dates and times in the app, and when your daily digest arrives."
      />
      <Select
        label="Date format"
        fieldSize="sm"
        value={prefs.dateFormat}
        onChange={(e) =>
          update({
            dateFormat:
              e.target.value === "MDY" || e.target.value === "YMD" ? e.target.value : "DMY",
          })
        }
        options={DATE_FORMATS}
      />
      <RadioGroup
        inline
        legend="Theme"
        name="theme"
        value={prefs.theme}
        onChange={(v) => update({ theme: v === "LIGHT" || v === "DARK" ? v : "SYSTEM" })}
        options={[
          { value: "SYSTEM", label: "Same as my device" },
          { value: "LIGHT", label: "Light" },
          { value: "DARK", label: "Dark" },
        ]}
      />
      <Button type="submit" variant="primary" className={styles.alignStart} disabled={saving}>
        {saving ? "Saving..." : "Save preferences"}
      </Button>
    </form>
  );
}

const EVENT_LABELS: Record<NotificationEvent, string> = {
  TICKET_ASSIGNED: "A ticket is assigned to me",
  CLIENT_REPLIED: "A client replied on my ticket",
  MENTIONED: "I am mentioned in an internal note",
  INTERNAL_NOTE_ADDED: "An internal note is added on my ticket",
  SLA_WARNING: "SLA about to breach",
  SLA_BREACHED: "SLA breached",
};

/** My settings - Notifications. Stored here; the server sends them. */
export function SettingsNotificationsPage() {
  const toast = useToast();
  const { value, setValue, error } = useLoaded(fetchNotifications);
  const [saving, setSaving] = useState(false);

  if (error) return <LoadError title="Notifications" message={error} />;
  if (!value) return <Loading title="Notifications" />;
  const current: Notifications = value;

  function toggle(event: NotificationEvent, key: "email" | "inApp") {
    setValue({
      ...current,
      events: current.events.map((e: NotificationChoice) =>
        e.event === event ? { ...e, [key]: !e[key] } : e,
      ),
    });
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      setValue(await saveNotifications(current));
      toast.show("Notification choices saved.", "success");
    } catch (err) {
      toast.showError(errorText(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.settingsCard} onSubmit={onSubmit}>
      <h1>Notifications</h1>
      <div className={styles.matrixWrap}>
        <table className={styles.matrix}>
          <caption className="visually-hidden">Notification choices per event</caption>
          <thead>
            <tr>
              <th scope="col">Event</th>
              <th scope="col" className={styles.centre}>
                Email
              </th>
              <th scope="col" className={styles.centre}>
                In the app
              </th>
            </tr>
          </thead>
          <tbody>
            {current.events.map((e) => (
              <tr key={e.event}>
                <th scope="row">{EVENT_LABELS[e.event]}</th>
                <td className={styles.centre}>
                  <input
                    type="checkbox"
                    checked={e.email}
                    aria-label={`${EVENT_LABELS[e.event]} - email`}
                    onChange={() => toggle(e.event, "email")}
                  />
                </td>
                <td className={styles.centre}>
                  <input
                    type="checkbox"
                    checked={e.inApp}
                    aria-label={`${EVENT_LABELS[e.event]} - in the app`}
                    onChange={() => toggle(e.event, "inApp")}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Checkbox
        label="Daily digest email of my open and breaching tickets (08:00 in my time zone)"
        checked={current.dailyDigest}
        onChange={() => setValue({ ...current, dailyDigest: !current.dailyDigest })}
      />
      <Button type="submit" variant="primary" className={styles.alignStart} disabled={saving}>
        {saving ? "Saving..." : "Save notifications"}
      </Button>
    </form>
  );
}

/** My settings - Out of office. Auto-assignment skips agents who are out of office. */
export function SettingsOutOfOfficePage() {
  const toast = useToast();
  const { value, setValue, error } = useLoaded(fetchOutOfOffice);
  const [saving, setSaving] = useState(false);

  if (error) return <LoadError title="Out of office" message={error} />;
  if (!value) return <Loading title="Out of office" />;
  const ooo: OutOfOffice = value;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      setValue(await saveOutOfOffice(ooo));
      toast.show(ooo.enabled ? "Out of office is on." : "Out of office is off.", "success");
    } catch (err) {
      toast.showError(errorText(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.settingsCard} onSubmit={onSubmit}>
      <h1>Out of office</h1>
      <Checkbox
        label="I am out of office"
        checked={ooo.enabled}
        onChange={() => setValue({ ...ooo, enabled: !ooo.enabled })}
      />
      <div className={styles.row}>
        <TextField
          label="From (optional)"
          type="date"
          fieldSize="sm"
          value={ooo.from ?? ""}
          onChange={(e) => setValue({ ...ooo, from: e.target.value || null })}
        />
        <TextField
          label="Until (optional)"
          type="date"
          fieldSize="sm"
          value={ooo.until ?? ""}
          onChange={(e) => setValue({ ...ooo, until: e.target.value || null })}
        />
      </div>
      <p className={styles.formNote}>
        While you are out of office, new tickets are not assigned to you automatically and the queue
        shows you as away. Clients get no automatic reply.
      </p>
      <Button type="submit" variant="primary" className={styles.alignStart} disabled={saving}>
        {saving ? "Saving..." : "Save"}
      </Button>
    </form>
  );
}

function Loading({ title }: { title: string }) {
  return (
    <div className={styles.settingsCard}>
      <h1>{title}</h1>
      <p>Loading...</p>
    </div>
  );
}

function LoadError({ title, message }: { title: string; message: string }) {
  return (
    <div className={styles.settingsCard}>
      <h1>{title}</h1>
      <p role="alert">{message}</p>
    </div>
  );
}
