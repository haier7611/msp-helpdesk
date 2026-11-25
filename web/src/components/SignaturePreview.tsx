import { useId, useLayoutEffect, useRef, useState } from "react";
import type { RenderedSignature } from "../api/settings";
import { buildSignatureFragment } from "./signatureDom";
import styles from "./SignaturePreview.module.css";

export type SignatureFormat = "html" | "text";

/** One server-rendered HTML signature, built into the DOM through the allow-list (signatureDom). */
function SignatureHtml({ html, label }: { html: string; label: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    ref.current?.replaceChildren(buildSignatureFragment(html));
  }, [html]);
  return <div ref={ref} className={styles.signature} aria-label={label} role="group" />;
}

/**
 * Read-only preview of the email signature exactly as the server renders it for clients: the
 * full signature every agent reply carries (there is no short signature), as HTML or plain text. The frame is always white like an
 * email, in both app themes.
 */
export function SignaturePreview({
  rendered,
  defaultFormat = "html",
  busy = false,
}: {
  rendered: RenderedSignature | null;
  defaultFormat?: SignatureFormat;
  busy?: boolean;
}) {
  const [format, setFormat] = useState<SignatureFormat>(defaultFormat);
  const headingId = useId();

  return (
    <section className={styles.wrapper} aria-labelledby={headingId} aria-busy={busy}>
      <div className={styles.top}>
        <h2 id={headingId} className={styles.heading}>
          Preview - as the client sees it
        </h2>
        <div className={styles.toggle} role="group" aria-label="Preview format">
          <button
            type="button"
            aria-pressed={format === "html"}
            className={styles.toggleButton}
            onClick={() => setFormat("html")}
          >
            HTML
          </button>
          <button
            type="button"
            aria-pressed={format === "text"}
            className={styles.toggleButton}
            onClick={() => setFormat("text")}
          >
            Plain text
          </button>
        </div>
      </div>

      <div className={styles.frame} data-testid="signature-frame">
        {!rendered ? (
          <p className={styles.caption}>Loading the preview...</p>
        ) : format === "html" ? (
          <>
            <p className={styles.caption}>Signature (on every reply)</p>
            <SignatureHtml html={rendered.fullHtml} label="Signature" />
          </>
        ) : (
          <>
            <p className={styles.caption}>Signature (on every reply)</p>
            <pre className={styles.text}>{rendered.fullText}</pre>
          </>
        )}
      </div>
    </section>
  );
}
