// Display labels the badges and the agent API share. The demo data that used to live next to
// these types is gone: every page now reads the real API.

export type Priority = "P1" | "P2" | "P3" | "P4";

export type TicketStatus =
  "New" | "Open" | "Pending on client" | "Pending on vendor" | "Resolved" | "Closed";

export type SlaState = "breached" | "risk" | "ok" | "paused";

export type SupporterStatus = "Active" | "Invited" | "Deactivated";
