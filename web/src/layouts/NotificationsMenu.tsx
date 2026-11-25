import { useCallback, useEffect, useId, useRef, useState } from "react";
import { Link, useLocation } from "react-router";
import {
  fetchUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type Notification,
} from "../api/notifications";
import { useReportError } from "../components/useReportError";
import { formatDateTime } from "../format";
import styles from "./layouts.module.css";

type ListState = { kind: "loading" } | { kind: "ready"; items: Notification[] } | { kind: "error" };

/**
 * The bell (notification centre): unread count, a panel of recent notifications
 * that opens on click or Enter/Space, closes on Escape (focus back on the bell) or a click outside,
 * and marks a notification read when it is opened, or all of them at once.
 */
export function NotificationsMenu() {
  const [unread, setUnread] = useState<number | null>(null);
  const [open, setOpen] = useState(false);
  const [list, setList] = useState<ListState>({ kind: "loading" });
  // A failed background count refresh: no toast (it runs on every page change), just a hint.
  const [stale, setStale] = useState(false);
  const report = useReportError();
  const panelId = useId();
  const headingId = useId();
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const { pathname } = useLocation();

  const refreshCount = useCallback((signal?: AbortSignal) => {
    fetchUnreadCount(signal).then(
      (n) => {
        if (signal?.aborted) return;
        setUnread(n);
        setStale(false);
      },
      () => {
        if (!signal?.aborted) setStale(true);
      },
    );
  }, []);

  // The count is checked when the bar mounts and on every page change.
  useEffect(() => {
    const controller = new AbortController();
    refreshCount(controller.signal);
    return () => controller.abort();
  }, [pathname, refreshCount]);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    listNotifications(controller.signal).then(
      (items) => setList({ kind: "ready", items }),
      () => {
        if (!controller.signal.aborted) setList({ kind: "error" });
      },
    );
    function onPointer(event: MouseEvent) {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onPointer);
    return () => {
      controller.abort();
      document.removeEventListener("mousedown", onPointer);
    };
  }, [open]);

  function close(returnFocus: boolean) {
    setOpen(false);
    if (returnFocus) buttonRef.current?.focus();
  }

  function onOpenItem(item: Notification) {
    close(false);
    if (!item.read) {
      markNotificationRead(item.id).then(
        () => refreshCount(),
        (e: unknown) => report(e, "The notification was not marked as read."),
      );
    }
  }

  function onMarkAll() {
    markAllNotificationsRead().then(
      () => {
        setUnread(0);
        setList((s) =>
          s.kind === "ready"
            ? { kind: "ready", items: s.items.map((i) => ({ ...i, read: true })) }
            : s,
        );
      },
      (e: unknown) => report(e, "The notifications were not marked as read."),
    );
  }

  const label =
    (unread === null ? "Notifications" : `Notifications, ${unread} unread`) +
    (stale ? " (couldn't refresh)" : "");

  return (
    <div
      className={styles.menuWrap}
      ref={wrapRef}
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) close(true);
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        className={styles.iconButton}
        aria-label={label}
        title={stale ? "Couldn't refresh notifications" : undefined}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          if (!open) setList({ kind: "loading" });
          setOpen(!open);
        }}
      >
        <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none">
          <path d="M6 8a6 6 0 1112 0c0 7 3 8 3 8H3s3-1 3-8" stroke="currentColor" strokeWidth="2" />
          <path d="M10 20a2 2 0 004 0" stroke="currentColor" strokeWidth="2" />
        </svg>
        {unread !== null && unread > 0 && (
          <span className={styles.count} aria-hidden="true">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>
      {open && (
        <section id={panelId} className={styles.notifPanel} aria-labelledby={headingId}>
          <div className={styles.notifHeader}>
            <h2 id={headingId} className={styles.notifTitle}>
              Notifications
            </h2>
            {list.kind === "ready" && list.items.some((i) => !i.read) && (
              <button type="button" className={styles.notifAction} onClick={onMarkAll}>
                Mark all as read
              </button>
            )}
          </div>
          {stale && <p role="status">Couldn't refresh the unread count.</p>}
          {list.kind === "loading" && <p role="status">Loading notifications...</p>}
          {list.kind === "error" && (
            <p role="alert">Notifications could not be loaded. Try again in a moment.</p>
          )}
          {list.kind === "ready" && list.items.length === 0 && <p>No notifications yet.</p>}
          {list.kind === "ready" && list.items.length > 0 && (
            <ul className={styles.notifList}>
              {list.items.map((item) => {
                const body = (
                  <>
                    <span className={styles.notifText}>
                      {!item.read && <span className="visually-hidden">Unread: </span>}
                      {item.text}
                    </span>
                    {item.reference && (
                      <span className={styles.notifTicket}>
                        {item.ticketSubject
                          ? `${item.reference} - ${item.ticketSubject}`
                          : item.reference}
                      </span>
                    )}
                    <span className={styles.notifTime}>{formatDateTime(item.createdAt)}</span>
                  </>
                );
                const cls = item.read
                  ? styles.notifItem
                  : `${styles.notifItem} ${styles.notifUnread}`;
                return (
                  <li key={item.id}>
                    {item.reference ? (
                      <Link
                        to={`/agent/tickets/${encodeURIComponent(item.reference)}`}
                        className={cls}
                        onClick={() => onOpenItem(item)}
                      >
                        {body}
                      </Link>
                    ) : (
                      <div className={cls}>{body}</div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
