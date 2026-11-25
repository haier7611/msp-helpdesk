import { ApiError } from "./api/client";

/** Date and time as "18 Nov 2026, 14:05" in the browser's timezone. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(d);
}

/**
 * A compact date for dense tables: "19 Nov" this year, "19 Nov 2025" otherwise, in the browser's
 * timezone. Empty for a missing or broken value.
 */
export function formatShortDate(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    ...(d.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  }).format(d);
}

/** A compact date and time for dense tables: "19 Nov, 12:51" (year only when not this year). */
export function formatShortDateTime(
  iso: string | null | undefined,
  now: Date = new Date(),
): string {
  const day = formatShortDate(iso, now);
  if (!day || !iso) return "";
  const time = new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(iso));
  return `${day}, ${time}`;
}

/** A calendar day "2026-12-25" as "25 Dec 2026", the app's date style (no timezone shift). */
export function formatDay(day: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day ?? "");
  if (!m) return day ?? "";
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  }).format(d);
}

/**
 * The message for a failed call: an ApiError's message (already mapped to plain words by the API
 * client), or the fallback. Other errors (a parse failure, a bug) never show their raw text.
 */
export function errorText(
  e: unknown,
  fallback = "Something went wrong. Please try again.",
): string {
  return e instanceof ApiError && e.message ? e.message : fallback;
}
