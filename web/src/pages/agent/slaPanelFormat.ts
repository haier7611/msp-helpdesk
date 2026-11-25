import type { SlaPanelData, SlaPanelTarget } from "../../api/agent";

/** SLA panel helpers: timing constants, durations and the between-refresh countdown. */

export const TICK_MS = 60_000;
export const RESYNC_MS = 5 * 60_000;
/** After a failed refresh, try again this soon. */
export const RETRY_MS = 30_000;
export const RED_SHARE = 0.1;
export const AMBER_SHARE = 0.2;

export type Synced = { panel: SlaPanelData; receivedAt: number };

/** "42m", "3h 20m", "2d 3h" - a day is one day on the priority's clock (business or 24 h). */
export function formatDuration(minutes: number, dayMinutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  if (m < 60) return `${m}m`;
  if (dayMinutes <= 0 || m < dayMinutes) {
    const h = Math.floor(m / 60);
    const r = m % 60;
    return r ? `${h}h ${r}m` : `${h}h`;
  }
  const d = Math.floor(m / dayMinutes);
  const h = Math.floor((m - d * dayMinutes) / 60);
  return h ? `${d}d ${h}h` : `${d}d`;
}

/** Time left as "1d 3h 39m", "3h 39m", "12m" - a day is one day on the priority's clock. */
export function formatLeft(minutes: number, dayMinutes: number): string {
  let m = Math.max(0, Math.round(minutes));
  const parts: string[] = [];
  if (dayMinutes > 0 && m >= dayMinutes) {
    parts.push(`${Math.floor(m / dayMinutes)}d`);
    m %= dayMinutes;
  }
  if (m >= 60) {
    parts.push(`${Math.floor(m / 60)}h`);
    m %= 60;
  }
  if (m > 0 || parts.length === 0) parts.push(`${m}m`);
  return parts.join(" ");
}

/** "Mon 08:30" in the viewer's timezone; with the date when it is a week or more away. */
export function formatClockStart(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const time = new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(d);
  const farAway = d.getTime() - now.getTime() >= 6 * 24 * 60 * 60_000;
  const day = new Intl.DateTimeFormat(
    "en-GB",
    farAway ? { weekday: "short", day: "numeric", month: "short" } : { weekday: "short" },
  ).format(d);
  return `${day} ${time}`;
}

/** Minutes left on a row at `nowMs`: counts down only while RUNNING and inside today's hours. */
export function liveRemaining(t: SlaPanelTarget, synced: Synced, nowMs: number): number | null {
  if (t.remainingBusinessMinutes === null) return null;
  if (t.state !== "RUNNING") return t.remainingBusinessMinutes;
  let elapsed = Math.max(0, nowMs - synced.receivedAt);
  if (t.clockStopsAt) {
    elapsed = Math.min(elapsed, Date.parse(t.clockStopsAt) - Date.parse(synced.panel.asOf));
  }
  return Math.max(0, t.remainingBusinessMinutes - Math.floor(elapsed / TICK_MS));
}
