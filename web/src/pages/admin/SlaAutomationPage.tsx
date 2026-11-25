import { useEffect, useState, type FormEvent } from "react";
import {
  listRequestTypes,
  PRIORITIES,
  type AdminRequestType,
  type Priority,
} from "../../api/admin";
import { PRIORITY_LABELS } from "../../api/agent";
import { listOrganisations, type Organisation } from "../../api/organisations";
import {
  ACTION_LABELS,
  ACTION_TYPES,
  createRule,
  deleteRule,
  listRules,
  reorderRules,
  setRuleEnabled,
  TRIGGER_LABELS,
  TRIGGERS,
  updateRule,
  type ActionType,
  type RuleAction,
  type RuleInput,
  type SlaRule,
  type SlaTrigger,
} from "../../api/slaAutomation";
import { listSupporters, type Supporter } from "../../api/supporters";
import { Badge } from "../../components/Badge";
import { DataTable } from "../../components/DataTable";
import { Button, Checkbox, Select, Textarea, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import { useReportError } from "../../components/useReportError";
import styles from "./admin.module.css";
import { messageOf } from "./adminShared";

const NOTE_MAX = 1000;

type Draft = {
  id: number | null;
  name: string;
  enabled: boolean;
  trigger: SlaTrigger;
  includeTriage: boolean;
  organisationIds: number[];
  priorities: Priority[];
  requestTypeIds: number[];
  actions: RuleAction[];
};

const EMPTY: Draft = {
  id: null,
  name: "",
  enabled: true,
  trigger: "FIRST_RESPONSE_BREACHED",
  includeTriage: false,
  organisationIds: [],
  priorities: [],
  requestTypeIds: [],
  actions: [{ type: "NOTIFY_ORGANISATION", agentIds: [], agentId: null, text: null }],
};

function blankAction(type: ActionType): RuleAction {
  return { type, agentIds: [], agentId: null, text: type === "ADD_NOTE" ? "" : null };
}

function toggle<T>(list: T[], value: T, on: boolean): T[] {
  return on ? (list.includes(value) ? list : [...list, value]) : list.filter((v) => v !== value);
}

/** What is wrong with the draft, or "" (the server checks everything again). */
function problem(d: Draft): string {
  if (d.name.trim() === "") return "Enter a name.";
  if (d.actions.length === 0) return "Add at least one action.";
  for (const a of d.actions) {
    if (a.type === "NOTIFY_AGENTS" && a.agentIds.length === 0)
      return "Choose at least one agent to notify.";
    if (a.type === "ASSIGN" && a.agentId === null)
      return "Choose the agent to assign the ticket to.";
    if (a.type === "ADD_NOTE" && (a.text ?? "").trim() === "")
      return "Enter the internal note text.";
  }
  return "";
}

/**
 * Admin - SLA automation: rules that act when a ticket's SLA becomes at risk,
 * breached or met. Each rule fires at most once per ticket, target and trigger; rules run in the
 * order shown. Admin only (the server enforces it); no step-up.
 */
export function SlaAutomationPage() {
  const toast = useToast();
  const report = useReportError();
  const [rules, setRules] = useState<SlaRule[] | null>(null);
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [types, setTypes] = useState<AdminRequestType[]>([]);
  const [agents, setAgents] = useState<Supporter[]>([]);
  const [loadError, setLoadError] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    listRules(signal)
      .then(setRules)
      .catch((e: unknown) => {
        if (!signal.aborted) setLoadError(messageOf(e));
      });
    listOrganisations(signal)
      .then(setOrganisations)
      .catch((e: unknown) => report(e, "Organisations could not be loaded."));
    listRequestTypes(signal)
      .then(setTypes)
      .catch((e: unknown) => report(e, "Request types could not be loaded."));
    listSupporters({ status: "ACTIVE" }, signal)
      .then(setAgents)
      .catch((e: unknown) => report(e, "Agents could not be loaded."));
    return () => controller.abort();
  }, [report]);

  const orgName = (id: number) =>
    organisations.find((o) => o.id === id)?.name ?? "a removed organisation";
  const typeName = (id: number) => types.find((t) => t.id === id)?.name ?? "a removed type";
  const agentName = (id: number | null) =>
    id === null ? "nobody" : (agents.find((a) => a.id === id)?.name ?? "a deactivated agent");

  function conditionsText(r: SlaRule): string {
    const c = r.conditions;
    const orgs = [...(c.includeTriage ? ["Email triage"] : []), ...c.organisationIds.map(orgName)];
    return [
      orgs.length ? orgs.join(", ") : "Any organisation",
      c.priorities.length ? c.priorities.join(", ") : "any priority",
      c.requestTypeIds.length ? c.requestTypeIds.map(typeName).join(", ") : "any request type",
    ].join("; ");
  }

  function actionText(a: RuleAction): string {
    switch (a.type) {
      case "NOTIFY_AGENTS":
        return `Notify ${a.agentIds.map(agentName).join(", ")}`;
      case "NOTIFY_ORGANISATION":
        return "Notify the organisation's agents";
      case "ASSIGN":
        return `Assign to ${agentName(a.agentId)} if unassigned`;
      case "RAISE_PRIORITY":
        return "Raise the priority";
      case "ADD_NOTE":
        return "Add an internal note";
    }
  }

  function edit(r: SlaRule) {
    setFormError("");
    setDraft({
      id: r.id,
      name: r.name,
      enabled: r.enabled,
      trigger: r.trigger,
      includeTriage: r.conditions.includeTriage,
      organisationIds: r.conditions.organisationIds,
      priorities: r.conditions.priorities,
      requestTypeIds: r.conditions.requestTypeIds,
      actions: r.actions.map((a) => ({ ...a })),
    });
  }

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!draft) return;
    const wrong = problem(draft);
    setFormError(wrong);
    if (wrong) return;
    const input: RuleInput = {
      name: draft.name.trim(),
      enabled: draft.enabled,
      trigger: draft.trigger,
      conditions: {
        includeTriage: draft.includeTriage,
        organisationIds: draft.organisationIds,
        priorities: draft.priorities,
        requestTypeIds: draft.requestTypeIds,
      },
      actions: draft.actions.map((a) => ({
        type: a.type,
        agentIds: a.type === "NOTIFY_AGENTS" ? a.agentIds : [],
        agentId: a.type === "ASSIGN" ? a.agentId : null,
        text: a.type === "ADD_NOTE" ? (a.text ?? "").trim() : null,
      })),
    };
    setBusy(true);
    try {
      const saved = draft.id === null ? await createRule(input) : await updateRule(draft.id, input);
      setRules(await listRules());
      toast.show(`Saved "${saved.name}".`, "success");
      setDraft(null);
    } catch (e) {
      setFormError(messageOf(e));
      report(e, "The rule was not saved.");
    } finally {
      setBusy(false);
    }
  }

  async function onToggle(r: SlaRule, enabled: boolean) {
    setBusy(true);
    try {
      const saved = await setRuleEnabled(r.id, enabled);
      setRules((rs) => rs?.map((x) => (x.id === saved.id ? saved : x)) ?? null);
      toast.show(`"${saved.name}" is ${saved.enabled ? "on" : "off"}.`, "success");
    } catch (e) {
      report(e, "The rule was not changed.");
    } finally {
      setBusy(false);
    }
  }

  async function move(r: SlaRule, delta: -1 | 1) {
    if (!rules) return;
    const ids = rules.map((x) => x.id);
    const at = ids.indexOf(r.id);
    const to = at + delta;
    if (to < 0 || to >= ids.length) return;
    ids[at] = ids[to] as number;
    ids[to] = r.id;
    setBusy(true);
    try {
      setRules(await reorderRules(ids));
    } catch (e) {
      report(e, "The order was not changed.");
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(r: SlaRule) {
    setBusy(true);
    try {
      await deleteRule(r.id);
      setRules((rs) => rs?.filter((x) => x.id !== r.id) ?? null);
      setConfirmDelete(null);
      toast.show(`Deleted "${r.name}". Its timeline lines stay on the tickets.`, "success");
    } catch (e) {
      report(e, "The rule was not deleted.");
    } finally {
      setBusy(false);
    }
  }

  function setAction(index: number, next: RuleAction) {
    if (!draft) return;
    setDraft({ ...draft, actions: draft.actions.map((a, i) => (i === index ? next : a)) });
  }

  const list = rules ?? [];

  return (
    <div className={styles.page}>
      <PageHeader
        title="SLA automation"
        actions={
          <Button
            variant="primary"
            onClick={() => {
              setFormError("");
              setDraft({ ...EMPTY, actions: EMPTY.actions.map((a) => ({ ...a })) });
            }}
          >
            Add rule
          </Button>
        }
      />
      <p className={styles.muted}>
        Rules act when a ticket&apos;s first response or resolution becomes at risk (80% of the
        target used), breached or met. They run in the order shown, each at most once per ticket,
        and every action appears in the ticket timeline as &quot;Automation (rule name)&quot;. The
        built-in SLA notifications still go out as before.
      </p>
      <p className={styles.muted}>
        Conditions are checked against the ticket as it was when the state changed, so a later rule
        still sees the original priority and assignee. Editing a rule keeps its history: it will not
        act again on tickets it has already acted on. To run it again, create a new rule.
      </p>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      <Card>
        <DataTable
          stackOnPhone
          caption="SLA automation rules"
          minWidth={900}
          loading={rules === null && !loadError}
          rows={list}
          rowKey={(r) => String(r.id)}
          emptyMessage="No rules yet."
          columns={[
            {
              key: "order",
              header: "Order",
              render: (r) => (
                <div className={styles.orderButtons}>
                  <Button
                    className={styles.orderButton}
                    aria-label={`Move ${r.name} up`}
                    disabled={busy || list[0]?.id === r.id}
                    onClick={() => void move(r, -1)}
                  >
                    ↑
                  </Button>
                  <Button
                    className={styles.orderButton}
                    aria-label={`Move ${r.name} down`}
                    disabled={busy || list[list.length - 1]?.id === r.id}
                    onClick={() => void move(r, 1)}
                  >
                    ↓
                  </Button>
                </div>
              ),
            },
            {
              key: "name",
              header: "Name",
              rowHeader: true,
              render: (r) => <strong>{r.name}</strong>,
            },
            { key: "trigger", header: "When", render: (r) => TRIGGER_LABELS[r.trigger] },
            { key: "conditions", header: "Tickets", render: conditionsText },
            {
              key: "actions",
              header: "Then",
              render: (r) => r.actions.map(actionText).join("; "),
            },
            {
              key: "enabled",
              header: "Status",
              render: (r) => (
                <Checkbox
                  label={
                    r.enabled ? <Badge tone="ok">On</Badge> : <Badge tone="neutral">Off</Badge>
                  }
                  aria-label={`${r.name} enabled`}
                  checked={r.enabled}
                  disabled={busy}
                  onChange={(e) => void onToggle(r, e.target.checked)}
                />
              ),
            },
            {
              key: "edit",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (r) =>
                confirmDelete === r.id ? (
                  <span role="group" aria-label={`Confirm deleting ${r.name}`}>
                    <span className={styles.muted}>Delete this rule?</span>{" "}
                    <Button variant="danger" disabled={busy} onClick={() => void onDelete(r)}>
                      Yes, delete
                    </Button>
                    <Button onClick={() => setConfirmDelete(null)}>Keep</Button>
                  </span>
                ) : (
                  <>
                    <Button aria-label={`Edit ${r.name}`} onClick={() => edit(r)}>
                      Edit
                    </Button>
                    <Button
                      variant="danger"
                      aria-label={`Delete ${r.name}`}
                      disabled={busy}
                      onClick={() => setConfirmDelete(r.id)}
                    >
                      Delete
                    </Button>
                  </>
                ),
            },
          ]}
        />
      </Card>

      <Modal
        open={draft !== null}
        size="wide"
        title={draft?.id === null ? "Add rule" : "Edit rule"}
        onClose={() => setDraft(null)}
      >
        {draft && (
          <form className={styles.form} onSubmit={onSave} noValidate>
            <TextField
              label="Name"
              maxLength={100}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
            <Select
              label="When"
              fieldSize="md"
              value={draft.trigger}
              options={TRIGGERS.map((t) => ({ value: t, label: TRIGGER_LABELS[t] }))}
              onChange={(e) => setDraft({ ...draft, trigger: e.target.value as SlaTrigger })}
            />
            <Checkbox
              label="Rule is on"
              checked={draft.enabled}
              onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
            />

            <fieldset className={styles.section}>
              <legend className={styles.sectionLegend}>Organisations (none ticked = any)</legend>
              <Checkbox
                label="Email triage (no organisation yet)"
                checked={draft.includeTriage}
                onChange={(e) => setDraft({ ...draft, includeTriage: e.target.checked })}
              />
              {organisations.map((o) => (
                <Checkbox
                  key={o.id}
                  label={o.name}
                  checked={draft.organisationIds.includes(o.id)}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      organisationIds: toggle(draft.organisationIds, o.id, e.target.checked),
                    })
                  }
                />
              ))}
            </fieldset>
            <fieldset className={styles.section}>
              <legend className={styles.sectionLegend}>Priorities (none ticked = any)</legend>
              {PRIORITIES.map((p) => (
                <Checkbox
                  key={p}
                  label={PRIORITY_LABELS[p]}
                  checked={draft.priorities.includes(p)}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      priorities: toggle(draft.priorities, p, e.target.checked),
                    })
                  }
                />
              ))}
            </fieldset>
            <fieldset className={styles.section}>
              <legend className={styles.sectionLegend}>Request types (none ticked = any)</legend>
              {types.map((t) => (
                <Checkbox
                  key={t.id}
                  label={t.archived ? `${t.name} (archived)` : t.name}
                  checked={draft.requestTypeIds.includes(t.id)}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      requestTypeIds: toggle(draft.requestTypeIds, t.id, e.target.checked),
                    })
                  }
                />
              ))}
            </fieldset>

            <fieldset className={styles.section}>
              <legend className={styles.sectionLegend}>Then (actions, in this order)</legend>
              {draft.actions.map((a, i) => (
                <div key={i} className={styles.section}>
                  <Select
                    label={`Action ${i + 1}`}
                    fieldSize="md"
                    value={a.type}
                    options={ACTION_TYPES.map((t) => ({ value: t, label: ACTION_LABELS[t] }))}
                    onChange={(e) => setAction(i, blankAction(e.target.value as ActionType))}
                  />
                  {a.type === "NOTIFY_AGENTS" && (
                    <fieldset className={styles.section}>
                      <legend className={styles.sectionLegend}>
                        Agents to notify (only those who can work the ticket&apos;s organisation are
                        told)
                      </legend>
                      {agents.map((s) => (
                        <Checkbox
                          key={s.id}
                          label={s.name}
                          checked={a.agentIds.includes(s.id)}
                          onChange={(e) =>
                            setAction(i, {
                              ...a,
                              agentIds: toggle(a.agentIds, s.id, e.target.checked),
                            })
                          }
                        />
                      ))}
                    </fieldset>
                  )}
                  {a.type === "ASSIGN" && (
                    <Select
                      label={`Agent for action ${i + 1}`}
                      fieldSize="md"
                      hint="Skipped (with a timeline line) if the ticket has an assignee or the agent cannot work its organisation."
                      value={a.agentId === null ? "" : String(a.agentId)}
                      options={[
                        { value: "", label: "Choose an agent" },
                        ...agents.map((s) => ({ value: String(s.id), label: s.name })),
                      ]}
                      onChange={(e) =>
                        setAction(i, {
                          ...a,
                          agentId: e.target.value === "" ? null : Number(e.target.value),
                        })
                      }
                    />
                  )}
                  {a.type === "ADD_NOTE" && (
                    <Textarea
                      label={`Note text for action ${i + 1}`}
                      rows={3}
                      maxLength={NOTE_MAX}
                      hint="Plain text, shown to agents only."
                      value={a.text ?? ""}
                      onChange={(e) => setAction(i, { ...a, text: e.target.value })}
                    />
                  )}
                  {a.type === "RAISE_PRIORITY" && (
                    <p className={styles.muted}>
                      One level up, never above P1. The SLA is recalculated as for a manual change.
                    </p>
                  )}
                  <div className={styles.actions}>
                    <Button
                      aria-label={`Remove action ${i + 1}`}
                      disabled={draft.actions.length === 1}
                      onClick={() =>
                        setDraft({ ...draft, actions: draft.actions.filter((_, j) => j !== i) })
                      }
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              ))}
              {draft.actions.length < 10 && (
                <div className={styles.actions}>
                  <Button
                    onClick={() =>
                      setDraft({
                        ...draft,
                        actions: [...draft.actions, blankAction("NOTIFY_ORGANISATION")],
                      })
                    }
                  >
                    Add action
                  </Button>
                </div>
              )}
            </fieldset>

            {formError && (
              <p role="alert" className={styles.error}>
                {formError}
              </p>
            )}
            <div className={styles.actions}>
              <Button type="submit" variant="primary" disabled={busy}>
                Save
              </Button>
              <Button onClick={() => setDraft(null)}>Cancel</Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  );
}
