// Fix 2026-11-20: attachment tiles rendered outside their comment card (the WP-05a placeholder
// box with a fixed size wrapped the real list) and must stay inside it, in a wrapping grid.
import { render, screen, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { Attachment } from "../api/agent";
import { axeViolations } from "../test/helpers";
import { AttachmentList, InternalNoteCard, MessageCard } from "./Conversation";

const css = readFileSync("src/components/Conversation.module.css", "utf8");

/** The body of one CSS rule, e.g. rule(".fileName"). */
function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = new RegExp(`(^|\\n)${escaped}\\s*\\{([^}]*)\\}`).exec(css);
  if (!match) throw new Error(`no CSS rule ${selector}`);
  return match[2] ?? "";
}

function file(id: string, filename: string, image = false): Attachment {
  return {
    id,
    filename,
    size: 2048,
    contentType: image ? "image/png" : "application/octet-stream",
    image,
    inline: false,
    internal: false,
  };
}

// Synthetic names only, shaped like a real bug report.
const FILES: Attachment[] = [
  file("a1", "Übersicht Drucker März.xls"),
  file("a2", "Bảng kê mẫu.ods"),
  file("a3", "Helpdesk QA results - 19 Nov 2026.xlsx"),
  file("a4", "screenshot.png", true),
  file("a5", "a-very-long-file-name-without-any-spaces-at-all-that-must-wrap-not-overflow.pdf"),
];

describe("attachments in comment cards", () => {
  it("a public reply keeps every tile inside its card, images as thumbnails", async () => {
    render(
      <main>
        <MessageCard
          kind="reply"
          author="Alex Tran"
          meta="Reply to client"
          attachment={<AttachmentList files={FILES} />}
        >
          <p>See the files</p>
        </MessageCard>
        <p>Status changed to Pending</p>
      </main>,
    );
    const card = screen.getByRole("article");
    const list = within(card).getByRole("list", { name: "Attachments" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(5);
    // The list sits in the card, after the body, and the timeline line comes after the card.
    expect(card.contains(list)).toBe(true);
    expect(
      card.querySelector("p")!.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(card.contains(screen.getByText("Status changed to Pending"))).toBe(false);
    // Unicode names shown as they are, with spaces (no %20).
    expect(within(list).getByRole("link", { name: "Übersicht Drucker März.xls" })).toHaveAttribute(
      "download",
      "Übersicht Drucker März.xls",
    );
    expect(list.textContent).not.toContain("%20");
    // Images are thumbnails; files get a tile with the name.
    const img = within(list).getByRole("img", { name: "Preview of screenshot.png" });
    expect(img).toHaveAttribute("src", "/api/v1/agent/attachments/a4/thumbnail");
    expect(within(items[2]!).queryByRole("img")).toBeNull();
    expect(await axeViolations()).toEqual([]);
  });

  it("an internal note keeps its tiles inside the note card", async () => {
    render(
      <main>
        <InternalNoteCard
          author="Minh Le"
          meta="Internal note"
          attachment={<AttachmentList files={FILES} />}
        >
          <p>Checked</p>
        </InternalNoteCard>
      </main>,
    );
    const note = screen.getByRole("article", { name: "Internal note by Minh Le" });
    expect(within(note).getAllByRole("listitem")).toHaveLength(5);
    expect(await axeViolations()).toEqual([]);
  });

  it("the styles wrap tiles in a grid with no fixed box, no absolute position, wrapped names", () => {
    expect(css).not.toMatch(/position:\s*absolute/);
    // The old fixed-size placeholder box is gone.
    expect(css).not.toMatch(/(^|\n)\.attachment\s*\{/);
    expect(css).not.toMatch(/\.noteAttachment/);
    const container = rule(".attachments");
    expect(container).not.toMatch(/(^|\s)height:/);
    expect(container).not.toMatch(/(^|\s)width:/);
    expect(container).not.toMatch(/overflow/);
    const card = rule(".card");
    expect(card).not.toMatch(/(^|\s)(height|max-height):/);
    const grid = rule(".files");
    expect(grid).toMatch(/display:\s*grid/);
    // Columns shrink to the card at 390 px: never wider than 100% of it.
    expect(grid).toMatch(/minmax\(min\(100%,\s*\d+px\),\s*1fr\)/);
    expect(rule(".fileItem")).toMatch(/min-width:\s*0/);
    expect(rule(".fileLink")).toMatch(/min-width:\s*0/);
    expect(rule(".fileName")).toMatch(/overflow-wrap:\s*anywhere/);
    expect(rule(".thumb")).toMatch(/width:\s*100%/);
  });
});
