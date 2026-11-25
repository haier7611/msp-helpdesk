import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { MIN_PASSWORD_LENGTH, tokenFromFragment } from "../../api/auth";
import { ApiError } from "../../api/client";

/**
 * The link token from the URL fragment (#token=...), read once. The fragment is then removed from
 * the address bar and history so the token is not left behind in the browser.
 */
export function useFragmentToken(): string | null {
  const location = useLocation();
  const navigate = useNavigate();
  const [token] = useState(() => tokenFromFragment(location.hash));
  useEffect(() => {
    if (location.hash) {
      navigate(location.pathname + location.search, { replace: true });
    }
  }, [location.hash, location.pathname, location.search, navigate]);
  return token;
}

/** The message to show for a failed call: the server's own text, or a plain fallback. */
export function errorText(error: unknown): string {
  if (error instanceof ApiError && error.status < 500 && error.message) {
    return error.message;
  }
  return "Something went wrong on our side. Please try again in a minute.";
}

/** Client-side password checks before the server's (length, confirmation). */
export function passwordProblem(password: string, confirm: string): string | null {
  if (password.length < MIN_PASSWORD_LENGTH) {
    return `Use at least ${MIN_PASSWORD_LENGTH} characters.`;
  }
  if (password !== confirm) {
    return "The two passwords do not match.";
  }
  return null;
}
