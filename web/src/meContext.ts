import { createContext, useContext } from "react";
import type { Me } from "./api/auth";

/**
 * The signed-in user that the route guard (RequireRole) already loaded from /api/v1/auth/me, so the
 * layouts (top bar name and initials, the Admin link) reuse it instead of fetching it again. Null
 * outside a guarded area.
 */
export const MeContext = createContext<Me | null>(null);

export function useSignedInUser(): Me | null {
  return useContext(MeContext);
}
