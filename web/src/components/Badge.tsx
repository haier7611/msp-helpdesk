import type { ReactNode } from "react";
import type { Priority, SlaState, SupporterStatus, TicketStatus } from "../mocks/types";
import styles from "./Badge.module.css";

export type BadgeTone =
  "p1" | "p2" | "p3" | "p4" | "breached" | "risk" | "ok" | "paused" | "brand" | "neutral";

/** A pill with visible text; colour is never the only signal (WCAG 1.4.1). */
export function Badge({
  tone,
  children,
  label,
}: {
  tone: BadgeTone;
  children: ReactNode;
  /** Longer accessible name, e.g. "Priority P1 Critical", when the visible text is short. */
  label?: string;
}) {
  return (
    <span className={`${styles.badge} ${styles[tone]}`}>
      {label ? (
        <>
          <span aria-hidden="true">{children}</span>
          <span className="visually-hidden">{label}</span>
        </>
      ) : (
        children
      )}
    </span>
  );
}

const PRIORITY_NAMES: Record<Priority, string> = {
  P1: "Critical",
  P2: "High",
  P3: "Medium",
  P4: "Low",
};

export function PriorityBadge({ priority, long = false }: { priority: Priority; long?: boolean }) {
  const name = `${priority} ${PRIORITY_NAMES[priority]}`;
  return (
    <Badge tone={priority.toLowerCase() as BadgeTone} label={long ? undefined : `Priority ${name}`}>
      {long ? name : priority}
    </Badge>
  );
}

const STATUS_TONE: Record<TicketStatus, BadgeTone> = {
  New: "brand",
  Open: "brand",
  "Pending on client": "neutral",
  "Pending on vendor": "neutral",
  Resolved: "ok",
  Closed: "neutral",
};

export function StatusBadge({ status }: { status: TicketStatus }) {
  return <Badge tone={STATUS_TONE[status]}>{status}</Badge>;
}

const SLA_NAMES: Record<SlaState, string> = {
  breached: "Breached",
  risk: "At risk",
  ok: "On track",
  paused: "Paused",
};

/** SLA chip. The visible text carries the detail ("Breached 12 min"); the state name is always
 *  in the accessible text too, so it is never conveyed by colour alone. */
export function SlaBadge({ state, text }: { state: SlaState; text?: string }) {
  const visible = text ?? SLA_NAMES[state];
  const startsWithState = visible.toLowerCase().startsWith(SLA_NAMES[state].toLowerCase());
  return (
    <Badge tone={state} label={startsWithState ? undefined : `${SLA_NAMES[state]}: ${visible}`}>
      {visible}
    </Badge>
  );
}

const SUPPORTER_TONE: Record<SupporterStatus, BadgeTone> = {
  Active: "ok",
  Invited: "brand",
  Deactivated: "neutral",
};

export function SupporterStatusBadge({ status }: { status: SupporterStatus }) {
  return <Badge tone={SUPPORTER_TONE[status]}>{status}</Badge>;
}
