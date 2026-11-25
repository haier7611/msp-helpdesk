import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { Link, NavLink, useNavigate } from "react-router";
import { signOut } from "../api/auth";
import { ApiError } from "../api/client";
import { initials } from "../components/initials";
import { useToast } from "../components/toastContext";
import { Avatar } from "../components/ui";
import { useDisplayName } from "../displayNameContext";
import { useSignedInUser } from "../meContext";
import { NotificationsMenu } from "./NotificationsMenu";
import styles from "./layouts.module.css";

const navClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? `${styles.topLink} ${styles.topLinkActive}` : styles.topLink;

export function BrandMark() {
  return (
    <span className={styles.brandMark} aria-hidden="true">
      TS
    </span>
  );
}

/**
 * Supporter top bar. The signed-in supporter comes from the
 * route guard; Admin is shown to admins only (the server enforces the same gate).
 */
export function TopBar() {
  const me = useSignedInUser();
  const displayName = useDisplayName();
  const navigate = useNavigate();
  const toast = useToast();
  const [menuOpen, setMenuOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const menuId = useId();
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    function onPointer(event: MouseEvent) {
      if (!menuRef.current?.contains(event.target as Node)) setMenuOpen(false);
    }
    document.addEventListener("mousedown", onPointer);
    return () => document.removeEventListener("mousedown", onPointer);
  }, [menuOpen]);

  /** Searches every ticket (closed ones too) through the queue's own URL filter. */
  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const q = String(new FormData(event.currentTarget).get("q") ?? "").trim();
    if (!q) return;
    navigate(`/agent/queue?${new URLSearchParams({ view: "all", q, closed: "1" }).toString()}`);
  }

  /** Ends the server session (drafts are wiped by signOut), then goes to /sign-in. */
  async function onSignOut() {
    setSigningOut(true);
    try {
      await signOut();
    } catch (error) {
      // 401: the session had already ended, so the person is signed out either way.
      if (!(error instanceof ApiError && error.status === 401)) {
        setSigningOut(false);
        toast.showError("We could not sign you out. Check your connection and try again.");
        return;
      }
    }
    setMenuOpen(false);
    navigate("/sign-in", { replace: true });
  }

  const name = me?.name ?? "";

  return (
    <header className={styles.topBar}>
      <Link to="/agent/queue" className={styles.brand}>
        <BrandMark />
        <span className={styles.brandName}>{displayName}</span>
      </Link>
      <nav aria-label="Main" className={styles.topNav}>
        <NavLink to="/agent/queue" className={navClass}>
          Queue
        </NavLink>
        <NavLink to="/agent/customers" className={navClass}>
          Customers
        </NavLink>
        <NavLink to="/agent/organisations" className={navClass}>
          Organisations
        </NavLink>
        <NavLink to="/agent/reports" className={navClass}>
          Reports
        </NavLink>
        {me?.role === "ADMIN" && (
          <NavLink to="/admin" className={navClass}>
            Admin
          </NavLink>
        )}
      </nav>
      <div className={styles.topTools}>
        <form role="search" className={styles.search} onSubmit={onSearch}>
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="2" />
            <path d="M20 20l-4-4" stroke="currentColor" strokeWidth="2" />
          </svg>
          <input
            type="search"
            name="q"
            placeholder="Search tickets"
            aria-label="Search tickets"
            className={styles.searchInput}
          />
        </form>
        <NotificationsMenu />
        <div
          className={styles.menuWrap}
          ref={menuRef}
          onKeyDown={(event) => {
            if (event.key === "Escape" && menuOpen) {
              setMenuOpen(false);
              menuRef.current?.querySelector("button")?.focus();
            }
          }}
        >
          <button
            type="button"
            className={styles.avatarButton}
            aria-expanded={menuOpen}
            aria-controls={menuId}
            onClick={() => setMenuOpen((open) => !open)}
          >
            <Avatar initials={name ? initials(name) : "?"} />
            <span className="visually-hidden">
              {name ? `Account menu for ${name}` : "Account menu"}
            </span>
          </button>
          {menuOpen && (
            <ul id={menuId} className={styles.menu}>
              <li>
                <Link
                  to="/agent/settings"
                  className={styles.menuItem}
                  onClick={() => setMenuOpen(false)}
                >
                  My settings
                </Link>
              </li>
              <li>
                <button
                  type="button"
                  className={styles.menuItem}
                  onClick={onSignOut}
                  disabled={signingOut}
                >
                  {signingOut ? "Signing out..." : "Sign out"}
                </button>
              </li>
            </ul>
          )}
        </div>
      </div>
    </header>
  );
}
