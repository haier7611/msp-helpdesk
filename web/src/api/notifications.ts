import { apiFetch } from "./client";

/**
 * The notification centre (/api/v1/agent/notifications): the bell's unread count,
 * recent notifications and mark read. The signed-in supporter's own notifications only.
 */

const BASE = "/api/v1/agent/notifications";

export type Notification = {
  id: number;
  text: string;
  reference: string | null;
  ticketSubject: string | null;
  createdAt: string;
  read: boolean;
};

const shapeError = () => new Error("API answered with an unexpected shape");

function parseNotification(value: unknown): Notification {
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (
      typeof v.id === "number" &&
      typeof v.text === "string" &&
      typeof v.createdAt === "string" &&
      typeof v.read === "boolean"
    ) {
      return {
        id: v.id,
        text: v.text,
        reference: typeof v.reference === "string" ? v.reference : null,
        ticketSubject: typeof v.ticketSubject === "string" ? v.ticketSubject : null,
        createdAt: v.createdAt,
        read: v.read,
      };
    }
  }
  throw shapeError();
}

export function parseNotifications(value: unknown): Notification[] {
  if (!Array.isArray(value)) throw shapeError();
  return value.map(parseNotification);
}

function parseUnread(value: unknown): number {
  if (typeof value === "object" && value !== null) {
    const unread = (value as Record<string, unknown>).unread;
    if (typeof unread === "number") return unread;
  }
  throw shapeError();
}

const ignore = () => undefined;

export function fetchUnreadCount(signal?: AbortSignal): Promise<number> {
  return apiFetch(`${BASE}/unread-count`, parseUnread, { signal });
}

export function listNotifications(signal?: AbortSignal): Promise<Notification[]> {
  return apiFetch(`${BASE}?limit=20`, parseNotifications, { signal });
}

export function markNotificationRead(id: number): Promise<void> {
  return apiFetch(`${BASE}/${id}/read`, ignore, { method: "POST", body: {} });
}

export function markAllNotificationsRead(): Promise<void> {
  return apiFetch(`${BASE}/read-all`, ignore, { method: "POST", body: {} });
}
