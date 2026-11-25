import type { FirstResponseTimer } from "../../api/agent";
import { SlaIcon } from "./SlaPanel";
import { formatDuration, formatLeft } from "./slaPanelFormat";
import styles from "./SlaPanel.module.css";

/** At or under this many minutes left the value turns red (with an icon and words). */
export const RED_MINUTES = 10;

/**
 * The agent queue's "First response" cell - the business time left ("1h 25m",
 * formatted as on the ticket's SLA card), "Met", "<time> over" when breached, "Paused", or a dash
 * when there is no target. The server works out every number; this only words it. Red values
 * always come with an icon and visually hidden words, never colour alone.
 */
export function FirstResponseCell({ timer }: { timer?: FirstResponseTimer }) {
  const day = timer?.businessDayMinutes ?? 0;
  switch (timer?.state) {
    case "MET":
      return (
        <span className={`${styles.cell} ${styles.met}`} data-testid="fr-cell" data-state="met">
          <SlaIcon status="met" />
          Met
          <span className={styles.srOnly}> - first response sent in time</span>
        </span>
      );
    case "BREACHED": {
      const over =
        timer.breachedByMinutes === null ? null : formatDuration(timer.breachedByMinutes, day);
      return (
        <span
          className={`${styles.cell} ${styles.breached}`}
          data-testid="fr-cell"
          data-state="breached"
        >
          <SlaIcon status="breached" />
          <span className={styles.srOnly}>Breached: </span>
          {over === null ? "Breached" : `${over} over`}
        </span>
      );
    }
    case "PAUSED":
      return (
        <span
          className={`${styles.cell} ${styles.paused}`}
          data-testid="fr-cell"
          data-state="paused"
        >
          <SlaIcon status="paused" />
          Paused
          {timer.remainingMinutes !== null && (
            <span className={styles.srOnly}>
              {`, ${formatLeft(timer.remainingMinutes, day)} left`}
            </span>
          )}
        </span>
      );
    case "RUNNING":
    case "STOPPED_OUTSIDE_HOURS": {
      if (timer.remainingMinutes === null) break;
      const left = formatLeft(timer.remainingMinutes, day);
      const stopped = timer.state === "STOPPED_OUTSIDE_HOURS";
      const soon = timer.remainingMinutes <= RED_MINUTES;
      return (
        <span
          className={`${styles.cell} ${soon ? styles.danger : ""}`}
          data-testid="fr-cell"
          data-state={soon ? "soon" : "running"}
          title={stopped ? "Clock stopped outside business hours" : undefined}
        >
          {soon && <SlaIcon status="atRisk" />}
          {soon && <span className={styles.srOnly}>Due soon: </span>}
          {left}
          <span className={styles.srOnly}>
            {` left${stopped ? ", clock stopped outside business hours" : ""}`}
          </span>
        </span>
      );
    }
    default:
      break;
  }
  return (
    <span className={`${styles.cell} ${styles.none}`} data-testid="fr-cell" data-state="none">
      <span aria-hidden="true">-</span>
      <span className={styles.srOnly}>No first-response target</span>
    </span>
  );
}
