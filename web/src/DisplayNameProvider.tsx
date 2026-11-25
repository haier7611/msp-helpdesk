import { useEffect, useState, type ReactNode } from "react";
import { fetchDisplayName } from "./api/displayName";
import {
  DEFAULT_DISPLAY_NAME,
  DisplayNameContext,
  rememberDisplayName,
  storedDisplayName,
} from "./displayNameContext";

/**
 * Loads the configured support name once at app start and keeps the browser tab title in
 * step. Purely cosmetic: a failure leaves the last known (or neutral) name in place, with no pop-up.
 */
export function DisplayNameProvider({ children }: { children: ReactNode }) {
  const [name, setName] = useState<string>(() => storedDisplayName() ?? DEFAULT_DISPLAY_NAME);

  useEffect(() => {
    const controller = new AbortController();
    fetchDisplayName(controller.signal).then(
      (loaded) => {
        setName(loaded);
        rememberDisplayName(loaded);
      },
      () => {
        // Aborted or failed: keep showing the current name; the next page load tries again.
      },
    );
    return () => controller.abort();
  }, []);

  useEffect(() => {
    document.title = name;
  }, [name]);

  return <DisplayNameContext.Provider value={name}>{children}</DisplayNameContext.Provider>;
}
