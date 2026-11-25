import { createContext, useContext } from "react";

export type ToastTone = "info" | "success" | "error";

export type ToastApi = {
  /** A short bottom toast that disappears after a few seconds. */
  show: (message: string, tone?: ToastTone) => void;
  /**
   * A pop-up error at the top of the viewport (role="alert") that stays until the user closes it.
   * Use it for any error or refusal the user must not miss, as well as the inline text.
   */
  showError: (message: string) => void;
  /** A short success confirmation (same as show(message, "success")). */
  showSuccess: (message: string) => void;
};

export const ToastContext = createContext<ToastApi | null>(null);

export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (!api) {
    throw new Error("useToast must be used inside <ToastProvider>");
  }
  return api;
}

/**
 * For shared components that may also render outside the app shell (isolated tests): the toast
 * API, or null without a provider. Always show the error inline too, so nothing is silent.
 */
export function useOptionalToast(): ToastApi | null {
  return useContext(ToastContext);
}
