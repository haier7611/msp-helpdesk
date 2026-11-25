import { apiFetch } from "./client";

/**
 * Admin - Customers (/api/v1/admin/customers). The server enforces admin-only, step-up
 * (force sign-out, move, erase) and every state rule; the page only offers what makes sense.
 */

export type CustomerStatus = "ACTIVE" | "INVITED" | "DEACTIVATED" | "ERASED";
export type CustomerSort = "name" | "organisation" | "lastSignIn";

export type CustomerSummary = {
  id: number;
  name: string;
  /** null once erased */
  email: string | null;
  organisationId: number;
  organisationName: string;
  status: CustomerStatus;
  lastSignInAt: string | null;
  createdAt: string;
};

export type CustomerDetail = CustomerSummary & {
  passwordSet: boolean;
  microsoftLinked: boolean;
  ticketsRaised: number;
  invitationExpiresAt: string | null;
};

export type CustomerPage = { items: CustomerSummary[]; total: number };

export const CUSTOMER_PAGE_SIZE = 50;

type Obj = Record<string, unknown>;

function bad(): never {
  throw new Error("API answered with an unexpected shape");
}

function obj(value: unknown): Obj {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Obj)
    : bad();
}

function str(v: unknown): string {
  return typeof v === "string" ? v : bad();
}

function optStr(v: unknown): string | null {
  return typeof v === "string" ? v : null;
}

function num(v: unknown): number {
  return typeof v === "number" ? v : bad();
}

function status(v: unknown): CustomerStatus {
  return v === "ACTIVE" || v === "INVITED" || v === "DEACTIVATED" || v === "ERASED" ? v : bad();
}

export function parseCustomerSummary(value: unknown): CustomerSummary {
  const v = obj(value);
  return {
    id: num(v.id),
    name: str(v.name),
    email: optStr(v.email),
    organisationId: num(v.organisationId),
    organisationName: str(v.organisationName),
    status: status(v.status),
    lastSignInAt: optStr(v.lastSignInAt),
    createdAt: str(v.createdAt),
  };
}

export function parseCustomerDetail(value: unknown): CustomerDetail {
  const v = obj(value);
  return {
    ...parseCustomerSummary(v),
    passwordSet: v.passwordSet === true,
    microsoftLinked: v.microsoftLinked === true,
    ticketsRaised: typeof v.ticketsRaised === "number" ? v.ticketsRaised : 0,
    invitationExpiresAt: optStr(v.invitationExpiresAt),
  };
}

const BASE = "/api/v1/admin/customers";
const ignore = () => undefined;

export function listCustomers(
  filters: {
    q?: string;
    organisationId?: number | null;
    status?: CustomerStatus | "";
    sort?: CustomerSort;
    page?: number;
  },
  signal?: AbortSignal,
): Promise<CustomerPage> {
  const params = new URLSearchParams();
  if (filters.q) params.set("q", filters.q);
  if (filters.organisationId) params.set("organisationId", String(filters.organisationId));
  if (filters.status) params.set("status", filters.status);
  if (filters.sort && filters.sort !== "name") params.set("sort", filters.sort);
  if (filters.page) params.set("page", String(filters.page));
  params.set("limit", String(CUSTOMER_PAGE_SIZE));
  return apiFetch(
    `${BASE}?${params.toString()}`,
    (b) => {
      const v = obj(b);
      return {
        items: Array.isArray(v.items) ? v.items.map(parseCustomerSummary) : bad(),
        total: num(v.total),
      };
    },
    { signal },
  );
}

export function fetchCustomer(id: number, signal?: AbortSignal): Promise<CustomerDetail> {
  return apiFetch(`${BASE}/${id}`, parseCustomerDetail, { signal });
}

export function resendCustomerInvitation(id: number): Promise<{ expiresAt: string }> {
  return apiFetch(`${BASE}/${id}/invitation`, (b) => ({ expiresAt: str(obj(b).expiresAt) }), {
    method: "POST",
    body: {},
  });
}

export function sendCustomerPasswordReset(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/password-reset`, ignore, { method: "POST", body: {} });
}

/** Step-up. */
export function signOutCustomerEverywhere(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/sign-out-everywhere`, ignore, { method: "POST", body: {} });
}

export function deactivateCustomer(id: number): Promise<CustomerDetail> {
  return apiFetch(`${BASE}/${id}/deactivate`, parseCustomerDetail, { method: "POST", body: {} });
}

export function reactivateCustomer(id: number): Promise<CustomerDetail> {
  return apiFetch(`${BASE}/${id}/reactivate`, parseCustomerDetail, { method: "POST", body: {} });
}

/** Step-up. */
export function moveCustomer(
  id: number,
  organisationId: number,
): Promise<{ customer: CustomerDetail; participantTicketsRemoved: number }> {
  return apiFetch(
    `${BASE}/${id}/organisation`,
    (b) => {
      const v = obj(b);
      return {
        customer: parseCustomerDetail(v.customer),
        participantTicketsRemoved: num(v.participantTicketsRemoved),
      };
    },
    { method: "PUT", body: { organisationId } },
  );
}

/** Erase (step-up, irreversible). */
export function eraseCustomer(id: number): Promise<void> {
  return apiFetch(`/api/v1/admin/users/${id}/erase`, ignore, { method: "POST", body: {} });
}
