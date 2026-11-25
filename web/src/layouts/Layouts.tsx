import { useEffect, useState, type ReactNode } from "react";
import { Link, NavLink, Outlet, useNavigate } from "react-router";
import { fetchPreferences } from "../api/settings";
import { fetchVersion, type VersionInfo } from "../api/version";
import { MfaExemptionBanner } from "../components/MfaExemptionBanner";
import { MicrosoftSecretBanner } from "../components/MicrosoftSecretBanner";
import { signOut } from "../api/auth";
import { ApiError } from "../api/client";
import { useReportError } from "../components/useReportError";
import { fetchAccount } from "../api/portal";
import { ADMIN_SECTIONS, SETTINGS_SECTIONS, type SectionLink } from "../navigation";
import { setTheme } from "../theme";
import { TopBar } from "./TopBar";
import styles from "./layouts.module.css";

function SkipLink() {
  return (
    <a className="skip-link" href="#main">
      Skip to content
    </a>
  );
}

/** Shows the running API version, as the walking skeleton did (and the deploy check relies on). */
export function VersionFooter() {
  const [state, setState] = useState<
    { kind: "loading" } | { kind: "ready"; info: VersionInfo } | { kind: "error" }
  >({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    fetchVersion(controller.signal)
      .then((info) => setState({ kind: "ready", info }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ kind: "error" });
      });
    return () => controller.abort();
  }, []);

  return (
    <footer className={styles.footer}>
      {state.kind === "loading" && <span>Checking the API...</span>}
      {state.kind === "ready" && (
        <span>
          {state.info.name} - API <span data-testid="api-version">{state.info.version}</span> -
          build <code>{state.info.gitSha}</code>
        </span>
      )}
      {state.kind === "error" && <span>Cannot reach the API right now.</span>}
    </footer>
  );
}

/** Applies the signed-in supporter's saved theme (My settings - Preferences). */
function useSupporterTheme() {
  useEffect(() => {
    const controller = new AbortController();
    fetchPreferences(controller.signal)
      .then((p) => setTheme(p.theme))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);
}

/** Supporter area: top bar, then the page. The queue adds its own views sidebar. */
export function AgentLayout() {
  useSupporterTheme();
  return (
    <div className={styles.app}>
      <SkipLink />
      <TopBar />
      <main id="main" className={styles.main} tabIndex={-1}>
        <MfaExemptionBanner />
        <Outlet />
      </main>
      <VersionFooter />
    </div>
  );
}

/** Vertical section nav at desktop width; a horizontal scrolling row at phone width. */
export function SectionNav({ label, links }: { label: string; links: SectionLink[] }) {
  return (
    <nav aria-label={label} className={styles.sectionNav}>
      <ul className={styles.sectionList}>
        {links.map((link) => (
          <li key={link.to}>
            <NavLink
              to={link.to}
              className={({ isActive }) =>
                isActive ? `${styles.sectionLink} ${styles.sectionLinkActive}` : styles.sectionLink
              }
            >
              {link.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

export function AdminLayout() {
  useSupporterTheme();
  return (
    <div className={styles.app}>
      <SkipLink />
      <TopBar />
      <div className={styles.withNav}>
        <SectionNav label="Admin sections" links={ADMIN_SECTIONS} />
        <main id="main" className={styles.navMain} tabIndex={-1}>
          <MicrosoftSecretBanner />
          <Outlet />
        </main>
      </div>
      <VersionFooter />
    </div>
  );
}

/** My settings, nested inside the agent layout. */
export function SettingsLayout() {
  return (
    <div className={styles.nested}>
      <SectionNav label="Settings sections" links={SETTINGS_SECTIONS} />
      <div className={styles.nestedMain}>
        <Outlet />
      </div>
    </div>
  );
}

/** Customer area: company header, the New ticket form and My account only. */
export function PortalLayout() {
  const navigate = useNavigate();
  const report = useReportError();
  const [who, setWho] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetchAccount(controller.signal)
      .then((a) => setWho(`${a.name} - ${a.organisationName}`))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);
  async function onSignOut() {
    try {
      await signOut();
    } catch (e) {
      // 401: the session had already ended, so the person is signed out either way.
      if (!(e instanceof ApiError && e.status === 401)) {
        report(e, "We could not sign you out.");
        return;
      }
    }
    navigate("/sign-in", { replace: true });
  }
  return (
    <div className={styles.app}>
      <SkipLink />
      <header className={styles.portalHeader}>
        <Link to="/portal/new-ticket" className={styles.portalBrand}>
          <span className={styles.portalLogo}>Example MSP</span>
          <span className={styles.portalSupport}>Support</span>
        </Link>
        <nav aria-label="Portal" className={styles.portalNav}>
          {who && <span className={styles.portalUser}>{who}</span>}
          <NavLink to="/portal/new-ticket" className={styles.portalLink}>
            New ticket
          </NavLink>
          <NavLink to="/portal/account" className={styles.portalLink}>
            My account
          </NavLink>
          <button type="button" className={styles.portalLink} onClick={onSignOut}>
            Sign out
          </button>
        </nav>
      </header>
      <main id="main" className={styles.portalMain} tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}

/** Bare centred layout for sign-in and not-found. */
export function PlainLayout({ children }: { children: ReactNode }) {
  return (
    <div className={styles.app}>
      <SkipLink />
      <main id="main" className={styles.plainMain} tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}
