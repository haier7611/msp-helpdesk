import {
  forwardRef,
  useEffect,
  useId,
  useImperativeHandle,
  useRef,
  useState,
  type ClipboardEvent,
  type KeyboardEvent,
} from "react";
import styles from "./Composer.module.css";

export type SlashOption = { id: number; title: string; shared: boolean };

export type RichEditorHandle = {
  /** Inserts HTML at the caret (or at the end when the editor has no caret). */
  insertHtml: (html: string) => void;
  focus: () => void;
};

export type RichEditorProps = {
  label: string;
  initialHtml: string;
  onChange: (html: string) => void;
  /** Saved replies offered when the user types "/" (filtered by what follows it). */
  slashOptions: SlashOption[];
  onSlashPick: (option: SlashOption) => void;
  onPasteFiles: (files: File[]) => void;
  className?: string;
};

/** "/word" right before the caret, at the start or after a space. */
const SLASH = /(?:^|\s)\/([\w-]*)$/;

type SlashState = { query: string; node: Text | null; start: number; end: number };

function caretText(): { node: Text; offset: number } | null {
  const selection = typeof window.getSelection === "function" ? window.getSelection() : null;
  if (!selection || selection.rangeCount === 0) return null;
  const range = selection.getRangeAt(0);
  if (!range.collapsed || range.startContainer.nodeType !== Node.TEXT_NODE) return null;
  return { node: range.startContainer as Text, offset: range.startOffset };
}

/**
 * Small rich-text editor (contentEditable) for the composer: bold, italic, list, pasted images and
 * "/" saved replies. Whatever it produces is sanitised by the server on save (C5), so the editor
 * itself does not need to be strict.
 */
export const RichEditor = forwardRef<RichEditorHandle, RichEditorProps>(function RichEditor(
  { label, initialHtml, onChange, slashOptions, onSlashPick, onPasteFiles, className },
  ref,
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const savedRange = useRef<Range | null>(null);
  const [slash, setSlash] = useState<SlashState | null>(null);
  const [active, setActive] = useState(0);
  const listId = useId();

  useEffect(() => {
    if (editorRef.current) editorRef.current.innerHTML = initialHtml;
    // Only on mount: the editor is uncontrolled after that.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const matches = slash
    ? slashOptions.filter((o) => o.title.toLowerCase().includes(slash.query.toLowerCase()))
    : [];

  function emit() {
    onChange(editorRef.current?.innerHTML ?? "");
  }

  function rememberCaret() {
    const selection = typeof window.getSelection === "function" ? window.getSelection() : null;
    if (selection && selection.rangeCount > 0) {
      const range = selection.getRangeAt(0);
      if (editorRef.current?.contains(range.startContainer)) {
        savedRange.current = range.cloneRange();
      }
    }
  }

  function detectSlash() {
    const caret = caretText();
    if (caret && editorRef.current?.contains(caret.node)) {
      const before = caret.node.data.slice(0, caret.offset);
      const m = SLASH.exec(before);
      if (m) {
        const query = m[1] ?? "";
        setSlash({
          query,
          node: caret.node,
          start: caret.offset - query.length - 1,
          end: caret.offset,
        });
        setActive(0);
        return;
      }
      setSlash(null);
      return;
    }
    // No caret information (e.g. jsdom): look at the end of the text.
    const text = editorRef.current?.textContent ?? "";
    const m = SLASH.exec(text);
    if (m) {
      setSlash({ query: m[1] ?? "", node: null, start: -1, end: -1 });
      setActive(0);
    } else {
      setSlash(null);
    }
  }

  function removeSlashText(state: SlashState) {
    if (state.node && state.node.isConnected) {
      state.node.deleteData(state.start, state.end - state.start);
      const range = document.createRange();
      range.setStart(state.node, state.start);
      range.collapse(true);
      savedRange.current = range;
      return;
    }
    // Fallback: strip the trailing "/query" from the last text node.
    const root = editorRef.current;
    if (!root) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let last: Text | null = null;
    while (walker.nextNode()) last = walker.currentNode as Text;
    if (last) {
      const token = `/${state.query}`;
      const at = last.data.lastIndexOf(token);
      if (at >= 0) last.deleteData(at, token.length);
    }
    savedRange.current = null;
  }

  function insertHtml(html: string) {
    const root = editorRef.current;
    if (!root) return;
    const template = document.createElement("template");
    template.innerHTML = html;
    const fragment = template.content;
    const range = savedRange.current;
    if (range && root.contains(range.startContainer)) {
      range.deleteContents();
      const lastNode = fragment.lastChild;
      range.insertNode(fragment);
      if (lastNode) {
        const after = document.createRange();
        after.setStartAfter(lastNode);
        after.collapse(true);
        savedRange.current = after;
      }
    } else {
      root.appendChild(fragment);
    }
    emit();
  }

  useImperativeHandle(ref, () => ({
    insertHtml,
    focus: () => editorRef.current?.focus(),
  }));

  function pick(option: SlashOption) {
    if (slash) removeSlashText(slash);
    setSlash(null);
    emit();
    onSlashPick(option);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!slash || matches.length === 0) {
      if (slash && event.key === "Escape") {
        event.preventDefault();
        setSlash(null);
      }
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((a) => (a + 1) % matches.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((a) => (a - 1 + matches.length) % matches.length);
    } else if (event.key === "Enter" || event.key === "Tab") {
      event.preventDefault();
      const option = matches[active];
      if (option) pick(option);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setSlash(null);
    }
  }

  function onPaste(event: ClipboardEvent<HTMLDivElement>) {
    const files = Array.from(event.clipboardData?.files ?? []).filter((f) =>
      f.type.startsWith("image/"),
    );
    if (files.length > 0) {
      event.preventDefault();
      rememberCaret();
      onPasteFiles(files);
    }
  }

  const activeOption = matches[active];
  return (
    <div className={styles.editorWrap}>
      <div
        ref={editorRef}
        role="textbox"
        aria-multiline="true"
        aria-label={label}
        aria-autocomplete="list"
        aria-controls={slash ? listId : undefined}
        aria-expanded={slash ? matches.length > 0 : undefined}
        aria-activedescendant={slash && activeOption ? `${listId}-${activeOption.id}` : undefined}
        contentEditable
        suppressContentEditableWarning
        tabIndex={0}
        className={`${styles.editor} ${className ?? ""}`}
        onInput={() => {
          emit();
          detectSlash();
          rememberCaret();
        }}
        onKeyUp={rememberCaret}
        onMouseUp={rememberCaret}
        onBlur={rememberCaret}
        onKeyDown={onKeyDown}
        onPaste={onPaste}
      />
      {slash && (
        <div className={styles.slashBox}>
          {matches.length === 0 ? (
            <p className={styles.slashEmpty} role="status">
              No saved reply matches "{slash.query}".
            </p>
          ) : (
            <ul id={listId} role="listbox" aria-label="Saved replies" className={styles.slashList}>
              {matches.map((o, i) => (
                <li
                  key={o.id}
                  id={`${listId}-${o.id}`}
                  role="option"
                  aria-selected={i === active}
                  className={i === active ? styles.slashActive : undefined}
                  onMouseDown={(event) => {
                    event.preventDefault();
                    pick(o);
                  }}
                >
                  {o.title}
                  <span className={styles.slashKind}>{o.shared ? "Shared" : "Mine"}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
});
