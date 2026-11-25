import { createContext, useContext } from "react";

/**
 * The support name shown in the top bar, the sign-in card and the browser tab. It comes
 * from GET /api/v1/display-name at app start; this neutral text shows while it loads, if it fails,
 * and outside the provider (isolated tests).
 */
export const DEFAULT_DISPLAY_NAME = "Support desk";

export const DISPLAY_NAME_STORAGE_KEY = "ts-display-name";

export const DisplayNameContext = createContext<string>(DEFAULT_DISPLAY_NAME);

export function useDisplayName(): string {
  return useContext(DisplayNameContext);
}

/**
 * The name from the last visit, so a returning browser shows it straight away instead of the
 * neutral text first (no visible swap). Storage can be blocked: every access is wrapped.
 */
export function storedDisplayName(): string | null {
  try {
    const value = window.localStorage.getItem(DISPLAY_NAME_STORAGE_KEY);
    return value && value.trim() ? value : null;
  } catch {
    return null;
  }
}

export function rememberDisplayName(name: string): void {
  try {
    window.localStorage.setItem(DISPLAY_NAME_STORAGE_KEY, name);
  } catch {
    // Storage blocked: the name still arrives from the server on each load.
  }
}
