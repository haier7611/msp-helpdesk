import type { ReactNode } from "react";
import { useDisplayName } from "../../displayNameContext";
import { PlainLayout } from "../../layouts/Layouts";
import { BrandMark } from "../../layouts/TopBar";
import styles from "../pages.module.css";

/** The narrow card used by every sign-in page (sign-in, reset, invitation, setup). */
export function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  const displayName = useDisplayName();
  return (
    <PlainLayout>
      <div className={styles.signIn}>
        <div className={styles.signInBrand}>
          <BrandMark />
          <span className={styles.panelName}>{displayName}</span>
        </div>
        <h1>{title}</h1>
        {children}
      </div>
    </PlainLayout>
  );
}
