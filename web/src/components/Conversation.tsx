import type { ReactNode } from "react";
import type { Attachment } from "../api/agent";
import { formatDateTime } from "../format";
import styles from "./Conversation.module.css";

const ATTACHMENTS = "/api/v1/agent/attachments";

/**
 * Files on a comment: images as thumbnails linking to the full file, others as file tiles, in a
 * wrapping grid inside the comment card (never positioned over the cards below it). Long names
 * wrap. The server checks access on every request (thumbnail and download included).
 */
export function AttachmentList({ files }: { files: Attachment[] }) {
  const shown = files.filter((f) => !f.inline);
  if (shown.length === 0) return null;
  return (
    <ul className={styles.files} aria-label="Attachments">
      {shown.map((f) => (
        <li key={f.id} className={styles.fileItem}>
          <a
            href={`${ATTACHMENTS}/${f.id}`}
            className={`${styles.fileLink} ${f.image ? styles.imageTile : styles.fileTile}`}
            download={f.filename}
          >
            {f.image ? (
              <img
                src={`${ATTACHMENTS}/${f.id}/thumbnail`}
                alt={`Preview of ${f.filename}`}
                className={styles.thumb}
                loading="lazy"
              />
            ) : (
              <svg
                className={styles.fileIcon}
                aria-hidden="true"
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
              >
                <path
                  d="M6 2h8l5 5v15H6z M14 2v5h5"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinejoin="round"
                />
              </svg>
            )}
            <span className={styles.fileName}>{f.filename}</span>
          </a>
        </li>
      ))}
    </ul>
  );
}

/** A hidden system entry (e.g. a stored auto-reply or an assignment note), supporters only. */
export function SystemEntryCard({ meta, children }: { meta: string; children: ReactNode }) {
  return (
    <article className={styles.system} aria-label="System entry">
      <span className={styles.systemLabel}>SYSTEM</span> <span className={styles.meta}>{meta}</span>
      <div>{children}</div>
    </article>
  );
}

/** Change types shown with the person icon; every other change gets the clock icon. */
const PERSON_TYPES = new Set([
  "ASSIGNEE",
  "REPORTER",
  "PARTICIPANT_ADDED",
  "PARTICIPANT_REMOVED",
  "PARTICIPANTS_RECHECKED",
]);

/**
 * A ticket change (assignment, status, priority, organisation, ...) as a
 * slim one-line row in the timeline, not a comment card - small decorative icon, muted text, and
 * the date and time in the viewer's timezone in a time element. Agent-only: the server sends these
 * only on agent ticket endpoints.
 */
export function TimelineLine({ text, at, type }: { text: string; at: string; type?: string }) {
  const person = type === undefined || PERSON_TYPES.has(type);
  return (
    <p className={styles.assignment}>
      <svg
        className={styles.assignmentIcon}
        aria-hidden="true"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
      >
        {person ? (
          <>
            <circle cx="12" cy="8" r="4" stroke="currentColor" strokeWidth="2" />
            <path d="M4 21c0-4 3.6-7 8-7s8 3 8 7" stroke="currentColor" strokeWidth="2" />
          </>
        ) : (
          <>
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" />
            <path d="M12 7v5l3 2" stroke="currentColor" strokeWidth="2" />
          </>
        )}
      </svg>
      <span>{text}</span>
      <span aria-hidden="true">-</span>
      <time dateTime={at}>{formatDateTime(at)}</time>
    </p>
  );
}

type CardProps = {
  author: string;
  meta: string;
  children: ReactNode;
  attachment?: ReactNode;
  footnote?: string;
};

/** A client message or a public reply in the ticket conversation. */
export function MessageCard({
  author,
  meta,
  children,
  attachment,
  footnote,
  kind,
}: CardProps & { kind: "client" | "reply" }) {
  return (
    <article className={`${styles.card} ${kind === "reply" ? styles.reply : ""}`}>
      <header className={styles.header}>
        <strong className={styles.author}>{author}</strong>
        <span className={styles.meta}>{meta}</span>
      </header>
      <div className={styles.body}>{children}</div>
      {attachment && <div className={styles.attachments}>{attachment}</div>}
      {footnote && <p className={styles.footnote}>{footnote}</p>}
    </article>
  );
}

/**
 * Internal note: yellow card with an "INTERNAL" label in text, so it is not told apart by colour
 * alone. Never sent to the client (flow 5).
 */
export function InternalNoteCard({ author, meta, children, attachment }: CardProps) {
  return (
    <article className={`${styles.card} ${styles.note}`} aria-label={`Internal note by ${author}`}>
      <header className={styles.header}>
        <span className={styles.internalLabel}>INTERNAL</span>
        <strong className={styles.author}>{author}</strong>
        <span className={styles.noteMeta}>{meta}</span>
      </header>
      <div className={styles.body}>{children}</div>
      {attachment && <div className={styles.attachments}>{attachment}</div>}
    </article>
  );
}
