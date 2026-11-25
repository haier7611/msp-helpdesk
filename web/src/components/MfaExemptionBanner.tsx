import { useEffect, useState } from "react";
import { fetchMe } from "../api/auth";
import { exemptionDate } from "../exemption";
import styles from "./MicrosoftSecretBanner.module.css";

/**
 * Engineer banner: an admin paused two-step sign-in for this account until a date.
 * Shows nothing for everyone else, or when /me cannot be read.
 */
export function MfaExemptionBanner() {
  const [until, setUntil] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetchMe(controller.signal)
      .then((me) => setUntil(me.mfaExemptUntil ?? null))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  if (!until) return null;
  return (
    <p role="status" className={styles.banner}>
      Two-step sign-in is paused for your account until {exemptionDate(until)}.
    </p>
  );
}
