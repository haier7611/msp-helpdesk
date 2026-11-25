import DOMPurify from "dompurify";
import styles from "./SafeHtml.module.css";

/**
 * Display-side sanitising (docs/35-security.md). The server already sanitised every
 * body on save with the same allow list (RichTextSanitiser); DOMPurify runs it again in the
 * browser so a stored body can never run script here even if the server-side check regressed.
 */
export const ALLOWED_TAGS = [
  "p",
  "br",
  "strong",
  "em",
  "u",
  "s",
  "ul",
  "ol",
  "li",
  "blockquote",
  "pre",
  "code",
  "h2",
  "h3",
  "a",
  "img",
  "table",
  "thead",
  "tbody",
  "tr",
  "th",
  "td",
];

const ALLOWED_ATTR = ["href", "src", "alt", "rel"];
const ATTACHMENT_SRC =
  /^attachment:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;
const UPLOAD_SRC =
  /^\/api\/v1\/agent\/attachments\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;
const SAFE_LINK = /^(https?:\/\/|mailto:)\S+$/i;

/** attachment:<id> -> the authenticated download URL; anything else -> null (image removed). */
export function attachmentUrl(src: string): string | null {
  const m = ATTACHMENT_SRC.exec(src.trim()) ?? UPLOAD_SRC.exec(src.trim());
  return m ? `/api/v1/agent/attachments/${m[1]?.toLowerCase()}` : null;
}

let hooked = false;

function installHooks() {
  if (hooked) return;
  hooked = true;
  DOMPurify.addHook("afterSanitizeAttributes", (node) => {
    if (node.tagName === "IMG") {
      // Only our own attachments: remote images (tracking pixels) and data: URLs are dropped.
      const url = attachmentUrl(node.getAttribute("src") ?? "");
      if (!url) {
        node.remove();
        return;
      }
      node.setAttribute("src", url);
    }
    if (node.tagName === "A") {
      const href = node.getAttribute("href") ?? "";
      if (!SAFE_LINK.test(href.trim())) {
        node.removeAttribute("href");
      } else {
        node.setAttribute("rel", "noopener noreferrer nofollow");
        node.setAttribute("target", "_blank");
      }
    }
  });
}

export function sanitiseHtml(html: string): string {
  installHooks();
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR: [...ALLOWED_ATTR, "target"],
    ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|attachment:|\/api\/v1\/agent\/attachments\/)/i,
    ALLOW_DATA_ATTR: false,
  });
}

/** Shows a stored rich-text body after DOMPurify. */
export function SafeHtml({ html, className }: { html: string; className?: string }) {
  return (
    <div
      className={`${styles.body} ${className ?? ""}`}
      // Sanitised just above with the shared allow list (C5).
      dangerouslySetInnerHTML={{ __html: sanitiseHtml(html) }}
    />
  );
}
