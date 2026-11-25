/**
 * Builds the server-rendered signature HTML into real DOM nodes for the preview.
 *
 * Why not innerHTML or an iframe srcdoc: the app's Content-Security-Policy has `style-src 'self'`,
 * which blocks inline style attributes - in a srcdoc iframe too, because it inherits the policy -
 * and the email signature is all inline styles (that is what mail clients need). Styles set through
 * the CSSOM (`element.style.setProperty`) are allowed by CSP, so this walks the parsed HTML and
 * copies an allow-list of elements, attributes and style properties. Everything else is dropped:
 * scripts, event handlers, unknown tags, any link that is not mailto:, any image that is not an
 * inline PNG, and any style value with url() or expression(). The server already escapes every
 * value, so this is a second wall, not the first.
 */

const ELEMENTS = new Set([
  "TABLE",
  "TBODY",
  "TR",
  "TD",
  "DIV",
  "SPAN",
  "STRONG",
  "B",
  "A",
  "IMG",
  "P",
  "BR",
]);

const ATTRIBUTES: Record<string, readonly string[]> = {
  TABLE: ["role", "cellpadding", "cellspacing", "border"],
  A: ["href"],
  IMG: ["src", "alt", "width", "height"],
};

const STYLE_PROPERTIES = new Set([
  "color",
  "background-color",
  "font-family",
  "font-size",
  "font-weight",
  "font-style",
  "line-height",
  "text-decoration",
  "text-align",
  "vertical-align",
  "display",
  "width",
  "height",
  "margin",
  "margin-top",
  "margin-bottom",
  "padding",
  "padding-top",
  "padding-bottom",
  "padding-left",
  "padding-right",
  "border",
  "border-top",
  "border-collapse",
  "word-break",
]);

const SAFE_HREF = /^mailto:[^\s<>"']+$/i;
const SAFE_SRC = /^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/;
const SAFE_VALUE = /^[#A-Za-z0-9 ,.%"'()+-]*$/;

function copyStyle(source: Element, target: HTMLElement) {
  const style = source.getAttribute("style");
  if (!style) return;
  for (const declaration of style.split(";")) {
    const colon = declaration.indexOf(":");
    if (colon < 0) continue;
    const property = declaration.slice(0, colon).trim().toLowerCase();
    const value = declaration.slice(colon + 1).trim();
    if (
      STYLE_PROPERTIES.has(property) &&
      SAFE_VALUE.test(value) &&
      !/url\s*\(|expression\s*\(/i.test(value)
    ) {
      target.style.setProperty(property, value);
    }
  }
}

function copyNode(node: Node, doc: Document): Node | null {
  if (node.nodeType === Node.TEXT_NODE) {
    return doc.createTextNode(node.textContent ?? "");
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return null;
  const source = node as Element;
  const tag = source.tagName.toUpperCase();
  if (!ELEMENTS.has(tag)) return null;
  const el = doc.createElement(tag.toLowerCase());
  for (const name of ATTRIBUTES[tag] ?? []) {
    const value = source.getAttribute(name);
    if (value === null) continue;
    if (name === "href" && !SAFE_HREF.test(value)) continue;
    if (name === "src" && !SAFE_SRC.test(value)) continue;
    el.setAttribute(name, value);
  }
  copyStyle(source, el);
  for (const child of Array.from(source.childNodes)) {
    const copy = copyNode(child, doc);
    if (copy) el.appendChild(copy);
  }
  return el;
}

/** Parses `html` inertly (nothing loads or runs) and returns the allowed part as a fragment. */
export function buildSignatureFragment(html: string, doc: Document = document): DocumentFragment {
  const parsed = new DOMParser().parseFromString(`<!DOCTYPE html><body>${html}`, "text/html");
  const fragment = doc.createDocumentFragment();
  for (const child of Array.from(parsed.body.childNodes)) {
    const copy = copyNode(child, doc);
    if (copy) fragment.appendChild(copy);
  }
  return fragment;
}
