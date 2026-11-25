import { Fragment, type ReactNode } from "react";
import styles from "./DataTable.module.css";

export type Column<T> = {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  /** Keep the cell on one line (refs, dates, chips). */
  nowrap?: boolean;
  /**
   * Row action buttons: kept on one row (never "Remove" under "Edit"), and the column is only as
   * wide as its buttons. At phone width (stacked cards) the buttons may wrap.
   */
  actions?: boolean;
  muted?: boolean;
  /** Mark this column's cell as the row header (th scope=row) for screen readers. */
  rowHeader?: boolean;
  /** Label shown beside the value when rows are stacked as cards on a phone; defaults to the
   *  header when that is plain text. */
  label?: string;
};

export type DataTableProps<T> = {
  /** Visible caption is optional; this is always the table's accessible name. */
  caption: string;
  columns: Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  loading?: boolean;
  emptyMessage?: ReactNode;
  /** Narrowest width before the box scrolls sideways (phone widths). */
  minWidth?: number;
  compact?: boolean;
  selectedKey?: string;
  /**
   * At phone width (<= 640 px) show each row as a stacked card (label: value per line) instead of
   * a table that scrolls sideways and hides key columns. Wider screens keep the table.
   */
  stackOnPhone?: boolean;
};

/**
 * Table in a horizontally scrolling box. The box is a focusable, labelled region so keyboard
 * users can scroll it too. Loading and empty states replace the body with one full-width row.
 */
export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  loading = false,
  emptyMessage = "Nothing to show yet.",
  minWidth = 640,
  compact = false,
  selectedKey,
  stackOnPhone = false,
}: DataTableProps<T>) {
  const minWidthClass = minWidthClassFor(minWidth);
  const labelOf = (column: Column<T>) =>
    column.label ?? (typeof column.header === "string" ? column.header : "");
  return (
    <div
      className={`${styles.scroller} ${stackOnPhone ? styles.stack : ""}`}
      role="region"
      aria-label={caption}
      tabIndex={0}
    >
      <table
        className={`${styles.table} ${minWidthClass} ${compact ? styles.compact : ""}`}
        aria-busy={loading || undefined}
      >
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={column.actions ? styles.actionsCell : undefined}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr>
              <td colSpan={columns.length} className={styles.state}>
                <span role="status">Loading...</span>
              </td>
            </tr>
          ) : rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className={styles.state}>
                {emptyMessage}
              </td>
            </tr>
          ) : (
            rows.map((row) => {
              const key = rowKey(row);
              return (
                <tr key={key} className={key === selectedKey ? styles.selected : undefined}>
                  {columns.map((column) => {
                    const className = [
                      column.nowrap ? styles.nowrap : "",
                      column.muted ? styles.muted : "",
                      column.actions ? styles.actionsCell : "",
                    ]
                      .filter(Boolean)
                      .join(" ");
                    // The stacked-card label repeats the column header, which screen readers already
                    // announce from the table, so it is hidden from them.
                    const rendered = column.render(row);
                    const content = column.actions ? (
                      <div className={styles.rowActions}>{rendered}</div>
                    ) : (
                      rendered
                    );
                    const empty = rendered === null || rendered === undefined || rendered === "";
                    const label =
                      stackOnPhone && !empty && labelOf(column) ? (
                        <span className={styles.cellLabel} aria-hidden="true">
                          {labelOf(column)}
                        </span>
                      ) : null;
                    // One wrapper, so a multi-part value stays in the value column of the card.
                    const value = label ? (
                      <div className={styles.cellValue}>{content}</div>
                    ) : (
                      content
                    );
                    return column.rowHeader ? (
                      <th key={column.key} scope="row" className={className || undefined}>
                        {label}
                        {value}
                      </th>
                    ) : (
                      <td key={column.key} className={className || undefined}>
                        {label}
                        {value}
                      </td>
                    );
                  })}
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}

/**
 * An email address, domain, IP address or other long identifier in a table cell: the normal UI font
 * (not monospace), line breaks offered before an "@" and after a ":" so "name@example.com" splits
 * as "name" / "@example.com", and a mid-word break only when nothing else fits (overflow-wrap:
 * break-word, which unlike "anywhere" does not let the table squeeze the column to a few letters).
 */
export function Identifier({ value }: { value: string }) {
  const parts = value.split(/(?=@)|(?<=:)/);
  return (
    <span className={styles.identifier}>
      {parts.map((part, i) => (
        <Fragment key={i}>
          {i > 0 && <wbr />}
          {part}
        </Fragment>
      ))}
    </span>
  );
}

// Fixed steps instead of an inline style, because the Caddy CSP has no 'unsafe-inline' for styles.
function minWidthClassFor(minWidth: number): string {
  if (minWidth >= 860) return styles.min860 ?? "";
  if (minWidth >= 640) return styles.min640 ?? "";
  return styles.min380 ?? "";
}
