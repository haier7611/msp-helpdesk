import type { QueueFilter, Supporter } from "../../api/agent";

/** Saved views; includes "breaching" (from the SLA fields). */
export const QUEUE_VIEWS: { id: string; label: string; filter: QueueFilter }[] = [
  { id: "mine", label: "My tickets", filter: { assignee: "me" } },
  { id: "unassigned", label: "Unassigned", filter: { assignee: "none", excludeTriage: true } },
  { id: "pending-client", label: "Pending on client", filter: { status: "PENDING_ON_CLIENT" } },
  { id: "pending-vendor", label: "Pending on vendor", filter: { status: "PENDING_ON_VENDOR" } },
  { id: "triage", label: "Email triage", filter: { triage: true } },
  { id: "all", label: "All open tickets", filter: {} },
];

export const SORTS: [string, string][] = [
  ["newest", "Newest first"],
  ["oldest", "Oldest first"],
  ["updated", "Recently updated"],
  ["priority", "Priority"],
  // Overdue unanswered first, then the soonest first-response due time (server-side).
  ["first_response", "First response due"],
];

/** View plus the extra filters in the URL -> the API filter (bookmarkable). */
export function queueFilterFrom(params: URLSearchParams): {
  filter: QueueFilter;
  view: string;
  org: string | null;
} {
  const org = params.get("org");
  const view = org ? "" : (params.get("view") ?? "mine");
  const base: QueueFilter = org
    ? { organisationId: org }
    : { ...(QUEUE_VIEWS.find((v) => v.id === view)?.filter ?? QUEUE_VIEWS[0]?.filter) };
  const filter: QueueFilter = { ...base };
  const status = params.get("status");
  if (status) filter.status = status;
  const priority = params.get("priority");
  if (priority) filter.priority = priority;
  const assignee = params.get("assignee");
  if (assignee) filter.assignee = assignee === "any" ? undefined : assignee;
  const q = params.get("q");
  if (q) filter.q = q;
  const sort = params.get("sort");
  if (sort) filter.sort = sort;
  if (params.get("closed") === "1") filter.includeClosed = true;
  return { filter, view, org };
}

export function supporterLabel(s: Supporter | undefined, fallback: string | null): string {
  if (!s) return fallback ?? "";
  return s.outOfOffice ? `${s.name} (out of office)` : s.name;
}
