import { ApiError } from "../../api/client";
import { StepUpCancelled } from "../../components/useStepUp";

/** The server's plain-English message, or a generic one. */
export function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
}

/** True when the person closed the step-up dialog (no message needed). */
export function cancelled(error: unknown): boolean {
  return error instanceof StepUpCancelled;
}

/** A date-time for tables, in the viewer's locale and timezone; "-" when missing. */
export function when(iso: string | null): string {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "-"
    : d.toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });
}

/** Today as yyyy-mm-dd in UTC (the audit log's day boundaries). */
export function utcDate(offsetDays = 0): string {
  const d = new Date(Date.now() + offsetDays * 86_400_000);
  return d.toISOString().slice(0, 10);
}
