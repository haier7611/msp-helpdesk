import type { ReactNode } from "react";
import styles from "./SidePanel.module.css";

export type SidePanelProps = {
  /** Accessible name of the panel landmark, e.g. "Supporter detail". */
  label: string;
  header?: ReactNode;
  onClose?: () => void;
  children: ReactNode;
  footer?: ReactNode;
};

/**
 * Detail panel beside a list (admin supporters, later organisations). Not modal: it sits in the
 * page flow at desktop width and stacks under the list at phone width.
 */
export function SidePanel({ label, header, onClose, children, footer }: SidePanelProps) {
  return (
    <aside className={styles.panel} aria-label={label}>
      {(header || onClose) && (
        <div className={styles.header}>
          <div className={styles.headerContent}>{header}</div>
          {onClose && (
            <button
              type="button"
              className={styles.close}
              aria-label={`Close ${label.toLowerCase()}`}
              onClick={onClose}
            >
              <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" />
              </svg>
            </button>
          )}
        </div>
      )}
      <div className={styles.body}>{children}</div>
      {footer && <div className={styles.footer}>{footer}</div>}
    </aside>
  );
}
