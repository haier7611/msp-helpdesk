import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import styles from "./Tabs.module.css";

export type TabItem = {
  id: string;
  label: ReactNode;
  content: ReactNode;
  /** Extra class for this tab button when it is selected (e.g. the internal-note colour). */
  selectedClassName?: string;
  className?: string;
};

export type TabsProps = {
  label: string;
  items: TabItem[];
  selectedId?: string;
  defaultSelectedId?: string;
  onSelect?: (id: string) => void;
  variant?: "underline" | "filled";
  className?: string;
  panelClassName?: string;
};

/**
 * WAI-ARIA tabs with automatic activation: Left/Right move and select, Home/End jump to the ends,
 * only the selected tab is in the Tab order (roving tabindex).
 */
export function Tabs({
  label,
  items,
  selectedId,
  defaultSelectedId,
  onSelect,
  variant = "underline",
  className,
  panelClassName,
}: TabsProps) {
  const baseId = useId();
  const [internal, setInternal] = useState(defaultSelectedId ?? items[0]?.id);
  const current = selectedId ?? internal;
  const tabRefs = useRef(new Map<string, HTMLButtonElement>());

  function select(id: string, focus: boolean) {
    if (selectedId === undefined) {
      setInternal(id);
    }
    onSelect?.(id);
    if (focus) {
      tabRefs.current.get(id)?.focus();
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next: number | undefined;
    if (event.key === "ArrowRight") next = (index + 1) % items.length;
    else if (event.key === "ArrowLeft") next = (index - 1 + items.length) % items.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = items.length - 1;
    if (next === undefined) return;
    event.preventDefault();
    const item = items[next];
    if (item) select(item.id, true);
  }

  return (
    <div className={className}>
      <div role="tablist" aria-label={label} className={`${styles.list} ${styles[variant]}`}>
        {items.map((item, index) => {
          const selected = item.id === current;
          return (
            <button
              key={item.id}
              ref={(node) => {
                if (node) tabRefs.current.set(item.id, node);
                else tabRefs.current.delete(item.id);
              }}
              type="button"
              role="tab"
              id={`${baseId}-tab-${item.id}`}
              aria-selected={selected}
              aria-controls={`${baseId}-panel-${item.id}`}
              tabIndex={selected ? 0 : -1}
              className={[
                styles.tab,
                item.className,
                selected ? styles.selected : "",
                selected ? item.selectedClassName : "",
              ]
                .filter(Boolean)
                .join(" ")}
              onClick={() => select(item.id, false)}
              onKeyDown={(event) => onKeyDown(event, index)}
            >
              {item.label}
            </button>
          );
        })}
      </div>
      {items.map((item) => (
        <div
          key={item.id}
          role="tabpanel"
          id={`${baseId}-panel-${item.id}`}
          aria-labelledby={`${baseId}-tab-${item.id}`}
          hidden={item.id !== current}
          tabIndex={0}
          className={panelClassName}
        >
          {item.id === current && item.content}
        </div>
      ))}
    </div>
  );
}
