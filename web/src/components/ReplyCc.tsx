import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import styles from "./ReplyCc.module.css";
import {
  MAX_CC,
  ccLabel as label,
  ccRefusal,
  ccSummary,
  isPlainAddress,
  type CcPerson,
  type CcValue,
  type ReplyCcConfig,
} from "./ccRecipients";
import { useOptionalToast } from "./toastContext";

/** What a typed address led to: added, the outside-address question, or an error. */
type TypedOutcome = "added" | "asked" | "error";

type Props = {
  to: string;
  config: ReplyCcConfig;
  value: CcValue;
  onChange: (value: CcValue) => void;
  disabled?: boolean;
};

/**
 * The "Reply to client" header line: "To: reporter   CC: names +N   Edit". "Edit" opens
 * an inline editor: removable chips, an add box with contact suggestions (combobox), "Keep on
 * future replies" and Done. The server checks every rule again; this only helps the agent.
 */
export function ReplyCc({ to, config, value, onChange, disabled }: Props) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [suggestions, setSuggestions] = useState<CcPerson[]>([]);
  const [active, setActive] = useState(-1);
  const [ask, setAsk] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ text: string; error: boolean } | null>(null);
  const [checking, setChecking] = useState(false);
  const toast = useOptionalToast();
  const editRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const askRef = useRef<HTMLParagraphElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const busy = useRef(false);
  const baseId = useId();
  const editorId = `${baseId}-cc-editor`;
  const listId = `${baseId}-cc-suggestions`;
  const keepId = `${baseId}-cc-keep`;
  const inputId = `${baseId}-cc-add`;

  const selectedEmails = new Set(value.selected.map((p) => p.email.toLowerCase()));
  const visible = suggestions.filter((s) => !selectedEmails.has(s.email.toLowerCase()));
  const listOpen = open && visible.length > 0 && text.trim().length >= 2;

  // The latest search function, so a parent re-render does not restart a pending search.
  const search = useRef(config.searchContacts);
  useEffect(() => {
    search.current = config.searchContacts;
  });
  const query = open ? text.trim() : "";
  useEffect(() => {
    if (query.length < 2) return;
    const q = query;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      search.current(q, controller.signal).then(
        (found) => {
          setSuggestions(found);
          setActive(-1);
        },
        () => setSuggestions([]),
      );
    }, 200);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  /** An error or refusal: inline text and a pop-up, never silent. */
  function fail(message: string): TypedOutcome {
    setNotice({ text: message, error: true });
    toast?.showError(message);
    return "error";
  }

  function close() {
    setOpen(false);
    setText("");
    setAsk(null);
    setNotice(null);
    setSuggestions([]);
    // Focus goes back to Edit after the editor closes.
    window.setTimeout(() => editRef.current?.focus(), 0);
  }

  function add(person: CcPerson, external: boolean): TypedOutcome {
    const email = person.email.trim().toLowerCase();
    if (selectedEmails.has(email)) return fail(`${email} is already in CC.`);
    if (value.selected.length >= MAX_CC) {
      return fail(`At most ${MAX_CC} people can be in CC. Remove someone first.`);
    }
    onChange({
      ...value,
      selected: [...value.selected, { ...person, email }],
      confirmed: external ? [...value.confirmed, email] : value.confirmed,
    });
    setText("");
    setAsk(null);
    setSuggestions([]);
    setActive(-1);
    setNotice({ text: `${label(person)} added to CC.`, error: false });
    inputRef.current?.focus();
    return "added";
  }

  function remove(person: CcPerson) {
    const email = person.email.toLowerCase();
    onChange({
      ...value,
      selected: value.selected.filter((p) => p.email.toLowerCase() !== email),
      confirmed: value.confirmed.filter((e) => e !== email),
    });
    setNotice({ text: `${label(person)} removed from CC.`, error: false });
    inputRef.current?.focus();
  }

  /**
   * A typed address (Enter, Add or Done): a known contact is added at once; otherwise the server's
   * early check refuses staff and other accounts, adds same-organisation addresses and asks about
   * outside ones. Without the check (or if it fails) the verified domains decide.
   */
  async function addTyped(raw: string): Promise<TypedOutcome> {
    const email = raw.trim().toLowerCase();
    if (!isPlainAddress(email)) {
      return fail(`${raw.trim() || "That"} is not a valid email address.`);
    }
    if (selectedEmails.has(email)) return fail(`${email} is already in CC.`);
    if (value.selected.length >= MAX_CC) {
      return fail(`At most ${MAX_CC} people can be in CC. Remove someone first.`);
    }
    const contact = suggestions.find((s) => s.email.toLowerCase() === email);
    if (contact) return add(contact, false);

    const domain = email.slice(email.lastIndexOf("@") + 1);
    let sameOrganisation = config.verifiedDomains.some((d) => d.toLowerCase() === domain);
    if (config.checkAddress) {
      setChecking(true);
      busy.current = true;
      try {
        const result = await config.checkAddress(email);
        if (result === "staff" || result === "not_allowed") return fail(ccRefusal(email, result));
        sameOrganisation = result === "allowed";
      } catch {
        // The server checks again when the reply is sent.
      } finally {
        busy.current = false;
        setChecking(false);
      }
    }
    if (sameOrganisation) return add({ email, name: null }, false);
    setAsk(email);
    return "asked";
  }

  /** Enter and Add share this; Done adds the typed text first and closes only when it was added. */
  async function submitTyped(then: "stay" | "close") {
    // A server check is already running for a typed address.
    if (busy.current) return;
    const outcome = text.trim() ? await addTyped(text) : "added";
    if (then === "close") {
      if (outcome === "added") close();
      else {
        // Done left something pending: focus the question or the error once it is shown.
        const target = outcome === "asked" ? askRef : errorRef;
        window.setTimeout(() => target.current?.focus(), 0);
      }
    }
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown" && visible.length > 0) {
      e.preventDefault();
      setActive((i) => (i + 1) % visible.length);
    } else if (e.key === "ArrowUp" && visible.length > 0) {
      e.preventDefault();
      setActive((i) => (i <= 0 ? visible.length - 1 : i - 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (listOpen && active >= 0 && visible[active]) add(visible[active], false);
      else if (text.trim()) void submitTyped("stay");
    } else if (e.key === "Backspace" && text === "" && value.selected.length > 0) {
      e.preventDefault();
      const last = value.selected[value.selected.length - 1];
      if (last) remove(last);
    } else if (e.key === "Escape") {
      e.preventDefault();
      if (listOpen) {
        setSuggestions([]);
        setActive(-1);
      } else {
        close();
      }
    }
  }

  return (
    <div className={styles.wrap}>
      <div className={styles.line}>
        <span className={styles.part}>
          <strong>To:</strong> {to || "No contact on this ticket"}
        </span>
        <span className={styles.part}>
          <strong>CC:</strong>{" "}
          <span title={value.selected.map(label).join(", ")}>{ccSummary(value.selected)}</span>
          {!value.keep && value.selected.length > 0 && (
            <span className={styles.muted}> (this reply only)</span>
          )}
        </span>
        <button
          ref={editRef}
          type="button"
          className={styles.edit}
          aria-expanded={open}
          aria-controls={editorId}
          aria-label="Edit CC"
          disabled={disabled}
          onClick={() => (open ? close() : setOpen(true))}
        >
          Edit
        </button>
      </div>

      {open && (
        <div id={editorId} className={styles.editor} role="group" aria-label="Edit CC">
          {value.selected.length === 0 ? (
            <p className={styles.muted}>Nobody in CC.</p>
          ) : (
            <ul className={styles.chips} aria-label="People in CC">
              {value.selected.map((p) => (
                <li key={p.email} className={styles.chip}>
                  <span className={styles.chipText}>
                    {label(p)}
                    {p.name && <span className={styles.muted}> {p.email}</span>}
                  </span>
                  <button
                    type="button"
                    className={styles.chipRemove}
                    aria-label={`Remove ${label(p)}`}
                    onClick={() => remove(p)}
                  >
                    <span aria-hidden="true">&times;</span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          {config.pending.filter((p) => !selectedEmails.has(p.email.toLowerCase())).length > 0 && (
            <ul className={styles.pending} aria-label="Waiting for confirmation">
              {config.pending
                .filter((p) => !selectedEmails.has(p.email.toLowerCase()))
                .map((p) => (
                  <li key={p.email}>
                    {label(p)} was CC&apos;d on an email and is waiting for confirmation.{" "}
                    <button
                      type="button"
                      className={styles.edit}
                      aria-label={`Confirm and add ${label(p)}`}
                      onClick={() => add(p, true)}
                    >
                      Confirm and add
                    </button>
                  </li>
                ))}
            </ul>
          )}

          <div className={styles.combo}>
            <label htmlFor={inputId} className={styles.addLabel}>
              Add someone (name or email)
            </label>
            <div className={styles.field}>
              <input
                ref={inputRef}
                id={inputId}
                type="text"
                role="combobox"
                autoComplete="off"
                spellCheck={false}
                className={styles.input}
                aria-autocomplete="list"
                aria-expanded={listOpen}
                aria-controls={listId}
                aria-activedescendant={listOpen && active >= 0 ? `${listId}-${active}` : undefined}
                value={text}
                onChange={(e) => {
                  setText(e.target.value);
                  setAsk(null);
                  setNotice(null);
                }}
                onKeyDown={onKeyDown}
              />
              <ul
                id={listId}
                role="listbox"
                aria-label="Contacts"
                className={styles.listbox}
                hidden={!listOpen}
              >
                {listOpen &&
                  visible.map((s, i) => (
                    <li
                      key={s.email}
                      id={`${listId}-${i}`}
                      role="option"
                      aria-selected={i === active}
                      className={i === active ? styles.optionActive : styles.option}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => add(s, false)}
                    >
                      {label(s)}
                      {s.name && <span className={styles.muted}> {s.email}</span>}
                    </li>
                  ))}
              </ul>
              <button
                type="button"
                className={styles.addButton}
                aria-label="Add to CC"
                disabled={checking}
                onClick={() => void submitTyped("stay")}
              >
                Add
              </button>
            </div>
            <p className={styles.hint}>
              Press Enter or Add. Contacts of {config.organisationName} are suggested.
            </p>
          </div>

          {ask && (
            <div className={styles.ask} role="alert">
              <p ref={askRef} tabIndex={-1}>
                {ask} is not from {config.organisationName}. Add anyway?
              </p>
              <div className={styles.askActions}>
                <button
                  type="button"
                  className={styles.button}
                  onClick={() => add({ email: ask, name: null }, true)}
                >
                  Add anyway
                </button>
                <button
                  type="button"
                  className={styles.secondary}
                  onClick={() => {
                    setAsk(null);
                    inputRef.current?.focus();
                  }}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}

          <div className={styles.footer}>
            <label htmlFor={keepId} className={styles.keep}>
              <input
                id={keepId}
                type="checkbox"
                checked={value.keep}
                onChange={(e) => onChange({ ...value, keep: e.target.checked })}
              />
              Keep on future replies
            </label>
            <button
              type="button"
              className={styles.button}
              disabled={checking}
              onClick={() => void submitTyped("close")}
            >
              Done
            </button>
          </div>
          <p className="visually-hidden" role="status">
            {notice && !notice.error ? notice.text : ""}
          </p>
          {notice?.error && (
            <p ref={errorRef} tabIndex={-1} className={styles.error} role="alert">
              {notice.text}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
