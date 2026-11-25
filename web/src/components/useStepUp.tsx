import { useRef, useState, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { StepUpDialog } from "./StepUpDialog";

/** Thrown by `run` when the person closes the step-up dialog instead of entering a code. */
export class StepUpCancelled extends Error {
  constructor() {
    super("Step-up cancelled");
    this.name = "StepUpCancelled";
  }
}

type Pending = { resolve: () => void; reject: (e: Error) => void };

/**
 * Step-up for sensitive actions. `run(action)` calls the action; when the
 * server answers 403 `step_up_required` it opens the dialog, and after a correct code it retries
 * the original request once. Render `dialog` somewhere in the page.
 */
export function useStepUp(): {
  run: <T>(action: () => Promise<T>) => Promise<T>;
  dialog: ReactNode;
} {
  const [pending, setPending] = useState<Pending | null>(null);
  const pendingRef = useRef<Pending | null>(null);

  async function run<T>(action: () => Promise<T>): Promise<T> {
    try {
      return await action();
    } catch (e) {
      if (!(e instanceof ApiError && e.code === "step_up_required")) {
        throw e;
      }
      await new Promise<void>((resolve, reject) => {
        const p = { resolve, reject };
        pendingRef.current = p;
        setPending(p);
      });
      return action();
    }
  }

  function finish(ok: boolean) {
    const p = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    if (ok) {
      p?.resolve();
    } else {
      p?.reject(new StepUpCancelled());
    }
  }

  return {
    run,
    dialog: (
      <StepUpDialog
        open={pending !== null}
        onConfirmed={() => finish(true)}
        onCancel={() => finish(false)}
      />
    ),
  };
}
