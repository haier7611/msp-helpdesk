import { apiFetch } from "./client";
import { PRIORITIES, type Priority } from "./admin";

/**
 * Admin - SLA automation (/api/v1/admin/sla-automation). Admin-only on the server; no
 * step-up. A rule = a trigger, optional conditions (AND-ed, empty = any) and one or more actions.
 */

export type SlaTrigger =
  | "FIRST_RESPONSE_AT_RISK"
  | "FIRST_RESPONSE_BREACHED"
  | "FIRST_RESPONSE_MET"
  | "RESOLUTION_AT_RISK"
  | "RESOLUTION_BREACHED"
  | "RESOLUTION_MET";

export const TRIGGERS: readonly SlaTrigger[] = [
  "FIRST_RESPONSE_AT_RISK",
  "FIRST_RESPONSE_BREACHED",
  "FIRST_RESPONSE_MET",
  "RESOLUTION_AT_RISK",
  "RESOLUTION_BREACHED",
  "RESOLUTION_MET",
];

export const TRIGGER_LABELS: Record<SlaTrigger, string> = {
  FIRST_RESPONSE_AT_RISK: "First response at risk (80%)",
  FIRST_RESPONSE_BREACHED: "First response breached",
  FIRST_RESPONSE_MET: "First response met",
  RESOLUTION_AT_RISK: "Resolution at risk (80%)",
  RESOLUTION_BREACHED: "Resolution breached",
  RESOLUTION_MET: "Resolution met",
};

export type ActionType =
  "NOTIFY_AGENTS" | "NOTIFY_ORGANISATION" | "ASSIGN" | "RAISE_PRIORITY" | "ADD_NOTE";

export const ACTION_TYPES: readonly ActionType[] = [
  "NOTIFY_AGENTS",
  "NOTIFY_ORGANISATION",
  "ASSIGN",
  "RAISE_PRIORITY",
  "ADD_NOTE",
];

export const ACTION_LABELS: Record<ActionType, string> = {
  NOTIFY_AGENTS: "Notify chosen agents",
  NOTIFY_ORGANISATION: "Notify every agent of the organisation",
  ASSIGN: "Assign to an agent (only if unassigned)",
  RAISE_PRIORITY: "Raise the priority one level",
  ADD_NOTE: "Add an internal note",
};

export type RuleConditions = {
  includeTriage: boolean;
  organisationIds: number[];
  priorities: Priority[];
  requestTypeIds: number[];
};

export type RuleAction = {
  type: ActionType;
  agentIds: number[];
  agentId: number | null;
  text: string | null;
};

export type SlaRule = {
  id: number;
  name: string;
  enabled: boolean;
  position: number;
  trigger: SlaTrigger;
  conditions: RuleConditions;
  actions: RuleAction[];
};

export type RuleInput = {
  name: string;
  enabled: boolean;
  trigger: SlaTrigger;
  conditions: RuleConditions;
  actions: RuleAction[];
};

type Obj = Record<string, unknown>;

function bad(): never {
  throw new Error("API answered with an unexpected shape");
}

function obj(value: unknown): Obj {
  if (typeof value === "object" && value !== null && !Array.isArray(value)) return value as Obj;
  return bad();
}

function arr(value: unknown): unknown[] {
  return Array.isArray(value) ? value : bad();
}

function ids(value: unknown): number[] {
  return Array.isArray(value) ? value.filter((v): v is number => typeof v === "number") : [];
}

function parseAction(value: unknown): RuleAction {
  const v = obj(value);
  const type = ACTION_TYPES.find((t) => t === v.type) ?? bad();
  return {
    type,
    agentIds: ids(v.agentIds),
    agentId: typeof v.agentId === "number" ? v.agentId : null,
    text: typeof v.text === "string" ? v.text : null,
  };
}

export function parseRule(value: unknown): SlaRule {
  const v = obj(value);
  const c = obj(v.conditions ?? {});
  if (typeof v.id !== "number" || typeof v.name !== "string") bad();
  return {
    id: v.id,
    name: v.name,
    enabled: v.enabled === true,
    position: typeof v.position === "number" ? v.position : 0,
    trigger: TRIGGERS.find((t) => t === v.trigger) ?? bad(),
    conditions: {
      includeTriage: c.includeTriage === true,
      organisationIds: ids(c.organisationIds),
      priorities: Array.isArray(c.priorities)
        ? PRIORITIES.filter((p) => (c.priorities as unknown[]).includes(p))
        : [],
      requestTypeIds: ids(c.requestTypeIds),
    },
    actions: arr(v.actions).map(parseAction),
  };
}

const BASE = "/api/v1/admin/sla-automation";
const ignore = () => undefined;
const list = (b: unknown) => arr(b).map(parseRule);

export function listRules(signal?: AbortSignal): Promise<SlaRule[]> {
  return apiFetch(`${BASE}/rules`, list, { signal });
}

export function createRule(input: RuleInput): Promise<SlaRule> {
  return apiFetch(`${BASE}/rules`, parseRule, { method: "POST", body: input });
}

export function updateRule(id: number, input: RuleInput): Promise<SlaRule> {
  return apiFetch(`${BASE}/rules/${id}`, parseRule, { method: "PUT", body: input });
}

export function setRuleEnabled(id: number, enabled: boolean): Promise<SlaRule> {
  return apiFetch(`${BASE}/rules/${id}/enabled`, parseRule, {
    method: "PUT",
    body: { enabled },
  });
}

export function deleteRule(id: number): Promise<void> {
  return apiFetch(`${BASE}/rules/${id}`, ignore, { method: "DELETE" });
}

export function reorderRules(ruleIds: number[]): Promise<SlaRule[]> {
  return apiFetch(`${BASE}/order`, list, { method: "PUT", body: { ids: ruleIds } });
}
