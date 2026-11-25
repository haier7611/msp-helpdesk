import { useEffect, useState, type ReactNode } from "react";
import { Navigate } from "react-router";
import { fetchMe, homeFor, type Me, type Role } from "./api/auth";
import { ApiError } from "./api/client";
import { mfaPathFor } from "./api/security";
import { MeContext } from "./meContext";

/**
 * Route guards. They are for a tidy experience only: the server enforces every role gate
 * (/api/v1/portal customers, /agent agents and admins, /admin admins). A 401 from /auth/me goes to
 * /sign-in through the API client's unauthorised handler.
 *
 * A half-signed-in session (password done, two-step set-up or code still owed: `authLevel`
 * MFA_ENROLMENT_REQUIRED or MFA_REQUIRED) counts as not signed in to the app: every guarded route
 * sends it to its step page, so no app shell, top bar or name is shown. The server refuses such a
 * session everything except the step, `me` and sign-out.
 */

type MeState = { kind: "loading" } | { kind: "ready"; me: Me } | { kind: "error" };

function useMe(): MeState {
  const [state, setState] = useState<MeState>({ kind: "loading" });
  useEffect(() => {
    const controller = new AbortController();
    fetchMe(controller.signal)
      .then((me) => setState({ kind: "ready", me }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        // 401 is handled by the client (redirect to /sign-in); anything else is shown.
        if (!(error instanceof ApiError && error.status === 401)) setState({ kind: "error" });
      });
    return () => controller.abort();
  }, []);
  return state;
}

function Checking({ failed }: { failed: boolean }) {
  return failed ? (
    <p role="alert">We could not check your sign-in. Reload the page to try again.</p>
  ) : (
    <p role="status">Checking your sign-in...</p>
  );
}

/** Shows `children` only to the given roles; anyone else goes to their own home page. */
export function RequireRole({ allow, children }: { allow: readonly Role[]; children: ReactNode }) {
  const state = useMe();
  if (state.kind !== "ready") return <Checking failed={state.kind === "error"} />;
  const step = mfaPathFor(state.me.authLevel);
  if (step) return <Navigate to={step} replace />;
  if (!allow.includes(state.me.role)) return <Navigate to={homeFor(state.me.role)} replace />;
  return <MeContext.Provider value={state.me}>{children}</MeContext.Provider>;
}

/** "/" sends each role to its landing page: customers to New ticket, supporters to the queue. */
export function RoleLanding() {
  const state = useMe();
  if (state.kind !== "ready") return <Checking failed={state.kind === "error"} />;
  return <Navigate to={mfaPathFor(state.me.authLevel) ?? homeFor(state.me.role)} replace />;
}
