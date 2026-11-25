import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PNG, RENDERED } from "../test/signatureFixture";
import { buildSignatureFragment } from "./signatureDom";
import { SignaturePreview } from "./SignaturePreview";

function build(html: string): HTMLDivElement {
  const div = document.createElement("div");
  div.appendChild(buildSignatureFragment(html));
  return div;
}

describe("buildSignatureFragment", () => {
  it("keeps the email layout: tables, inline styles via CSSOM, mailto links and the PNG logo", () => {
    const div = build(RENDERED.fullHtml);
    const name = div.querySelector("td");
    expect(name?.textContent).toBe("Alex Tran");
    expect(name?.style.color).toBe("rgb(43, 87, 151)");
    expect(name?.style.fontSize).toBe("20px");
    expect(div.querySelector("a")?.getAttribute("href")).toBe("mailto:alex.tran@helpdesk.example");
    const img = div.querySelector("img");
    expect(img?.getAttribute("src")).toBe(PNG);
    expect(img?.getAttribute("alt")).toBe("Example MSP logo");
    expect(div.querySelector("div")?.style.borderTop).toContain("dashed");
  });

  it("drops scripts, handlers, foreign links and images, and dangerous styles", () => {
    const div = build(
      '<td onclick="alert(1)" style="color:red;background-image:url(https://x.example/a.png)">ok</td>' +
        "<script>alert(1)</script>" +
        '<a href="javascript:alert(1)">j</a>' +
        '<a href="https://evil.example">h</a>' +
        '<img src="https://evil.example/track.png" onerror="alert(1)">' +
        '<svg><circle onload="alert(1)"/></svg>' +
        '<span style="width:expression(alert(1))">e</span>' +
        '<iframe src="https://evil.example"></iframe>',
    );
    expect(div.innerHTML).not.toMatch(/script|onclick|onerror|onload|javascript|evil|svg|iframe/i);
    expect(div.innerHTML).not.toMatch(/url\(|expression/i);
    expect(div.querySelectorAll("a[href]")).toHaveLength(0);
    expect(div.querySelector("img")?.hasAttribute("src")).toBe(false);
  });

  it("shows escaped text as text, never as markup", () => {
    const div = build("<td>&lt;b&gt;x&lt;/b&gt; {name}</td>");
    expect(div.textContent).toBe("<b>x</b> {name}");
    expect(div.querySelector("b")).toBeNull();
  });
});

describe("SignaturePreview", () => {
  it("shows the one full signature, then plain text on toggle (ADR-015, no short one)", () => {
    render(<SignaturePreview rendered={RENDERED} />);
    expect(screen.getByRole("button", { name: "HTML" })).toHaveAttribute("aria-pressed", "true");
    const frame = screen.getByTestId("signature-frame");
    expect(frame).toHaveTextContent("Signature (on every reply)");
    expect(frame).not.toHaveTextContent(/short signature/i);
    expect(frame).not.toHaveTextContent(/later replies/i);
    expect(screen.getAllByRole("group", { name: "Signature" })).toHaveLength(1);
    expect(screen.getByRole("group", { name: "Signature" })).toHaveTextContent(
      "alex.tran@helpdesk.example",
    );
    expect(screen.getByRole("img", { name: "Example MSP logo" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Plain text" }));
    const pres = frame.querySelectorAll("pre");
    expect(pres).toHaveLength(1);
    expect(pres[0]).toHaveTextContent("E: alex.tran@helpdesk.example");
  });

  it("says it is loading before the first rendering arrives", () => {
    render(<SignaturePreview rendered={null} />);
    expect(screen.getByText("Loading the preview...")).toBeInTheDocument();
  });
});
