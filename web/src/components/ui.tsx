import type { ReactNode } from "react";
import styles from "./ui.module.css";

export function Avatar({
  initials,
  size = "md",
  label,
}: {
  initials: string;
  size?: "md" | "lg" | "xl";
  label?: string;
}) {
  return (
    <span
      className={`${styles.avatar} ${styles[size]}`}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      {initials}
    </span>
  );
}

export function PageHeader({
  title,
  meta,
  actions,
}: {
  title: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className={styles.pageHeader}>
      <h1>{title}</h1>
      {meta && <span className={styles.pageMeta}>{meta}</span>}
      {actions && <div className={styles.pageActions}>{actions}</div>}
    </div>
  );
}

export function Card({
  children,
  title,
  aside,
  className,
}: {
  children: ReactNode;
  title?: ReactNode;
  aside?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`${styles.card} ${className ?? ""}`}>
      {(title || aside) && (
        <div className={styles.cardHeader}>
          {title && <h2 className={styles.cardTitle}>{title}</h2>}
          {aside && <span className={styles.cardAside}>{aside}</span>}
        </div>
      )}
      {children}
    </section>
  );
}

/** Route placeholder for screens that are not built yet (no internal names on screen). */
export function PlaceholderPage({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className={styles.placeholder}>
      <PageHeader title={title} />
      <Card>
        <p className={styles.placeholderText}>
          This screen is not ready yet. It arrives in a later update.
        </p>
        {children}
      </Card>
    </div>
  );
}
