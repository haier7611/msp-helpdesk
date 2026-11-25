import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { axeViolations } from "../test/helpers";
import { Composer, toStoredHtml, type ComposerProps } from "./Composer";
import { sanitiseHtml } from "./SafeHtml";

const UPLOAD_ID = "11111111-2222-4333-8444-555555555555";

function setup(overrides: Partial<ComposerProps> = {}) {
  const onSend = vi.fn(() => Promise.resolve());
  const props: ComposerProps = {
    draftKey: "test-draft",
    to: "Dr Maria Gomez <maria@acmedental.example>",
    cc: "office@acmedental.example",
    onSend,
    savedReplies: [
      { id: 1, title: "Password reset steps", bodyHtml: "", shared: true },
      { id: 2, title: "Remote session", bodyHtml: "", shared: false },
    ],
    renderSavedReply: vi.fn((id: number) =>
      Promise.resolve(id === 1 ? "<p>Hi Maria, about ACD-042</p>" : "<p>Remote</p>"),
    ),
    upload: vi.fn((file: File) =>
      Promise.resolve({
        id: UPLOAD_ID,
        filename: file.name,
        size: file.size,
        contentType: file.type,
        image: file.type.startsWith("image/"),
      }),
    ),
    ...overrides,
  };
  const view = render(<Composer {...props} />);
  return { ...view, props, onSend: props.onSend as ReturnType<typeof vi.fn> };
}

/** Types into the contentEditable editor and puts the caret at the end, as a browser would. */
function typeInto(editor: HTMLElement, html: string) {
  editor.innerHTML = html;
  let last: Node = editor;
  while (last.lastChild) last = last.lastChild;
  const range = document.createRange();
  if (last.nodeType === Node.TEXT_NODE) range.setStart(last, (last as Text).length);
  else range.setStart(editor, editor.childNodes.length);
  range.collapse(true);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
  fireEvent.input(editor);
}

afterEach(() => {
  sessionStorage.clear();
});

describe("Composer", () => {
  it("opens on Internal note by default, with the yellow INTERNAL banner and Add note", () => {
    setup();
    expect(screen.getByRole("tab", { name: "Internal note" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByText("INTERNAL")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add note" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send to client" })).not.toBeInTheDocument();
    expect(screen.queryByText(/maria@acmedental.example/)).not.toBeInTheDocument();
  });

  it("opens on the supporter's default tab, Reply to client with visible To and CC", () => {
    setup({ defaultTab: "reply" });
    expect(screen.getByRole("tab", { name: "Reply to client" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByText(/maria@acmedental.example/)).toBeInTheDocument();
    expect(screen.getByText("office@acmedental.example")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send to client" })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Send and set Pending on client" }),
    ).toBeInTheDocument();
  });

  it("switches tabs with the keyboard (arrows, Home, End) and moves focus", () => {
    setup();
    const note = screen.getByRole("tab", { name: "Internal note" });
    fireEvent.keyDown(note, { key: "ArrowLeft" });
    const reply = screen.getByRole("tab", { name: "Reply to client" });
    expect(reply).toHaveAttribute("aria-selected", "true");
    expect(reply).toHaveFocus();
    fireEvent.keyDown(reply, { key: "End" });
    expect(screen.getByRole("tab", { name: "Internal note" })).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("tab", { name: "Internal note" }), { key: "Home" });
    expect(screen.getByRole("tab", { name: "Reply to client" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.keyDown(screen.getByRole("tab", { name: "Reply to client" }), { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: "Internal note" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("adds a note and sends a reply with and without Pending on client, each as its own kind", async () => {
    const { onSend } = setup();
    typeInto(screen.getByRole("textbox", { name: "Internal note text" }), "<p>Checked logs</p>");
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() =>
      expect(onSend).toHaveBeenLastCalledWith({
        kind: "note",
        html: "<p>Checked logs</p>",
        attachmentIds: [],
      }),
    );

    fireEvent.click(screen.getByRole("tab", { name: "Reply to client" }));
    typeInto(screen.getByRole("textbox", { name: "Reply text" }), "<p>Fixed now</p>");
    fireEvent.click(screen.getByRole("button", { name: "Send and set Pending on client" }));
    await waitFor(() =>
      expect(onSend).toHaveBeenLastCalledWith({
        kind: "reply",
        html: "<p>Fixed now</p>",
        attachmentIds: [],
        thenPendingOnClient: true,
      }),
    );
    // Sent: the editor is empty again.
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Reply text" }).textContent).toBe(""),
    );
    typeInto(screen.getByRole("textbox", { name: "Reply text" }), "<p>Again</p>");
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    await waitFor(() =>
      expect(onSend).toHaveBeenLastCalledWith({
        kind: "reply",
        html: "<p>Again</p>",
        attachmentIds: [],
      }),
    );
  });

  it("refuses to send an empty note", async () => {
    const { onSend } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Write a note first.");
    expect(onSend).not.toHaveBeenCalled();
    // The message goes as soon as the agent starts typing (L-QA-012).
    typeInto(screen.getByRole("textbox", { name: "Internal note text" }), "<p>Checked.</p>");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it('typing "/" opens saved replies; arrows and Enter insert the filled-in reply', async () => {
    const { props } = setup({ defaultTab: "reply" });
    const editor = screen.getByRole("textbox", { name: "Reply text" });
    typeInto(editor, "Hello /");
    const list = await screen.findByRole("listbox", { name: "Saved replies" });
    expect(within(list).getAllByRole("option")).toHaveLength(2);
    expect(within(list).getByText("Shared")).toBeInTheDocument();

    typeInto(editor, "Hello /rem");
    expect(within(screen.getByRole("listbox")).getAllByRole("option")).toHaveLength(1);
    typeInto(editor, "Hello /");
    fireEvent.keyDown(editor, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: /Remote session/ })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.keyDown(editor, { key: "ArrowUp" });
    fireEvent.keyDown(editor, { key: "Enter" });

    await waitFor(() => expect(editor.innerHTML).toContain("Hi Maria, about ACD-042"));
    expect(props.renderSavedReply).toHaveBeenCalledWith(1);
    expect(editor.textContent).not.toContain("/");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("Escape closes the saved-reply list; no match says so", async () => {
    setup({ defaultTab: "reply" });
    const editor = screen.getByRole("textbox", { name: "Reply text" });
    typeInto(editor, "/zzz");
    expect(await screen.findByText('No saved reply matches "zzz".')).toBeInTheDocument();
    fireEvent.keyDown(editor, { key: "Escape" });
    expect(screen.queryByText(/No saved reply matches/)).not.toBeInTheDocument();
  });

  it("a send that fails shows the error and keeps the draft", async () => {
    const onSend = vi.fn(() =>
      Promise.reject(new ApiError(409, "The mail queue is down.", "queue_down")),
    );
    setup({ defaultTab: "reply", onSend });
    const editor = screen.getByRole("textbox", { name: "Reply text" });
    typeInto(editor, "<p>Important answer</p>");
    fireEvent.click(screen.getByRole("button", { name: "Send to client" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The reply was not sent: The mail queue is down. Your text is still here.",
    );
    expect(screen.getByRole("textbox", { name: "Reply text" }).innerHTML).toBe(
      "<p>Important answer</p>",
    );
  });

  it("a note refused by the per-agent cap (429) shows the wait and keeps the draft", async () => {
    const onSend = vi.fn(() =>
      Promise.reject(
        new ApiError(
          429,
          "Too many attempts. Please wait 12 seconds and try again.",
          "too_many_requests",
        ),
      ),
    );
    setup({ onSend });
    const editor = screen.getByRole("textbox", { name: "Internal note text" });
    typeInto(editor, "<p>Checked the logs</p>");
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The note was not saved: Too many attempts. Please wait 12 seconds and try again. Your text is still here.",
    );
    expect(screen.getByRole("textbox", { name: "Internal note text" }).innerHTML).toBe(
      "<p>Checked the logs</p>",
    );
  });

  it("keeps the draft in sessionStorage, so it comes back after a sign-in round trip", () => {
    const first = setup();
    typeInto(screen.getByRole("textbox", { name: "Internal note text" }), "<p>Half a note</p>");
    first.unmount();
    setup();
    expect(screen.getByRole("textbox", { name: "Internal note text" }).innerHTML).toBe(
      "<p>Half a note</p>",
    );
  });

  it("refuses a file over 20 MB with a message and attaches a smaller one", async () => {
    const { props, onSend } = setup();
    const big = new File(["x"], "huge.zip", { type: "application/zip" });
    Object.defineProperty(big, "size", { value: 21 * 1024 * 1024 });
    fireEvent.change(screen.getByTestId("file-input"), { target: { files: [big] } });
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "huge.zip is larger than 20 MB, so it cannot be attached.",
    );
    expect(props.upload).not.toHaveBeenCalled();

    const small = new File(["hello"], "log.txt", { type: "text/plain" });
    fireEvent.change(screen.getByTestId("file-input"), { target: { files: [small] } });
    expect(await screen.findByText(/log.txt/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() =>
      expect(onSend).toHaveBeenLastCalledWith({
        kind: "note",
        html: "",
        attachmentIds: [UPLOAD_ID],
      }),
    );
  });

  it("a pasted image is uploaded, shown inline and sent as attachment:<id>", async () => {
    const { onSend } = setup();
    const editor = screen.getByRole("textbox", { name: "Internal note text" });
    const image = new File(["png"], "screen.png", { type: "image/png" });
    await act(async () => {
      fireEvent.paste(editor, { clipboardData: { files: [image] } });
    });
    await waitFor(() =>
      expect(editor.innerHTML).toContain(`/api/v1/agent/attachments/${UPLOAD_ID}`),
    );
    fireEvent.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(onSend).toHaveBeenCalled());
    const sent = onSend.mock.calls[0]?.[0] as { html: string; attachmentIds: string[] };
    expect(sent.html).toContain(`src="attachment:${UPLOAD_ID}"`);
    expect(sent.attachmentIds).toEqual([UPLOAD_ID]);
  });

  it("explains why a closed ticket has no Reply to client", () => {
    setup({ defaultTab: "reply", replyDisabledReason: "This ticket is closed." });
    expect(screen.getByText("This ticket is closed.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send to client" })).not.toBeInTheDocument();
  });

  it("has no axe violations on either tab", async () => {
    setup({ defaultTab: "reply" });
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("tab", { name: "Internal note" }));
    expect(await axeViolations()).toEqual([]);
  });
});

describe("rich text safety", () => {
  it("toStoredHtml turns upload URLs into attachment: references", () => {
    expect(toStoredHtml(`<img src="/api/v1/agent/attachments/${UPLOAD_ID}" alt="a">`)).toEqual({
      html: `<img src="attachment:${UPLOAD_ID}" alt="a">`,
      inlineIds: [UPLOAD_ID],
    });
  });

  it("DOMPurify strips scripts, handlers, javascript: links and remote images", () => {
    const out = sanitiseHtml(
      `<p onclick="x()">Hi<script>alert(1)</script></p><a href="javascript:alert(1)">bad</a>` +
        `<a href="https://example.test/">ok</a><img src="https://tracker.example/p.gif">` +
        `<img src="data:image/png;base64,AAAA"><img src="attachment:${UPLOAD_ID}" alt="shot">` +
        `<style>p{}</style><iframe src="https://x.example"></iframe><svg><circle/></svg>`,
    );
    expect(out).not.toMatch(/script|onclick|javascript:|tracker|data:|style|iframe|svg/);
    expect(out).toContain('href="https://example.test/"');
    expect(out).toContain('rel="noopener noreferrer nofollow"');
    expect(out).toContain(`src="/api/v1/agent/attachments/${UPLOAD_ID}"`);
  });
});
