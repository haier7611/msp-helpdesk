import type { Theme } from "./api/settings";

/**
 * Light / dark / system theme (My settings - Preferences). The choice is stored on the
 * server (it follows the person across browsers and sign-ins) and mirrored to localStorage so the
 * right theme applies before the first API answer. Storage can be blocked (private windows,
 * policies): every access is wrapped, and the app still works - it then follows the system until
 * the server's preference arrives. "System" removes the attribute, so tokens.css follows
 * prefers-color-scheme.
 */

export const THEME_STORAGE_KEY = "ts-theme";

export function applyTheme(theme: Theme, root: HTMLElement = document.documentElement): void {
  if (theme === "LIGHT") root.setAttribute("data-theme", "light");
  else if (theme === "DARK") root.setAttribute("data-theme", "dark");
  else root.removeAttribute("data-theme");
}

export function storedTheme(): Theme | null {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY);
    return value === "LIGHT" || value === "DARK" || value === "SYSTEM" ? value : null;
  } catch {
    return null;
  }
}

export function rememberTheme(theme: Theme): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Storage blocked: the server copy still applies after sign-in.
  }
}

/** Applies and remembers a theme the person chose or the server sent. */
export function setTheme(theme: Theme): void {
  applyTheme(theme);
  rememberTheme(theme);
}

/** At start-up, before React renders: whatever this browser last saw. */
export function applyStoredTheme(): void {
  const theme = storedTheme();
  if (theme) applyTheme(theme);
}
