import { useCallback, useEffect, useRef, useState } from "react";
import {
  getSlaPanel,
  type SlaPanelData,
  type SlaPanelState,
  type SlaPanelTarget,
} from "../../api/agent";
import { formatDateTime } from "../../format";
import {
  AMBER_SHARE,
  formatClockStart,
  formatDuration,
  formatLeft,
  liveRemaining,
  RED_SHARE,
  RESYNC_MS,
  RETRY_MS,
  TICK_MS,
  type Synced,
} from "./slaPanelFormat";
import styles from "./SlaPanel.module.css";

/**
 * The agent-only "SLA" card in the ticket header, right of the title (moved out of the Details
 * sidebar so agents notice it). The server works out every
 * state and the business time left (no business-hours maths here); this component only counts a
 * RUNNING row down between refreshes, once a minute, and asks the server again every few minutes,
 * when a countdown reaches zero and when today's business hours end.
 *
 * Colour (always with an icon and words, never alone): amber from the warn point (80% of the target
 * used, the same point as the queue's "At risk"), red in the last 10% of the target. 10% rather than
 * a fixed 30 minutes, so a 30-minute P1 first response is not red from the start.
 */

type Tone = "ok" | "warn" | "danger" | "paused" | "met" | "breached";

/** The state word shown with an icon on every row: never colour alone. */
export type SlaStatus = "onTrack" | "atRisk" | "paused" | "met" | "breached";

const STATUS_WORDS: Record<SlaStatus, string> = {
  onTrack: "On track",
  atRisk: "At risk",
  paused: "Paused",
  met: "Met",
  breached: "Breached",
};

const STATUS_CLASS: Record<SlaStatus, string | undefined> = {
  onTrack: styles.statusOk,
  atRisk: styles.statusRisk,
  paused: styles.statusPaused,
  met: styles.statusOk,
  breached: styles.statusBreached,
};

function statusOf(t: SlaPanelTarget, tone: Tone): SlaStatus {
  if (t.state === "MET") return "met";
  if (t.state === "BREACHED") return "breached";
  if (t.state === "PAUSED") return "paused";
  return tone === "warn" || tone === "danger" ? "atRisk" : "onTrack";
}

/** Worst first, for the card's accent edge (decoration only; every row has its own words). */
const WORST: Tone[] = ["breached", "danger", "warn", "paused", "ok", "met"];

function serverNow(synced: Synced, nowMs: number): number {
  return Date.parse(synced.panel.asOf) + Math.max(0, nowMs - synced.receivedAt);
}

function toneOf(t: SlaPanelTarget, remaining: number | null, atServer: number): Tone {
  if (t.state === "MET") return "met";
  if (t.state === "BREACHED") return "breached";
  if (remaining !== null && remaining <= Math.max(1, Math.ceil(t.targetMinutes * RED_SHARE))) {
    return "danger";
  }
  const warned = t.warnAt !== null && atServer >= Date.parse(t.warnAt);
  if (warned || (remaining !== null && remaining <= t.targetMinutes * AMBER_SHARE)) return "warn";
  return t.state === "RUNNING" ? "ok" : "paused";
}

const STATE_WORDS: Record<SlaPanelState, string> = {
  RUNNING: "running",
  PAUSED: "paused",
  STOPPED_OUTSIDE_HOURS: "clock stopped outside business hours",
  MET: "met",
  BREACHED: "breached",
  NOT_APPLICABLE: "not applicable",
};

/** The state icon (decoration: always next to words). Shared with the queue's First response column. */
export function SlaIcon({ status }: { status: SlaStatus }) {
  const common = {
    width: 16,
    height: 16,
    viewBox: "0 0 16 16",
    "aria-hidden": true,
    focusable: false,
    className: styles.icon,
  } as const;
  const line = { fill: "none", stroke: "currentColor", strokeWidth: 1.8 } as const;
  switch (status) {
    case "met":
      return (
        <svg {...common}>
          <path d="M3 8.5l3 3 7-7" {...line} strokeWidth={2} />
        </svg>
      );
    case "breached":
      return (
        <svg {...common}>
          <path d="M4 4l8 8M12 4l-8 8" {...line} strokeWidth={2} />
        </svg>
      );
    case "paused":
      return (
        <svg {...common}>
          <path d="M6 3.5v9M10 3.5v9" {...line} strokeWidth={2} />
        </svg>
      );
    case "atRisk":
      return (
        <svg {...common}>
          <path d="M8 2L1.5 13.5h13z" {...line} strokeLinejoin="round" />
          <path d="M8 6.5v3.2M8 11.2v.6" {...line} />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <circle cx="8" cy="8" r="6" {...line} />
          <path d="M8 4.5V8l2.5 1.5" {...line} />
        </svg>
      );
  }
}

/** The big value: the time left, the time over a breached target ("1h 15m" + " over"), or "Met". */
function visibleText(t: SlaPanelTarget, remaining: number | null, dayMinutes: number): string {
  switch (t.state) {
    case "MET":
      return "Met";
    case "BREACHED":
      return formatDuration(t.breachedByMinutes ?? 0, dayMinutes);
    case "NOT_APPLICABLE":
      return "";
    default:
      return remaining === null ? "" : formatLeft(remaining, dayMinutes);
  }
}

/** Screen-reader text for a row: the state in words, with the time left or taken. */
function srText(
  kind: "firstResponse" | "resolution",
  t: SlaPanelTarget,
  remaining: number | null,
  dayMinutes: number,
): string {
  const left = remaining === null ? "" : `${formatLeft(remaining, dayMinutes)} left`;
  switch (t.state) {
    case "MET":
      return `Met - ${kind === "firstResponse" ? "answered" : "resolved"} in ${formatDuration(t.usedMinutes, dayMinutes)}`;
    case "BREACHED":
      return `Breached by ${formatDuration(t.breachedByMinutes ?? 0, dayMinutes)}`;
    case "PAUSED":
      return `${pausedWords(t)}${left ? `, ${left}` : ""}`;
    case "STOPPED_OUTSIDE_HOURS":
      return `${left}${left ? ", " : ""}clock stopped outside business hours`;
    default:
      return left;
  }
}

function pausedWords(t: SlaPanelTarget): string {
  return `Paused - waiting on ${t.pausedReason === "VENDOR" ? "vendor" : "client"}`;
}

function detailText(t: SlaPanelTarget, dayMinutes: number): string {
  const target = `Target ${formatDuration(t.targetMinutes, dayMinutes)}`;
  switch (t.state) {
    case "MET":
      return t.metAt ? `${target}, met ${formatDateTime(t.metAt)}` : target;
    case "BREACHED":
      return t.dueAt ? `${target}, was due ${formatDateTime(t.dueAt)}` : target;
    case "PAUSED":
      return `${pausedWords(t)}. ${target}, no due time while paused`;
    case "STOPPED_OUTSIDE_HOURS":
      return `${target}, clock stopped until ${t.nextClockStart ? formatClockStart(t.nextClockStart) : "business hours"}`;
    default:
      return t.dueAt ? `${target}, due ${formatDateTime(t.dueAt)}` : target;
  }
}

const LABELS = { firstResponse: "First response", resolution: "Resolution" } as const;

function isFinal(s: SlaPanelState) {
  return s === "MET" || s === "BREACHED" || s === "NOT_APPLICABLE";
}

export function SlaPanel({
  ticketId,
  initial,
  load = getSlaPanel,
  className,
}: {
  ticketId: number;
  /** Placement from the page (the ticket header). */
  className?: string;
  /** The panel from the ticket detail; give the component a `key` that changes with it. */
  initial: SlaPanelData;
  load?: (id: number, signal?: AbortSignal) => Promise<SlaPanelData | null>;
}) {
  const [synced, setSynced] = useState<Synced>(() => ({
    panel: initial,
    receivedAt: Date.now(),
  }));
  const [now, setNow] = useState(() => Date.now());
  // Failed refreshes in a row; 0 = the times are fresh.
  const [failures, setFailures] = useState(0);
  const stale = failures > 0;
  const resyncAsked = useRef(false);
  const controller = useRef<AbortController | null>(null);

  const resync = useCallback(() => {
    controller.current?.abort();
    const c = new AbortController();
    controller.current = c;
    // A background refresh fails quietly but visibly (no pop-up, a muted line).
    const failed = () => setFailures((n) => n + 1);
    load(ticketId, c.signal).then(
      (p) => {
        if (c.signal.aborted) return;
        if (p) {
          resyncAsked.current = false;
          setSynced({ panel: p, receivedAt: Date.now() });
          setNow(Date.now());
          setFailures(0);
        } else {
          failed();
        }
      },
      () => {
        if (!c.signal.aborted) failed();
      },
    );
  }, [load, ticketId]);

  useEffect(() => () => controller.current?.abort(), []);

  // After a failed refresh, try again soon, so the warning clears on the next success rather than
  // at the next 5-minute sync.
  useEffect(() => {
    if (failures === 0) return;
    const id = window.setTimeout(resync, RETRY_MS);
    return () => window.clearTimeout(id);
  }, [failures, resync]);

  const { panel } = synced;
  const rows = (["firstResponse", "resolution"] as const).filter(
    (k) => panel[k].state !== "NOT_APPLICABLE",
  );
  const running = rows.some((k) => panel[k].state === "RUNNING");
  const allFinal = rows.every((k) => isFinal(panel[k].state));

  // Tick once a minute, only while a row is running.
  useEffect(() => {
    if (!running) return;
    const id = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(id);
  }, [running, synced]);

  // Re-sync every few minutes while anything can still change.
  useEffect(() => {
    if (allFinal) return;
    const id = window.setInterval(resync, RESYNC_MS);
    return () => window.clearInterval(id);
  }, [allFinal, resync, synced]);

  const atServer = serverNow(synced, now);

  // Ask the server as soon as a countdown reaches zero or today's hours end.
  useEffect(() => {
    if (resyncAsked.current) return;
    const due = rows.some((k) => {
      const t = panel[k];
      if (t.state !== "RUNNING") return false;
      const left = liveRemaining(t, synced, now);
      return left === 0 || (t.clockStopsAt !== null && atServer >= Date.parse(t.clockStopsAt));
    });
    if (due) {
      resyncAsked.current = true;
      resync();
    }
  });

  if (rows.length === 0) return null;
  const announcement = rows.map((k) => `${LABELS[k]}: ${STATE_WORDS[panel[k].state]}.`).join(" ");

  const view = rows.map((k) => {
    const t = panel[k];
    const left = liveRemaining(t, synced, now);
    return { k, t, left, tone: toneOf(t, left, atServer) };
  });
  const worst = WORST.find((w) => view.some((v) => v.tone === w)) ?? "ok";

  return (
    <section
      className={`${styles.sla} ${className ?? ""}`}
      aria-labelledby={`sla-heading-${ticketId}`}
      data-testid="sla-card"
      data-worst={worst}
    >
      <h2 id={`sla-heading-${ticketId}`} className={styles.heading}>
        SLA
      </h2>
      <ul className={styles.rows}>
        {view.map(({ k, t, left, tone }) => {
          const status = statusOf(t, tone);
          const text = visibleText(t, left, panel.businessDayMinutes);
          const sr = srText(k, t, left, panel.businessDayMinutes);
          const detail = `${detailText(t, panel.businessDayMinutes)}. Policy: ${panel.policyLabel}`;
          const final = t.state === "MET";
          return (
            <li key={k} className={styles.row} data-testid={`sla-${k}`} data-tone={tone}>
              <span className={styles.label} aria-hidden="true">
                {LABELS[k]}
              </span>
              {/* The tooltips are for the mouse only; screen readers get the hidden text below. */}
              {final ? (
                <span
                  className={`${styles.line} ${styles.final} ${styles[tone]}`}
                  data-testid={`sla-state-${k}`}
                  data-status={status}
                  aria-hidden="true"
                  title={detail}
                >
                  <SlaIcon status={status} />
                  <span data-testid={`sla-text-${k}`}>{text}</span>
                </span>
              ) : (
                <span className={styles.line} aria-hidden="true">
                  <span
                    className={`${styles.value} ${styles[tone]}`}
                    data-testid={`sla-text-${k}`}
                    title={detail}
                  >
                    {text}
                    {t.state === "BREACHED" && <span className={styles.over}> over</span>}
                  </span>
                  <span
                    className={`${styles.status} ${STATUS_CLASS[status] ?? ""}`}
                    data-testid={`sla-state-${k}`}
                    data-status={status}
                    title={detail}
                  >
                    <SlaIcon status={status} />
                    {STATUS_WORDS[status]}
                  </span>
                </span>
              )}
              <span className={styles.srOnly}>
                {`${LABELS[k]}: ${status === "onTrack" || status === "atRisk" ? `${STATUS_WORDS[status]}, ` : ""}${sr}. ${detail}`}
              </span>
            </li>
          );
        })}
      </ul>
      {stale && (
        <p className={styles.detail}>
          The SLA times could not be refreshed; they may be out of date.
        </p>
      )}
      <p className={styles.srOnly} aria-live="polite">
        {announcement}
      </p>
    </section>
  );
}
