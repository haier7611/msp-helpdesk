import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { axeViolations, renderWithToast } from "../test/helpers";
import { Composer, type ComposerProps, type ComposerSend } from "./Composer";
import { ccSummary, type CcCheckResult, type CcPerson, type ReplyCcConfig } from "./ccRecipients";

// ADR-035: "To: reporter   CC: names   Edit" in the Reply to client tab. Synthetic people only.

const SAM: CcPerson = { email: "sam.okoye@acmedental.example", name: "Sam Okoye" };
const OFFICE: CcPerson = { email: "office@acmedental.example", name: null };
const LEE: CcPerson = { email: "lee.park@acmedental.example", name: "Lee Park" };

function config(overrides: Partial<ReplyCcConfig> = {}): ReplyCcConfig {
  return {
    initial: [SAM, OFFICE],
    pending: [{ email: "it@vendor.example", name: null }],
    organisationName: "Acme Dental",
    verifiedDomains: ["acmedental.example"],
    searchContacts: vi.fn((q: string) =>
      Promise.resolve([SAM, OFFICE, LEE].filter((p) => (p.name ?? p.email).includes(q))),
    ),
    ...overrides,
  };
}

function setup(cc: ReplyCcConfig = config(), overrides: Partial<ComposerProps> = {}) {
  const onSend = vi.fn<(send: ComposerSend) => Promise<void>>(() => Promise.resolve());
  const props: ComposerProps = {
    draftKey: null,
    to: "Maria Gomez <maria@acmedental.example>",
    defaultTab: "reply",
    ccConfig: cc,
    onSend,
    ...overrides,
  };
  renderWithToast(<Composer {...props} />);
  return { onSend, cc };
}

function typeReply() {
  const editor = screen.getByRole("textbox", { name: "Reply text" });
  editor.innerHTML = "<p>Hello</p>";
  fireEvent.input(editor);
}

function openEditor() {
  fireEvent.click(screen.getByRole("button", { name: "Edit CC" }));
  return screen.getByRole("group", { name: "Edit CC" });
}

function addBox() {
  return screen.getByRole("combobox", { name: "Add someone (name or email)" });
}

afterEach(() => {
  vi.useRealTimers();
});

describe("Reply CC line and editor (ADR-035)", () => {
  it("shows To, a short CC summary and an Edit link, and no CC editor on the Internal note tab", async () => {
    setup(config({ initial: [SAM, OFFICE, LEE] }));
    expect(screen.getByText("Maria Gomez <maria@acmedental.example>")).toBeInTheDocument();
    expect(screen.getByText("Sam Okoye, office@acmedental.example +1")).toBeInTheDocument();
    const edit = screen.getByRole("button", { name: "Edit CC" });
    expect(edit).toHaveAttribute("aria-expanded", "false");
    expect(await axeViolations()).toEqual([]);

    fireEvent.click(screen.getByRole("tab", { name: "Internal note" }));
    expect(screen.queryByRole("button", { name: "Edit CC" })).toBeNull();
  });

  it("summarises CC with +N", () => {
    expect(ccSummary([])).toBe("None");
    expect(ccSummary([SAM])).toBe("Sam Okoye");
    expect(ccSummary([SAM, OFFICE, LEE])).toBe("Sam Okoye, office@acmedental.example +1");
  });

  it("toggles the editor, lists chips with accessible remove buttons and passes axe", async () => {
    setup();
    const group = openEditor();
    expect(screen.getByRole("button", { name: "Edit CC" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    const chips = within(group).getByRole("list", { name: "People in CC" });
    expect(within(chips).getAllByRole("listitem")).toHaveLength(2);
    expect(within(group).getByRole("button", { name: "Remove Sam Okoye" })).toBeInTheDocument();
    expect(within(group).getByRole("checkbox", { name: "Keep on future replies" })).toBeChecked();
    expect(await axeViolations()).toEqual([]);
  });

  it("removes a chip and sends the new list with keep on; Done returns focus to Edit", async () => {
    const { onSend } = setup();
    const group = openEditor();
    fireEvent.click(within(group).getByRole("button", { name: "Remove Sam Okoye" }));
    expect(within(group).queryByRole("button", { name: "Remove Sam Okoye" })).toBeNull();
    fireEvent.click(within(group).getByRole("button", { name: "Done" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit CC" })).toHaveFocus());
    expect(screen.queryByRole("group", { name: "Edit CC" })).toBeNull();

    typeReply();
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    expect(onSend.mock.calls[0]?.[0]).toMatchObject({
      kind: "reply",
      cc: {
        recipients: ["office@acmedental.example"],
        keepOnFutureReplies: true,
        confirmExternal: [],
      },
    });
  });

  it("sends no CC choice when nothing changed", async () => {
    const { onSend } = setup();
    typeReply();
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    expect(onSend.mock.calls[0]?.[0]).not.toHaveProperty("cc");
  });

  it("suggests contacts in a combobox; arrow keys and Enter add one; keep off is sent", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { onSend, cc } = setup();
    const group = openEditor();
    const box = addBox();
    fireEvent.change(box, { target: { value: "Lee" } });
    await act(async () => {
      vi.advanceTimersByTime(250);
    });
    const list = await screen.findByRole("listbox", { name: "Contacts" });
    expect(cc.searchContacts).toHaveBeenCalledWith("Lee", expect.any(AbortSignal));
    expect(box).toHaveAttribute("aria-expanded", "true");
    fireEvent.keyDown(box, { key: "ArrowDown" });
    const option = within(list).getByRole("option", { name: /Lee Park/ });
    expect(option).toHaveAttribute("aria-selected", "true");
    expect(box).toHaveAttribute("aria-activedescendant", option.id);
    fireEvent.keyDown(box, { key: "Enter" });
    expect(within(group).getByRole("button", { name: "Remove Lee Park" })).toBeInTheDocument();

    fireEvent.click(within(group).getByRole("checkbox", { name: "Keep on future replies" }));
    typeReply();
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    expect(onSend.mock.calls[0]?.[0]).toMatchObject({
      cc: {
        recipients: [SAM.email, OFFICE.email, LEE.email],
        keepOnFutureReplies: false,
        confirmExternal: [],
      },
    });
  });

  it("adds a verified-domain address at once but asks before an outside address", async () => {
    const { onSend } = setup();
    const group = openEditor();
    const box = addBox();
    fireEvent.change(box, { target: { value: "reception@acmedental.example" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(
      within(group).getByRole("button", { name: "Remove reception@acmedental.example" }),
    ).toBeInTheDocument();

    fireEvent.change(box, { target: { value: "Pat@Outside.example" } });
    fireEvent.keyDown(box, { key: "Enter" });
    const ask = within(group).getByRole("alert");
    expect(ask).toHaveTextContent("pat@outside.example is not from Acme Dental. Add anyway?");
    expect(within(group).queryByRole("button", { name: "Remove pat@outside.example" })).toBeNull();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(ask).getByRole("button", { name: "Add anyway" }));
    expect(
      within(group).getByRole("button", { name: "Remove pat@outside.example" }),
    ).toBeInTheDocument();

    typeReply();
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    expect(onSend.mock.calls[0]?.[0]).toMatchObject({
      cc: { confirmExternal: ["pat@outside.example"] },
    });
  });

  it("cancelling the outside-address question adds nothing", () => {
    setup();
    const group = openEditor();
    const box = addBox();
    fireEvent.change(box, { target: { value: "pat@outside.example" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.click(within(group).getByRole("button", { name: "Cancel" }));
    expect(within(group).queryByRole("alert")).toBeNull();
    expect(within(group).queryByRole("button", { name: "Remove pat@outside.example" })).toBeNull();
  });

  it("confirms a pending participant from an email", () => {
    setup();
    const group = openEditor();
    fireEvent.click(
      within(group).getByRole("button", { name: "Confirm and add it@vendor.example" }),
    );
    expect(
      within(group).getByRole("button", { name: "Remove it@vendor.example" }),
    ).toBeInTheDocument();
  });

  it("refuses an invalid address", () => {
    setup();
    const group = openEditor();
    const box = addBox();
    fireEvent.change(box, { target: { value: "not an address" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(within(group).getByRole("alert")).toHaveTextContent(
      "not an address is not a valid email address.",
    );
  });

  it("stops at 10 people with a message", () => {
    const ten = Array.from({ length: 10 }, (_, i) => ({
      email: `person${i}@acmedental.example`,
      name: null,
    }));
    setup(config({ initial: ten }));
    const group = openEditor();
    const box = addBox();
    fireEvent.change(box, { target: { value: "one.more@acmedental.example" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(within(group).getByRole("alert")).toHaveTextContent(
      "At most 10 people can be in CC. Remove someone first.",
    );
    expect(within(group).getAllByRole("button", { name: /^Remove / })).toHaveLength(10);
  });

  it("Backspace on an empty box removes the last chip; Esc closes and focuses Edit", async () => {
    setup();
    const group = openEditor();
    const box = addBox();
    fireEvent.keyDown(box, { key: "Backspace" });
    expect(
      within(group).queryByRole("button", { name: "Remove office@acmedental.example" }),
    ).toBeNull();
    expect(within(group).getByRole("button", { name: "Remove Sam Okoye" })).toBeInTheDocument();
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("group", { name: "Edit CC" })).toBeNull();
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit CC" })).toHaveFocus());
  });
});

// Regression: a typed address and Done left "CC: None"; errors must be visible pop-ups.
describe("Typed address with Done or Add, early refusal and send-time errors", () => {
  function checked(answers: Record<string, CcCheckResult>) {
    return config({
      checkAddress: vi.fn((email: string) => Promise.resolve(answers[email] ?? "external")),
    });
  }

  function popups() {
    return screen.getByTestId("error-popups");
  }

  it("Done with a typed same-organisation address adds it and closes", async () => {
    setup(checked({ "reception@acmedental.example": "allowed" }));
    openEditor();
    fireEvent.change(addBox(), { target: { value: "reception@acmedental.example" } });
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await waitFor(() => expect(screen.queryByRole("group", { name: "Edit CC" })).toBeNull());
    expect(screen.getByText("Sam Okoye, office@acmedental.example +1")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit CC" })).toHaveFocus());
  });

  it("Done with a typed address works without the server check too", async () => {
    setup(config({ initial: [] }));
    openEditor();
    fireEvent.change(addBox(), { target: { value: "reception@acmedental.example" } });
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await waitFor(() => expect(screen.queryByRole("group", { name: "Edit CC" })).toBeNull());
    expect(screen.getByText("reception@acmedental.example")).toBeInTheDocument();
  });

  it("Done with a typed outside address shows the question, stays open and focuses it", async () => {
    setup(checked({}));
    const group = openEditor();
    fireEvent.change(addBox(), { target: { value: "pat@outside.example" } });
    fireEvent.click(within(group).getByRole("button", { name: "Done" }));
    const question = await within(group).findByText(
      "pat@outside.example is not from Acme Dental. Add anyway?",
    );
    await waitFor(() => expect(question).toHaveFocus());
    expect(screen.getByRole("group", { name: "Edit CC" })).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(within(group).getByRole("button", { name: "Add anyway" }));
    expect(
      within(group).getByRole("button", { name: "Remove pat@outside.example" }),
    ).toBeInTheDocument();
  });

  it("Done with invalid text shows the error inline and as a pop-up and stays open", async () => {
    setup();
    const group = openEditor();
    fireEvent.change(addBox(), { target: { value: "alex.tran@" } });
    fireEvent.click(within(group).getByRole("button", { name: "Done" }));
    const error = await within(group).findByText("alex.tran@ is not a valid email address.");
    await waitFor(() => expect(error).toHaveFocus());
    expect(within(popups()).getByRole("alert")).toHaveTextContent(
      "alex.tran@ is not a valid email address.",
    );
    expect(screen.getByRole("group", { name: "Edit CC" })).toBeInTheDocument();
  });

  it("the Add button (48 px, named Add to CC) adds the typed address", async () => {
    setup(checked({ "reception@acmedental.example": "allowed" }));
    const group = openEditor();
    expect(
      within(group).getByText("Press Enter or Add. Contacts of Acme Dental are suggested."),
    ).toBeInTheDocument();
    fireEvent.change(addBox(), { target: { value: "reception@acmedental.example" } });
    fireEvent.click(within(group).getByRole("button", { name: "Add to CC" }));
    expect(
      await within(group).findByRole("button", { name: "Remove reception@acmedental.example" }),
    ).toBeInTheDocument();
    expect(addBox()).toHaveValue("");
    expect(screen.getByRole("group", { name: "Edit CC" })).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it("a staff account is refused before sending, inline and as a pop-up", async () => {
    const cc = checked({ "alex.tran@helpdesk.example": "staff" });
    setup(cc);
    const group = openEditor();
    fireEvent.change(addBox(), { target: { value: "alex.tran@helpdesk.example" } });
    fireEvent.click(within(group).getByRole("button", { name: "Done" }));
    const message = "alex.tran@helpdesk.example is a staff account and can't be CC'd as a client.";
    expect(await within(group).findByText(message)).toBeInTheDocument();
    expect(within(popups()).getByRole("alert")).toHaveTextContent(message);
    expect(cc.checkAddress).toHaveBeenCalledWith("alex.tran@helpdesk.example");
    expect(within(group).queryByText(/Add anyway/)).toBeNull();
    expect(
      within(group).queryByRole("button", { name: "Remove alex.tran@helpdesk.example" }),
    ).toBeNull();
    expect(screen.getByRole("group", { name: "Edit CC" })).toBeInTheDocument();
  });

  it("a contact the agent may not use is refused with a plain message", async () => {
    setup(checked({ "someone@other.example": "not_allowed" }));
    const group = openEditor();
    fireEvent.change(addBox(), { target: { value: "someone@other.example" } });
    fireEvent.keyDown(addBox(), { key: "Enter" });
    expect(
      await within(group).findByText("You can't add someone@other.example to this ticket."),
    ).toBeInTheDocument();
  });

  it("a send-time CC refusal shows an alert naming the address and keeps the draft", async () => {
    const onSend = vi.fn<(send: ComposerSend) => Promise<void>>(() =>
      Promise.reject(
        new ApiError(
          400,
          "pat@outside.example cannot be copied on replies to this ticket.",
          "cc_not_allowed",
        ),
      ),
    );
    setup(config(), { onSend });
    const group = openEditor();
    fireEvent.click(within(group).getByRole("button", { name: "Remove Sam Okoye" }));
    fireEvent.click(within(group).getByRole("button", { name: "Done" }));
    typeReply();
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    const alert = await within(popups()).findByRole("alert");
    expect(alert).toHaveTextContent("pat@outside.example cannot be copied on replies");
    expect(screen.getAllByText(/The reply was not sent: pat@outside.example/)).toHaveLength(2);
    expect(screen.getByRole("textbox", { name: "Reply text" }).innerHTML).toContain("Hello");
    // The CC choice is kept too.
    expect(screen.getByText("office@acmedental.example")).toBeInTheDocument();
  });
});
