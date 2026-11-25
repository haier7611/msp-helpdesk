import type { RenderedSignature } from "../api/settings";

export const PNG = "data:image/png;base64,iVBORw0KGgo=";

// The shape the server's SignatureRenderer produces (synthetic values only).
export const RENDERED: RenderedSignature = {
  fullHtml:
    '<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse;">' +
    '<tr><td style="font-family:Arial,Helvetica,sans-serif;font-size:20px;font-weight:bold;color:#2B5797;">Alex Tran</td></tr>' +
    '<tr><td style="font-size:14px;"><strong style="color:#2B5797;">E:</strong> <a href="mailto:alex.tran@helpdesk.example" style="color:#000000;">alex.tran@helpdesk.example</a></td></tr>' +
    '<tr><td style="padding:10px 0;"><div style="border-top:1px dashed #A6A6A6;">&nbsp;</div></td></tr>' +
    `<tr><td><img src="${PNG}" alt="Example MSP logo" width="150" height="40" style="display:block;border:0;"></td></tr>` +
    "</table>",
  fullText: "Alex Tran\nE: alex.tran@helpdesk.example\n--\nExample MSP",
};
