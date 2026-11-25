import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { signOut } from "./api/auth";
import { Composer } from "./components/Composer";
import { clearAllDrafts, clearDraftsOfOtherUsers, draftKeyFor } from "./draftStorage";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

function composer(draftKey: string | null) {
  return render(<Composer draftKey={draftKey} to="Someone" onSend={() => Promise.resolve()} />);
}

function seed(userId: number, text: string) {
  sessionStorage.setItem(
    `${draftKeyFor(userId, 42)}:note`,
    JSON.stringify({ html: `<p>${text}</p>`, files: [] }),
  );
}

describe("composer drafts", () => {
  it("keys drafts by user id so user B never sees user A's text in the same tab", () => {
    seed(1, "Secret of A");
    const a = composer(draftKeyFor(1, 42));
    expect(screen.getByRole("textbox", { name: "Internal note text" }).innerHTML).toBe(
      "<p>Secret of A</p>",
    );
    a.unmount();
    composer(draftKeyFor(2, 42));
    expect(screen.getByRole("textbox", { name: "Internal note text" }).innerHTML).toBe("");
  });

  it("clears the drafts of other users but keeps the signed-in user's", () => {
    seed(1, "A");
    seed(2, "B");
    clearDraftsOfOtherUsers(2);
    expect(sessionStorage.getItem(`${draftKeyFor(1, 42)}:note`)).toBeNull();
    expect(sessionStorage.getItem(`${draftKeyFor(2, 42)}:note`)).not.toBeNull();
  });

  it("clears every draft on sign-out, and leaves other storage alone", async () => {
    seed(1, "A");
    seed(2, "B");
    sessionStorage.setItem("unrelated", "keep");
    document.cookie = "XSRF-TOKEN=t; path=/";
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))),
    );
    await signOut();
    expect(sessionStorage.getItem(`${draftKeyFor(1, 42)}:note`)).toBeNull();
    expect(sessionStorage.getItem(`${draftKeyFor(2, 42)}:note`)).toBeNull();
    expect(sessionStorage.getItem("unrelated")).toBe("keep");
  });

  it("works when storage is blocked: the composer still renders and accepts typing", () => {
    const blocked = () => {
      throw new DOMException("blocked", "SecurityError");
    };
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(blocked);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(blocked);
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(blocked);
    vi.spyOn(Storage.prototype, "key").mockImplementation(blocked);
    expect(() => clearAllDrafts()).not.toThrow();
    expect(() => clearDraftsOfOtherUsers(1)).not.toThrow();
    composer(draftKeyFor(1, 42));
    const editor = screen.getByRole("textbox", { name: "Internal note text" });
    editor.innerHTML = "<p>Typing</p>";
    fireEvent.input(editor);
    expect(screen.getByRole("button", { name: "Add note" })).toBeInTheDocument();
  });
});
