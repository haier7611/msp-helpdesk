import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchMicrosoftSettings, type SecretExpiry } from "../api/microsoft";
import styles from "./MicrosoftSecretBanner.module.css";

/**
 * Admin banner: the Microsoft sign-in client secret expires within 30 days or
 * has expired - a common cause of sign-in outages. Shows nothing when the settings cannot be read.
 */
export function MicrosoftSecretBanner() {
  const [expiry, setExpiry] = useState<SecretExpiry | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetchMicrosoftSettings(controller.signal)
      .then((page) => setExpiry(page.settings.secretExpiry))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  if (!expiry || (expiry.state !== "SOON" && expiry.state !== "EXPIRED")) return null;
  const days = expiry.daysLeft ?? 0;
  return (
    <p role="status" className={styles.banner}>
      {expiry.state === "EXPIRED"
        ? "The Microsoft sign-in client secret has expired, so Microsoft sign-in does not work. "
        : `The Microsoft sign-in client secret expires in ${days} ${days === 1 ? "day" : "days"}. `}
      <Link to="/admin/sign-in">Add a new secret</Link>
    </p>
  );
}
