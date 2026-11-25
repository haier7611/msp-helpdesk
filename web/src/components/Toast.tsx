import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ToastContext, type ToastTone } from "./toastContext";
import styles from "./Toast.module.css";

type ToastItem = { id: number; message: string; tone: ToastTone };

export const TOAST_TIMEOUT_MS = 6000;
const MAX_TOASTS = 3;

/**
 * Short confirmations ("Saved", "Demo data - nothing was sent"). The live region is always in the
 * DOM so screen readers announce new messages; errors use role="alert". Each toast also has a
 * Dismiss button and disappears after 6 seconds.
 *
 * showError(message) is the pop-up for errors the user must not miss: at the top of the viewport,
 * role="alert", and it stays until the user closes it (48 px Close button).
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [errors, setErrors] = useState<ToastItem[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer) clearTimeout(timer);
    timers.current.delete(id);
  }, []);

  const show = useCallback(
    (message: string, tone: ToastTone = "info") => {
      const id = nextId.current++;
      setToasts((current) => [...current, { id, message, tone }].slice(-MAX_TOASTS));
      timers.current.set(
        id,
        setTimeout(() => dismiss(id), TOAST_TIMEOUT_MS),
      );
    },
    [dismiss],
  );

  const showError = useCallback((message: string) => {
    const id = nextId.current++;
    setErrors((current) =>
      // A message that is already showing is not shown twice.
      current.some((e) => e.message === message)
        ? current
        : [...current, { id, message, tone: "error" as const }].slice(-MAX_TOASTS),
    );
  }, []);

  const closeError = useCallback((id: number) => {
    setErrors((current) => current.filter((e) => e.id !== id));
  }, []);

  const showSuccess = useCallback((message: string) => show(message, "success"), [show]);

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((timer) => clearTimeout(timer));
      pending.clear();
    };
  }, []);

  const api = useMemo(() => ({ show, showError, showSuccess }), [show, showError, showSuccess]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={styles.errorRegion} data-testid="error-popups">
        {errors.map((e) => (
          <div key={e.id} className={`${styles.toast} ${styles.error}`} role="alert">
            <span className={styles.message}>{e.message}</span>
            <button
              type="button"
              className={styles.dismiss}
              aria-label="Close error"
              onClick={() => closeError(e.id)}
            >
              Close
            </button>
          </div>
        ))}
      </div>
      <div className={styles.region} aria-live="polite" data-testid="toast-region">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`${styles.toast} ${styles[toast.tone]}`}
            role={toast.tone === "error" ? "alert" : undefined}
          >
            <span className={styles.message}>{toast.message}</span>
            <button type="button" className={styles.dismiss} onClick={() => dismiss(toast.id)}>
              Dismiss
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
