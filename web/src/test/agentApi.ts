import { jsonResponse, type ApiStub } from "./helpers";

// Synthetic agent API answers for the WP-05 screens. No real clients or people.

export const acme = {
  id: 1,
  name: "Acme Dental",
  prefix: "ACD",
  verifiedDomains: ["acmedental.example"],
  retiredPrefixes: [],
  timezone: "Asia/Ho_Chi_Minh",
  status: "ACTIVE",
};
export const beta = { ...acme, id: 2, name: "Beta Clinic", prefix: "BCL", verifiedDomains: [] };

export const summaryRow = {
  id: 42,
  reference: "ACD-042",
  organisationId: 1,
  organisationName: "Acme Dental",
  subject: "Outlook keeps asking for a password",
  status: "OPEN",
  priority: "P2",
  source: "EMAIL",
  assigneeUserId: 1,
  assigneeName: "Alex Tran",
  reporterName: "Maria Gomez",
  reporterEmail: "maria@acmedental.example",
  requestTypeName: "General",
  createdAt: "2026-11-18T02:00:00Z",
  updatedAt: "2026-11-18T03:00:00Z",
};

export const triageRow = {
  ...summaryRow,
  id: 7,
  reference: "TRI-007",
  organisationId: null,
  organisationName: null,
  subject: "Printer offline",
  status: "NEW",
  assigneeUserId: null,
  assigneeName: null,
  reporterName: null,
  reporterEmail: "someone@gmail.com",
};

export const detail = {
  id: 42,
  reference: "ACD-042",
  aliases: ["TRI-003"],
  organisationId: 1,
  organisationName: "Acme Dental",
  subject: "Outlook keeps asking for a password",
  status: "OPEN",
  statusLabel: "Open",
  allowedStatuses: ["PENDING_ON_CLIENT", "PENDING_ON_VENDOR", "RESOLVED"],
  priority: "P2",
  impact: "JUST_ME",
  urgency: "PARTLY",
  source: "EMAIL",
  requestType: { id: 1, name: "General", defaultPriority: "P3" },
  reporter: { userId: 30, name: "Maria Gomez", email: "maria@acmedental.example" },
  assignee: { userId: 1, name: "Alex Tran", email: null },
  duplicateOf: null,
  followUpOf: null,
  links: [{ ticketId: 40, reference: "ACD-040", kind: "RELATED" }],
  participants: [
    { userId: 31, email: "office@acmedental.example", confirmed: true },
    { userId: null, email: "it@vendor.example", confirmed: false },
  ],
  publicComments: [
    {
      id: 1,
      authorKind: "CLIENT",
      author: { userId: 30, name: "Maria Gomez", email: "maria@acmedental.example" },
      bodyHtml: '<p>It asks every hour.</p><img src="https://tracker.example/x.gif">',
      receivedByEmail: true,
      createdAt: "2026-11-18T02:00:00Z",
      attachments: [
        {
          id: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
          filename: "error.png",
          size: 2048,
          contentType: "image/png",
          image: true,
          inline: false,
          internal: false,
        },
      ],
    },
    {
      id: 2,
      authorKind: "SUPPORTER",
      author: { userId: 1, name: "Alex Tran", email: null },
      bodyHtml: "<p>Please try signing out and in.</p>",
      receivedByEmail: false,
      createdAt: "2026-11-18T02:30:00Z",
      attachments: [],
    },
  ],
  internalNotes: [
    {
      id: 5,
      authorKind: "SUPPORTER",
      author: { userId: 1, name: "Alex Tran", email: null },
      bodyHtml: "<p>MFA token expired on the tenant.</p>",
      receivedByEmail: false,
      createdAt: "2026-11-18T02:20:00Z",
      attachments: [],
    },
  ],
  systemEntries: [],
  assignmentEntries: [
    {
      id: 21,
      fromUserId: null,
      toUserId: 1,
      actorUserId: null,
      reason: "DEFAULT_ORGANISATION",
      text: "Assigned to Alex Tran automatically (default for Acme Dental)",
      createdAt: "2026-11-18T02:10:00.123456Z",
    },
    {
      id: 22,
      fromUserId: 1,
      toUserId: 3,
      actorUserId: 1,
      reason: null,
      text: "Reassigned from Alex Tran to Sam Lee by Alex Tran",
      createdAt: "2026-11-18T02:32:00Z",
    },
  ],
  timelineEntries: [
    {
      id: 20,
      type: "CREATED",
      actorKind: "CLIENT",
      actorUserId: null,
      text: "Ticket created from an email",
      createdAt: "2026-11-18T02:00:00Z",
    },
    {
      id: 21,
      type: "ASSIGNEE",
      actorKind: "SYSTEM",
      actorUserId: null,
      text: "Assigned to Alex Tran automatically (default for Acme Dental)",
      createdAt: "2026-11-18T02:10:00.123456Z",
    },
    {
      id: 22,
      type: "ASSIGNEE",
      actorKind: "AGENT",
      actorUserId: 1,
      text: "Reassigned from Alex Tran to Sam Lee by Alex Tran",
      createdAt: "2026-11-18T02:32:00Z",
    },
    {
      id: 23,
      type: "PRIORITY",
      actorKind: "AGENT",
      actorUserId: 3,
      text: "Priority changed from P3 Medium to P2 High by Sam Lee",
      createdAt: "2026-11-18T02:40:00Z",
    },
  ],
  createdAt: "2026-11-18T02:00:00Z",
  updatedAt: "2026-11-18T03:00:00Z",
};

export const triageDetail = {
  ...detail,
  id: 7,
  reference: "TRI-007",
  aliases: [],
  organisationId: null,
  organisationName: null,
  subject: "Printer offline",
  status: "NEW",
  allowedStatuses: ["OPEN", "PENDING_ON_CLIENT", "PENDING_ON_VENDOR", "RESOLVED"],
  reporter: { userId: null, name: null, email: "someone@gmail.com" },
  assignee: null,
  links: [],
  participants: [],
  internalNotes: [],
};

export type Call = { path: string; method: string; body: unknown };

/**
 * Agent API stub: default answers for every WP-05 read, plus per-test handlers keyed by
 * "METHOD /path" (path without the query string) or "METHOD /path?query" (exact). Records calls.
 */
export function agentApi(
  handlers: Record<string, (body: unknown, path: string) => Response | undefined> = {},
) {
  const calls: Call[] = [];
  const api: ApiStub = (path, init) => {
    const method = init?.method ?? "GET";
    let body: unknown;
    if (typeof init?.body === "string") body = JSON.parse(init.body) as unknown;
    else if (init?.body instanceof FormData) body = init.body;
    calls.push({ path, method, body });
    const bare = path.split("?")[0] ?? path;
    const handler = handlers[`${method} ${path}`] ?? handlers[`${method} ${bare}`];
    if (handler) {
      const answer = handler(body, path);
      if (answer) return answer;
    }
    if (method !== "GET") return undefined;
    switch (bare) {
      case "/api/v1/agent/tickets":
        return new Response(JSON.stringify([summaryRow]), {
          status: 200,
          headers: { "Content-Type": "application/json", "X-Total-Count": "1" },
        });
      case "/api/v1/agent/organisations":
        return jsonResponse([acme, beta]);
      case "/api/v1/agent/supporters":
        return jsonResponse([
          { id: 1, name: "Alex Tran", email: "alex@examplemsp.example", outOfOffice: false },
          {
            id: 2,
            name: "Minh Le",
            email: "minh@examplemsp.example",
            outOfOffice: true,
            outOfOfficeUntil: "2026-11-30",
          },
        ]);
      case "/api/v1/agent/request-types":
        return jsonResponse([
          { id: 1, name: "General", defaultPriority: "P3" },
          { id: 2, name: "Alert", defaultPriority: "P2" },
        ]);
      case "/api/v1/agent/saved-replies":
        return jsonResponse([
          { id: 9, title: "Password reset steps", bodyHtml: "<p>x</p>", shared: true },
        ]);
      case "/api/v1/agent/me/preferences":
        return jsonResponse({ error: "not_found", message: "We could not find that." }, 404);
      case "/api/v1/agent/tickets/by-reference/ACD-042":
        return jsonResponse({
          ticketId: 42,
          reference: "ACD-042",
          matched: "ACD-042",
          alias: false,
        });
      case "/api/v1/agent/tickets/by-reference/TRI-007":
        return jsonResponse({
          ticketId: 7,
          reference: "TRI-007",
          matched: "TRI-007",
          alias: false,
        });
      case "/api/v1/agent/tickets/42":
        return jsonResponse(detail);
      case "/api/v1/agent/tickets/7":
        return jsonResponse(triageDetail);
      case "/api/v1/agent/contacts":
        return jsonResponse({
          items: [
            {
              id: 30,
              name: "Maria Gomez",
              email: "maria@acmedental.example",
              organisationId: 1,
              organisationName: "Acme Dental",
              status: "ACTIVE",
            },
            {
              id: 32,
              name: "Sam Ho",
              email: "sam@acmedental.example",
              organisationId: 1,
              organisationName: "Acme Dental",
              status: "INVITED",
            },
          ],
          total: 2,
        });
      default:
        if (bare.startsWith("/api/v1/agent/tickets/by-reference/")) {
          return jsonResponse({ error: "not_found", message: "We could not find that." }, 404);
        }
        return undefined;
    }
  };
  return { api, calls };
}
