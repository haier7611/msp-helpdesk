import {
  useId,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import styles from "./forms.module.css";

function cx(...names: (string | false | undefined | null)[]): string {
  return names.filter(Boolean).join(" ");
}

export type ButtonVariant = "primary" | "secondary" | "danger";

/** Classes for a link that looks like a button (e.g. a download). */
export function buttonClass(variant: ButtonVariant = "secondary"): string {
  return cx(styles.button, styles[variant]);
}

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
};

/** Real <button>: 38 px on fine pointers, 48 px on touch screens (--control-size). Defaults to type="button" so it never submits a form by accident. */
export function Button({ variant = "secondary", className, type, ...rest }: ButtonProps) {
  return (
    <button
      type={type ?? "button"}
      className={cx(styles.button, styles[variant], className)}
      {...rest}
    />
  );
}

/**
 * How wide a field is - xs for ports and small numbers, sm for selects, dates and times,
 * md for host names, emails and names, full (default) for free text.
 */
export type FieldSize = "xs" | "sm" | "md" | "full";

const SIZE_CLASS: Record<FieldSize, string | undefined> = {
  xs: styles.sizeXs,
  sm: styles.sizeSm,
  md: styles.sizeMd,
  full: styles.sizeFull,
};

type FieldShell = {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  /** Content-sized width; default full. */
  fieldSize?: FieldSize;
};

function useFieldIds(id: string | undefined, hint: ReactNode, error: ReactNode) {
  const generated = useId();
  const fieldId = id ?? generated;
  const hintId = hint ? `${fieldId}-hint` : undefined;
  const errorId = error ? `${fieldId}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(" ") || undefined;
  return { fieldId, hintId, errorId, describedBy };
}

function FieldLabel({
  fieldId,
  label,
  hint,
  hintId,
  error,
  errorId,
}: FieldShell & { fieldId: string; hintId?: string; errorId?: string }) {
  return (
    <>
      <label htmlFor={fieldId} className={styles.label}>
        {label}
      </label>
      {hint && (
        <span id={hintId} className={styles.hint}>
          {hint}
        </span>
      )}
      {error && (
        <span id={errorId} className={styles.error}>
          {error}
        </span>
      )}
    </>
  );
}

export type TextFieldProps = InputHTMLAttributes<HTMLInputElement> & FieldShell;

export function TextField({
  label,
  hint,
  error,
  id,
  className,
  fieldSize,
  ...rest
}: TextFieldProps) {
  const ids = useFieldIds(id, hint, error);
  return (
    <div className={cx(styles.field, fieldSize && SIZE_CLASS[fieldSize], className)}>
      <FieldLabel {...ids} label={label} hint={hint} error={error} />
      <input
        id={ids.fieldId}
        className={styles.control}
        aria-describedby={ids.describedBy}
        aria-invalid={error ? true : undefined}
        {...rest}
      />
    </div>
  );
}

export type SelectOption = string | { value: string; label: string };

export type SelectProps = SelectHTMLAttributes<HTMLSelectElement> &
  FieldShell & { options: readonly SelectOption[] };

export function Select({
  label,
  hint,
  error,
  id,
  className,
  options,
  fieldSize,
  ...rest
}: SelectProps) {
  const ids = useFieldIds(id, hint, error);
  return (
    <div className={cx(styles.field, fieldSize && SIZE_CLASS[fieldSize], className)}>
      <FieldLabel {...ids} label={label} hint={hint} error={error} />
      <select
        id={ids.fieldId}
        className={styles.control}
        aria-describedby={ids.describedBy}
        aria-invalid={error ? true : undefined}
        {...rest}
      >
        {options.map((option) => {
          const { value, label: text } =
            typeof option === "string" ? { value: option, label: option } : option;
          return (
            <option key={value} value={value}>
              {text}
            </option>
          );
        })}
      </select>
    </div>
  );
}

export type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & FieldShell;

export function Textarea({ label, hint, error, id, className, fieldSize, ...rest }: TextareaProps) {
  const ids = useFieldIds(id, hint, error);
  return (
    <div className={cx(styles.field, fieldSize && SIZE_CLASS[fieldSize], className)}>
      <FieldLabel {...ids} label={label} hint={hint} error={error} />
      <textarea
        id={ids.fieldId}
        className={cx(styles.control, styles.textarea)}
        aria-describedby={ids.describedBy}
        aria-invalid={error ? true : undefined}
        rows={rest.rows ?? 4}
        {...rest}
      />
    </div>
  );
}

export type RadioOption = { value: string; label: ReactNode };

export type RadioGroupProps = {
  legend: ReactNode;
  name: string;
  options: readonly RadioOption[];
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  hint?: ReactNode;
  boxed?: boolean;
  /** Options side by side (wrapping on narrow screens). */
  inline?: boolean;
  className?: string;
};

/** A <fieldset> of native radios - arrow keys move between options for free. */
export function RadioGroup({
  legend,
  name,
  options,
  value,
  defaultValue,
  onChange,
  hint,
  boxed = false,
  inline = false,
  className,
}: RadioGroupProps) {
  const hintId = useId();
  return (
    <fieldset
      className={cx(styles.fieldset, boxed && styles.boxed, inline && styles.inline, className)}
      aria-describedby={hint ? hintId : undefined}
    >
      <legend className={styles.legend}>{legend}</legend>
      {options.map((option) => (
        <label key={option.value} className={styles.choice}>
          <input
            type="radio"
            name={name}
            value={option.value}
            {...(value !== undefined
              ? { checked: value === option.value }
              : { defaultChecked: defaultValue === option.value })}
            onChange={(event) => onChange?.(event.target.value)}
          />
          <span>{option.label}</span>
        </label>
      ))}
      {hint && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
    </fieldset>
  );
}

export type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & {
  label: ReactNode;
};

export function Checkbox({ label, className, ...rest }: CheckboxProps) {
  return (
    <label className={cx(styles.choice, className)}>
      <input type="checkbox" {...rest} />
      <span>{label}</span>
    </label>
  );
}

/** Fields of different widths side by side (e.g. Port + Security), wrapping when narrow. */
export function FieldRow({ children }: { children: ReactNode }) {
  return <div className={styles.fieldRow}>{children}</div>;
}

/**
 * One line of help, with the longer explanation behind an info toggle. The toggle is a
 * real button with aria-expanded and aria-controls; the detail is plain text in the page, so screen
 * readers reach it in order once it is open.
 */
export function HelpToggle({
  children,
  detail,
  topic,
}: {
  /** The one-line help, always shown. */
  children: ReactNode;
  /** The longer explanation, shown on demand. */
  detail: ReactNode;
  /** What the detail is about, for the button's accessible name ("More about ..."). */
  topic: string;
}) {
  const [open, setOpen] = useState(false);
  const detailId = useId();
  return (
    <>
      <p className={styles.helpLine}>
        <span className={styles.helpText}>{children}</span>
        <button
          type="button"
          className={styles.infoButton}
          aria-expanded={open}
          aria-controls={detailId}
          aria-label={`More about ${topic}`}
          onClick={() => setOpen((o) => !o)}
        >
          <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" />
            <path d="M12 11v6M12 7.5v.5" stroke="currentColor" strokeWidth="2" />
          </svg>
        </button>
      </p>
      <p id={detailId} className={styles.helpDetail} hidden={!open}>
        {detail}
      </p>
    </>
  );
}

/** Rarely used fields under a collapsed disclosure (native <details>). */
export function Advanced({
  summary = "Advanced",
  defaultOpen = false,
  children,
}: {
  summary?: ReactNode;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  return (
    <details className={styles.advanced} open={defaultOpen || undefined}>
      <summary>{summary}</summary>
      <div className={styles.advancedBody}>{children}</div>
    </details>
  );
}
