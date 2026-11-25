// Navigation data shared by the layouts, the router and the route smoke tests.

export type SectionLink = { to: string; label: string };

/**
 * Screens that exist in code but stay hidden until their backend merges. API keys is
 * still in review: its nav entry and route appear when this is switched on.
 */
export const FEATURES = { apiKeys: false } as const;

export const ADMIN_SECTIONS: SectionLink[] = [
  { to: "/admin/supporters", label: "Agents" },
  { to: "/admin/organisations", label: "Organisations" },
  { to: "/admin/customers", label: "Customers" },
  { to: "/admin/request-types", label: "Request types" },
  { to: "/admin/sla", label: "SLA policies" },
  { to: "/admin/sla-automation", label: "SLA automation" },
  { to: "/admin/saved-replies", label: "Saved replies" },
  { to: "/admin/email", label: "Email and signature" },
  { to: "/admin/sign-in", label: "Sign-in" },
  { to: "/admin/block-list", label: "Block list" },
  { to: "/admin/branding", label: "Branding" },
  ...(FEATURES.apiKeys ? [{ to: "/admin/api-keys", label: "API keys" }] : []),
  { to: "/admin/audit-log", label: "Audit log" },
];

export const SETTINGS_SECTIONS: SectionLink[] = [
  { to: "/agent/settings/profile", label: "Profile and signature" },
  { to: "/agent/settings/security", label: "Security" },
  { to: "/agent/settings/notifications", label: "Notifications" },
  { to: "/agent/settings/preferences", label: "Preferences" },
  { to: "/agent/settings/out-of-office", label: "Out of office" },
  { to: "/agent/settings/saved-replies", label: "My saved replies" },
];

/** `item` is for developers only (which item builds the screen); it is never shown. */
export type Placeholder = { path: string; title: string; item: string };

// Screens a later item builds; each route already has its layout and navigation.
export const AGENT_PLACEHOLDERS: Placeholder[] = [];

export const SETTINGS_PLACEHOLDERS: Placeholder[] = [
  { path: "saved-replies", title: "My saved replies", item: "WP-05" },
];

export const ADMIN_PLACEHOLDERS: Placeholder[] = [
  ...(FEATURES.apiKeys ? [{ path: "api-keys", title: "API keys", item: "WP-08" }] : []),
];

// The portal has only two screens (New ticket, My account).
export const PORTAL_PLACEHOLDERS: Placeholder[] = [];
