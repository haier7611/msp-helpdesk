/**
 * Two-step exemption helpers: how its end date is shown and what the admin form
 * sends. The server is the only judge of the rules (engineers only, reason, at most 30 days).
 */

export const MAX_EXEMPTION_DAYS = 30;
export const DEFAULT_EXEMPTION_DAYS = 7;
export const MAX_EXEMPTION_REASON = 200;

/** "4 Dec 2026, 09:30" in the viewer's own timezone. */
export function exemptionDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });
}

/** yyyy-mm-dd of a local date, for a date input. */
export function isoDay(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function addDays(d: Date, days: number): Date {
  const out = new Date(d);
  out.setDate(out.getDate() + days);
  return out;
}

/**
 * The end time sent for a chosen end date: that date at the current time of day, capped a minute
 * short of 30 days from now (the server refuses anything longer). Null for a missing or past day.
 */
export function exemptionEndsAt(day: string, now: Date = new Date()): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!m) return null;
  const end = new Date(
    Number(m[1]),
    Number(m[2]) - 1,
    Number(m[3]),
    now.getHours(),
    now.getMinutes(),
    now.getSeconds(),
  );
  if (end.getTime() <= now.getTime()) return null;
  const latest = now.getTime() + MAX_EXEMPTION_DAYS * 24 * 60 * 60 * 1000 - 60 * 1000;
  return new Date(Math.min(end.getTime(), latest)).toISOString();
}
