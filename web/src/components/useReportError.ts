import { useCallback } from "react";
import { ApiError, MESSAGES, isAbort } from "../api/client";
import { useOptionalToast } from "./toastContext";
import { StepUpCancelled } from "./useStepUp";

/**
 * The text to show for a failed action. An ApiError's message is already safe (the client maps it
 * by status and only uses the server's own text from the known error shape); anything else (a
 * parse error, a bug) gets the generic sentence, never its raw message or stack.
 */
export function errorMessage(error: unknown, fallback: string = MESSAGES.generic): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

/**
 * Errors that need no pop-up: the person closed the step-up dialog, the request was aborted, or the
 * session ended (401 already sends them to sign-in).
 */
export function isQuietError(error: unknown): boolean {
  return (
    error instanceof StepUpCancelled ||
    isAbort(error) ||
    (error instanceof ApiError && error.status === 401 && error.code !== "invalid_credentials")
  );
}

/**
 * Pop-up errors for user actions (no silent failures): `report(error, context?)` shows
 * showError("<context> <message>"), e.g. "The status was not changed. You don't have permission
 * to do that.". Keep any inline field error as well; this is the pop-up on top of it.
 */
export function useReportError(): (error: unknown, context?: string) => void {
  const toast = useOptionalToast();
  return useCallback(
    (error: unknown, context?: string) => {
      if (isQuietError(error)) return;
      const message = errorMessage(error);
      toast?.showError(context ? `${context} ${message}` : message);
    },
    [toast],
  );
}
