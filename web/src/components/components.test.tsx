import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { axeViolations, renderWithToast } from "../test/helpers";
import { PriorityBadge, SlaBadge, StatusBadge, SupporterStatusBadge } from "./Badge";
import { InternalNoteCard, MessageCard } from "./Conversation";
import { DataTable, Identifier, type Column } from "./DataTable";
import { Button, Checkbox, RadioGroup, Select, TextField, Textarea } from "./forms";
import { Modal } from "./Modal";
import { SidePanel } from "./SidePanel";
import { Tabs } from "./Tabs";
import { TOAST_TIMEOUT_MS } from "./Toast";
import { useToast } from "./toastContext";

describe("Badge", () => {
  it.each([
    ["P1", "Priority P1 Critical"],
    ["P2", "Priority P2 High"],
    ["P3", "Priority P3 Medium"],
    ["P4", "Priority P4 Low"],
  ] as const)("priority %s shows its text and a full accessible name", (priority, name) => {
    const { container } = render(<PriorityBadge priority={priority} />);
    expect(container).toHaveTextContent(priority);
    expect(screen.getByText(name)).toHaveClass("visually-hidden");
  });

  it("long priority badge shows the name in visible text", () => {
    render(<PriorityBadge priority="P2" long />);
    expect(screen.getByText("P2 High")).toBeVisible();
  });

  it.each([
    ["breached", "Breached 12 min", "Breached 12 min"],
    ["risk", "Response due 14:30", "At risk: Response due 14:30"],
    ["ok", "Resolve by Thu", "On track: Resolve by Thu"],
    ["paused", "Paused", "Paused"],
  ] as const)("SLA %s always carries the state in text", (state, visible, spoken) => {
    const { container } = render(<SlaBadge state={state} text={visible} />);
    expect(container).toHaveTextContent(spoken);
  });

  it("SLA badge without text shows the state name", () => {
    render(<SlaBadge state="risk" />);
    expect(screen.getByText("At risk")).toBeInTheDocument();
  });

  it("status and supporter status badges show their text", () => {
    render(
      <>
        <StatusBadge status="Pending on vendor" />
        <SupporterStatusBadge status="Invited" />
      </>,
    );
    expect(screen.getByText("Pending on vendor")).toBeInTheDocument();
    expect(screen.getByText("Invited")).toBeInTheDocument();
  });
});

describe("form controls", () => {
  it("Button defaults to type=button and takes a variant", () => {
    const onClick = vi.fn();
    render(
      <Button variant="danger" onClick={onClick}>
        Deactivate
      </Button>,
    );
    const button = screen.getByRole("button", { name: "Deactivate" });
    expect(button).toHaveAttribute("type", "button");
    expect(button.className).toMatch(/danger/);
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("TextField links its label, hint and error", () => {
    render(<TextField label="Display name" hint="Shown on emails" error="Required" />);
    const input = screen.getByLabelText("Display name");
    expect(input).toHaveAccessibleDescription("Shown on emails Required");
    expect(input).toHaveAttribute("aria-invalid", "true");
  });

  it("Select and Textarea are labelled", () => {
    render(
      <>
        <Select label="Status" options={["Open", "Resolved"]} defaultValue="Resolved" />
        <Textarea label="Describe the problem" />
      </>,
    );
    expect(screen.getByLabelText("Status")).toHaveValue("Resolved");
    expect(screen.getByLabelText("Describe the problem").tagName).toBe("TEXTAREA");
  });

  it("RadioGroup is a labelled group and reports changes", () => {
    const onChange = vi.fn();
    render(
      <RadioGroup
        legend="Role"
        name="role"
        options={[
          { value: "Agent", label: "Agent" },
          { value: "Admin", label: "Admin" },
        ]}
        defaultValue="Agent"
        onChange={onChange}
      />,
    );
    const group = screen.getByRole("group", { name: "Role" });
    expect(within(group).getByLabelText("Agent")).toBeChecked();
    fireEvent.click(within(group).getByLabelText("Admin"));
    expect(onChange).toHaveBeenCalledWith("Admin");
  });

  it("Checkbox toggles through its label", () => {
    render(<Checkbox label="All organisations" />);
    const box = screen.getByLabelText("All organisations");
    fireEvent.click(box);
    expect(box).toBeChecked();
  });
});

type Row = { id: string; name: string };
const COLUMNS: Column<Row>[] = [{ key: "name", header: "Name", render: (r) => r.name }];

describe("DataTable", () => {
  it("renders rows inside a focusable, labelled scroll region", () => {
    render(
      <DataTable
        caption="People"
        columns={COLUMNS}
        rows={[{ id: "1", name: "Sam Patel" }]}
        rowKey={(r) => r.id}
      />,
    );
    const region = screen.getByRole("region", { name: "People" });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(within(region).getByRole("table", { name: "People" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Name" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Sam Patel" })).toBeInTheDocument();
  });

  it("stackOnPhone adds a per-cell label for the phone card layout, hidden from screen readers", () => {
    type Row = { id: string; ref: string; sla: string | null };
    const columns: Column<Row>[] = [
      { key: "ref", header: "Ticket", rowHeader: true, render: (r) => r.ref },
      { key: "sla", header: "SLA", render: (r) => r.sla },
      { key: "x", header: <span>Actions</span>, render: () => "Open" },
    ];
    render(
      <DataTable
        caption="Queue"
        stackOnPhone
        columns={columns}
        rows={[{ id: "1", ref: "ACD-001", sla: null }]}
        rowKey={(r) => r.id}
      />,
    );
    const region = screen.getByRole("region", { name: "Queue" });
    const rowHeader = screen.getByRole("rowheader");
    // The visible label is aria-hidden, so the accessible name is just the value.
    expect(rowHeader).toHaveAccessibleName("ACD-001");
    expect(rowHeader).toHaveTextContent("TicketACD-001");
    // No label in an empty cell (it would show "SLA" with nothing beside it), nor for a
    // non-text header.
    const cells = within(region).getAllByRole("cell");
    expect(cells[0]).toBeEmptyDOMElement();
    expect(cells[1]).toHaveTextContent(/^Open$/);
  });

  it("without stackOnPhone, cells carry no extra label", () => {
    render(
      <DataTable
        caption="People"
        columns={COLUMNS}
        rows={[{ id: "1", name: "Sam Patel" }]}
        rowKey={(r) => r.id}
      />,
    );
    expect(screen.getByRole("cell", { name: "Sam Patel" })).toHaveTextContent(/^Sam Patel$/);
  });

  it("an actions column keeps its buttons on one row and sizes to them", () => {
    const columns: Column<Row>[] = [
      { key: "name", header: "Name", render: (r) => r.name },
      {
        key: "actions",
        header: <span className="visually-hidden">Actions</span>,
        actions: true,
        render: (r) => (
          <>
            <Button aria-label={`Edit ${r.name}`}>Edit</Button>
            <Button aria-label={`Remove ${r.name}`}>Remove</Button>
          </>
        ),
      },
    ];
    render(
      <DataTable
        caption="People"
        columns={columns}
        rows={[{ id: "1", name: "Sam Patel" }]}
        rowKey={(r) => r.id}
      />,
    );
    const edit = screen.getByRole("button", { name: "Edit Sam Patel" });
    const remove = screen.getByRole("button", { name: "Remove Sam Patel" });
    // Both buttons sit in one no-wrap flex row inside a content-sized, no-wrap cell.
    const group = edit.parentElement as HTMLElement;
    expect(group).toBe(remove.parentElement);
    expect(group.className).toMatch(/rowActions/);
    const cell = group.closest("td") as HTMLElement;
    expect(cell.className).toMatch(/actionsCell/);
    expect(screen.getAllByRole("columnheader")[1]?.className ?? "").toMatch(/actionsCell/);
  });

  it("Identifier offers a line break before the @ and keeps the full address as text", () => {
    const { container } = render(<Identifier value="longsupportaddress@example.test" />);
    const span = container.firstElementChild as HTMLElement;
    expect(span).toHaveTextContent(/^longsupportaddress@example\.test$/);
    expect(span.className).toMatch(/identifier/);
    expect(span.innerHTML).toBe("longsupportaddress<wbr>@example.test");
  });

  it("shows the empty message when there are no rows", () => {
    render(
      <DataTable
        caption="People"
        columns={COLUMNS}
        rows={[]}
        rowKey={(r) => r.id}
        emptyMessage="No supporters yet."
      />,
    );
    expect(screen.getByText("No supporters yet.")).toBeInTheDocument();
  });

  it("shows a loading state instead of rows while loading", () => {
    render(
      <DataTable
        caption="People"
        columns={COLUMNS}
        rows={[{ id: "1", name: "Sam Patel" }]}
        rowKey={(r) => r.id}
        loading
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Loading...");
    expect(screen.queryByText("Sam Patel")).not.toBeInTheDocument();
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
  });
});

function renderTabs(onSelect = vi.fn()) {
  render(
    <Tabs
      label="Sections"
      onSelect={onSelect}
      items={[
        { id: "a", label: "Overview", content: <p>Overview panel</p> },
        { id: "b", label: "Access", content: <p>Access panel</p> },
        { id: "c", label: "Security", content: <p>Security panel</p> },
      ]}
    />,
  );
  return onSelect;
}

describe("Tabs", () => {
  it("selects the first tab and only it is in the Tab order", () => {
    renderTabs();
    const tabs = screen.getAllByRole("tab");
    expect(tabs[0]).toHaveAttribute("aria-selected", "true");
    expect(tabs.map((t) => t.getAttribute("tabindex"))).toEqual(["0", "-1", "-1"]);
    expect(screen.getByRole("tabpanel", { name: "Overview" })).toHaveTextContent("Overview panel");
  });

  it("ArrowRight / ArrowLeft move focus and selection, wrapping at the ends", () => {
    const onSelect = renderTabs();
    const overview = screen.getByRole("tab", { name: "Overview" });
    overview.focus();
    fireEvent.keyDown(overview, { key: "ArrowRight" });
    const access = screen.getByRole("tab", { name: "Access" });
    expect(access).toHaveFocus();
    expect(access).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tabpanel")).toHaveTextContent("Access panel");
    expect(onSelect).toHaveBeenLastCalledWith("b");

    fireEvent.keyDown(overview.ownerDocument.activeElement as Element, { key: "ArrowLeft" });
    fireEvent.keyDown(overview, { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: "Security" })).toHaveFocus();
  });

  it("Home and End jump to the first and last tab", () => {
    renderTabs();
    const overview = screen.getByRole("tab", { name: "Overview" });
    fireEvent.keyDown(overview, { key: "End" });
    expect(screen.getByRole("tab", { name: "Security" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(screen.getByRole("tab", { name: "Security" }), { key: "Home" });
    expect(screen.getByRole("tab", { name: "Overview" })).toHaveFocus();
  });

  it("other keys do nothing; click selects", () => {
    renderTabs();
    fireEvent.keyDown(screen.getByRole("tab", { name: "Overview" }), { key: "a" });
    expect(screen.getByRole("tab", { name: "Overview" })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("tab", { name: "Security" }));
    expect(screen.getByRole("tabpanel")).toHaveTextContent("Security panel");
  });
});

function ModalHarness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      <Modal
        open={open}
        title="Invite supporter"
        onClose={() => setOpen(false)}
        footer={<button type="button">Send invitation</button>}
      >
        <label>
          Email
          <input />
        </label>
      </Modal>
    </>
  );
}

describe("Modal", () => {
  it("opens as a labelled modal dialog with focus inside", () => {
    render(<ModalHarness />);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    const dialog = screen.getByRole("dialog", { name: "Invite supporter" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
  });

  it("Escape closes it and focus returns to the opener", () => {
    render(<ModalHarness />);
    const opener = screen.getByRole("button", { name: "Open" });
    opener.focus();
    fireEvent.click(opener);
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it("Tab from the last element wraps to the first, Shift+Tab from the first to the last", () => {
    render(<ModalHarness />);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    const close = screen.getByRole("button", { name: "Close" });
    const last = screen.getByRole("button", { name: "Send invitation" });
    last.focus();
    fireEvent.keyDown(last, { key: "Tab" });
    expect(close).toHaveFocus();
    fireEvent.keyDown(close, { key: "Tab", shiftKey: true });
    expect(last).toHaveFocus();
  });

  it("the close button and a click on the backdrop close it", () => {
    render(<ModalHarness />);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    const backdrop = screen.getByRole("dialog").parentElement as HTMLElement;
    fireEvent.mouseDown(screen.getByRole("dialog"));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.mouseDown(backdrop);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("renders nothing while closed", () => {
    render(
      <Modal open={false} title="Hidden" onClose={() => undefined}>
        x
      </Modal>,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

function ToastButton({ message, tone }: { message: string; tone?: "error" }) {
  const toast = useToast();
  return (
    <button type="button" onClick={() => toast.show(message, tone)}>
      Show
    </button>
  );
}

describe("Toast", () => {
  it("announces a message in the live region and can be dismissed", () => {
    renderWithToast(<ToastButton message="Saved" />);
    fireEvent.click(screen.getByRole("button", { name: "Show" }));
    const region = screen.getByTestId("toast-region");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toHaveTextContent("Saved");
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(region).not.toHaveTextContent("Saved");
  });

  it("errors use role=alert", () => {
    renderWithToast(<ToastButton message="Could not save" tone="error" />);
    fireEvent.click(screen.getByRole("button", { name: "Show" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Could not save");
  });

  it("disappears by itself after the timeout and keeps at most three", () => {
    vi.useFakeTimers();
    try {
      renderWithToast(<ToastButton message="Hello" />);
      const button = screen.getByRole("button", { name: "Show" });
      for (let i = 0; i < 5; i++) fireEvent.click(button);
      expect(screen.getAllByRole("button", { name: "Dismiss" })).toHaveLength(3);
      act(() => {
        vi.advanceTimersByTime(TOAST_TIMEOUT_MS + 1);
      });
      expect(screen.queryByRole("button", { name: "Dismiss" })).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("useToast outside the provider fails loudly", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    expect(() => render(<ToastButton message="x" />)).toThrow(/ToastProvider/);
  });
});

function PopupButtons() {
  const toast = useToast();
  return (
    <>
      <button type="button" onClick={() => toast.showError("Could not add that address.")}>
        Fail
      </button>
      <button type="button" onClick={() => toast.showSuccess("Added.")}>
        Succeed
      </button>
    </>
  );
}

describe("Error pop-up (showError / showSuccess)", () => {
  it("shows an alert at the top that stays until closed, and passes axe", async () => {
    vi.useFakeTimers();
    try {
      renderWithToast(<PopupButtons />);
      fireEvent.click(screen.getByRole("button", { name: "Fail" }));
      const popups = screen.getByTestId("error-popups");
      expect(within(popups).getByRole("alert")).toHaveTextContent("Could not add that address.");
      // The same message again is not stacked.
      fireEvent.click(screen.getByRole("button", { name: "Fail" }));
      expect(within(popups).getAllByRole("alert")).toHaveLength(1);
      act(() => {
        vi.advanceTimersByTime(TOAST_TIMEOUT_MS * 3);
      });
      expect(within(popups).getByRole("alert")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Close error" }));
    expect(within(screen.getByTestId("error-popups")).queryByRole("alert")).toBeNull();
  });

  it("showSuccess is a short toast in the polite region", () => {
    renderWithToast(<PopupButtons />);
    fireEvent.click(screen.getByRole("button", { name: "Succeed" }));
    expect(screen.getByTestId("toast-region")).toHaveTextContent("Added.");
    expect(screen.getByTestId("error-popups")).toBeEmptyDOMElement();
  });
});

describe("Conversation cards", () => {
  it("an internal note is labelled INTERNAL in text and named as a note", () => {
    render(
      <InternalNoteCard author="Minh Le" meta="Internal note - 10:05">
        <p>Checked logs</p>
      </InternalNoteCard>,
    );
    const note = screen.getByRole("article", { name: "Internal note by Minh Le" });
    expect(within(note).getByText("INTERNAL")).toBeInTheDocument();
  });

  it("a message card shows author, meta and attachment", () => {
    render(
      <MessageCard kind="client" author="Dr Maria Gomez" meta="via email" attachment="a.png">
        <p>Hi</p>
      </MessageCard>,
    );
    expect(screen.getByText("Dr Maria Gomez")).toBeInTheDocument();
    expect(screen.getByText("a.png")).toBeInTheDocument();
  });
});

describe("SidePanel", () => {
  it("SidePanel is a labelled complementary region with an optional close button", () => {
    const onClose = vi.fn();
    render(
      <SidePanel label="Supporter detail" header={<h2>Minh Le</h2>} onClose={onClose}>
        body
      </SidePanel>,
    );
    expect(screen.getByRole("complementary", { name: "Supporter detail" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Close supporter detail" }));
    expect(onClose).toHaveBeenCalled();
  });
});
