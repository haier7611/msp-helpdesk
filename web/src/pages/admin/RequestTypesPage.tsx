import { useEffect, useState, type FormEvent } from "react";
import {
  createRequestType,
  listRequestTypes,
  PRIORITIES,
  reorderRequestTypes,
  setRequestTypeAssignee,
  updateRequestType,
  type AdminRequestType,
  type Priority,
} from "../../api/admin";
import { PRIORITY_LABELS } from "../../api/agent";
import { listSupporters, type Supporter } from "../../api/supporters";
import { Badge, PriorityBadge } from "../../components/Badge";
import { DataTable } from "../../components/DataTable";
import { Button, Checkbox, Select, TextField } from "../../components/forms";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/toastContext";
import { Card, PageHeader } from "../../components/ui";
import styles from "./admin.module.css";
import { messageOf } from "./adminShared";
import { useReportError } from "../../components/useReportError";

type Draft = {
  id: number | null;
  name: string;
  description: string;
  defaultPriority: Priority;
  portalVisible: boolean;
  archived: boolean;
  assignee: string;
  system: boolean;
};

const EMPTY: Draft = {
  id: null,
  name: "",
  description: "",
  defaultPriority: "P3",
  portalVisible: true,
  archived: false,
  assignee: "",
  system: false,
};

/**
 * Admin - Request types: add, rename, describe, starting priority,
 * show or hide in the portal, default assignee, archive and reorder. A type in use is archived,
 * never deleted. The built-in Alert type keeps its name and stays hidden (the server refuses).
 */
export function RequestTypesPage() {
  const toast = useToast();
  const report = useReportError();
  const [types, setTypes] = useState<AdminRequestType[] | null>(null);
  const [supporters, setSupporters] = useState<Supporter[]>([]);
  const [loadError, setLoadError] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listRequestTypes(controller.signal)
      .then(setTypes)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setLoadError(messageOf(e));
      });
    listSupporters({ status: "ACTIVE" }, controller.signal)
      .then(setSupporters)
      .catch((e: unknown) => report(e, "Agents could not be loaded."));
    return () => controller.abort();
  }, [report]);

  const nameOf = (id: number | null) =>
    id === null ? "None" : (supporters.find((s) => s.id === id)?.name ?? `Agent #${id}`);

  function edit(t: AdminRequestType) {
    setFormError("");
    setDraft({
      id: t.id,
      name: t.name,
      description: t.description,
      defaultPriority: t.defaultPriority,
      portalVisible: t.portalVisible,
      archived: t.archived,
      assignee: t.defaultAssigneeUserId === null ? "" : String(t.defaultAssigneeUserId),
      system: t.system,
    });
  }

  async function onSave(event: FormEvent) {
    event.preventDefault();
    if (!draft) return;
    setFormError("");
    if (draft.name.trim() === "") {
      setFormError("Enter a name.");
      return;
    }
    setBusy(true);
    try {
      const input = {
        name: draft.name.trim(),
        description: draft.description.trim(),
        defaultPriority: draft.defaultPriority,
        portalVisible: draft.portalVisible,
        archived: draft.archived,
      };
      let saved =
        draft.id === null
          ? await createRequestType(input)
          : await updateRequestType(draft.id, input);
      const assignee = draft.assignee === "" ? null : Number(draft.assignee);
      if (assignee !== saved.defaultAssigneeUserId) {
        await setRequestTypeAssignee(saved.id, assignee);
        saved = { ...saved, defaultAssigneeUserId: assignee };
      }
      setTypes(await listRequestTypes());
      toast.show(`Saved "${saved.name}".`, "success");
      setDraft(null);
    } catch (e) {
      setFormError(messageOf(e));
      report(e);
    } finally {
      setBusy(false);
    }
  }

  async function move(t: AdminRequestType, delta: -1 | 1) {
    if (!types) return;
    // Swap with the neighbour among the movable rows (the built-in Alert type keeps its place).
    const neighbour = movable[movable.findIndex((x) => x.id === t.id) + delta];
    if (!neighbour) return;
    const ids = types.map((x) => x.id);
    const at = ids.indexOf(t.id);
    const to = ids.indexOf(neighbour.id);
    ids[to] = t.id;
    ids[at] = neighbour.id;
    setBusy(true);
    try {
      setTypes(await reorderRequestTypes(ids));
    } catch (e) {
      toast.showError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }

  const movable = types?.filter((t) => !t.archived && !t.system) ?? [];

  return (
    <div className={styles.page}>
      <PageHeader
        title="Request types"
        actions={
          <Button
            variant="primary"
            onClick={() => {
              setFormError("");
              setDraft({ ...EMPTY });
            }}
          >
            Add request type
          </Button>
        }
      />
      <p className={styles.muted}>
        Clients choose one of these when they raise a ticket; it sets the starting priority. Agents
        can change the priority afterwards. Archived types are no longer offered, but tickets that
        use them keep them.
      </p>
      {loadError && (
        <p role="alert" className={styles.error}>
          {loadError}
        </p>
      )}
      <Card>
        <DataTable
          stackOnPhone
          caption="Request types"
          minWidth={820}
          loading={types === null && !loadError}
          rows={types ?? []}
          rowKey={(t) => String(t.id)}
          emptyMessage="No request types yet."
          columns={[
            {
              key: "order",
              header: "Order",
              render: (t) =>
                t.archived || t.system ? (
                  <span className={styles.muted}>-</span>
                ) : (
                  <div className={styles.orderButtons}>
                    <Button
                      className={styles.orderButton}
                      aria-label={`Move ${t.name} up`}
                      disabled={busy || movable[0]?.id === t.id}
                      onClick={() => void move(t, -1)}
                    >
                      ↑
                    </Button>
                    <Button
                      className={styles.orderButton}
                      aria-label={`Move ${t.name} down`}
                      disabled={busy || movable[movable.length - 1]?.id === t.id}
                      onClick={() => void move(t, 1)}
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
              render: (t) => (
                <>
                  <strong>{t.name}</strong>
                  {t.description && <div className={styles.muted}>{t.description}</div>}
                </>
              ),
            },
            {
              key: "priority",
              header: "Starts at",
              nowrap: true,
              render: (t) => <PriorityBadge priority={t.defaultPriority} long />,
            },
            {
              key: "portal",
              header: "Portal",
              render: (t) => (t.portalVisible ? "Shown" : "Hidden"),
            },
            {
              key: "assignee",
              header: "Default assignee",
              render: (t) => nameOf(t.defaultAssigneeUserId),
            },
            {
              key: "status",
              header: "Status",
              render: (t) =>
                t.archived ? (
                  <Badge tone="neutral">Archived</Badge>
                ) : t.system ? (
                  <Badge tone="brand">Built in</Badge>
                ) : (
                  <Badge tone="ok">Active</Badge>
                ),
            },
            { key: "tickets", header: "Tickets", render: (t) => t.ticketCount },
            {
              key: "edit",
              header: <span className="visually-hidden">Actions</span>,
              actions: true,
              render: (t) => (
                <Button aria-label={`Edit ${t.name}`} onClick={() => edit(t)}>
                  Edit
                </Button>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        open={draft !== null}
        title={draft?.id === null ? "Add request type" : "Edit request type"}
        onClose={() => setDraft(null)}
      >
        {draft && (
          <form className={styles.form} onSubmit={onSave} noValidate>
            <TextField
              label="Name"
              maxLength={100}
              value={draft.name}
              disabled={draft.system}
              hint={
                draft.system
                  ? "The Alert type keeps its name: the integrations API uses it."
                  : undefined
              }
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
            <TextField
              label="Description"
              hint="Shown under the name in the portal, such as 'Passwords, locked accounts'."
              maxLength={200}
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
            />
            <Select
              label="Starting priority"
              fieldSize="sm"
              hint="P1 starts the 24/7 clock, so only types hidden from the portal can start at P1."
              value={draft.defaultPriority}
              options={PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABELS[p] }))}
              onChange={(e) => setDraft({ ...draft, defaultPriority: e.target.value as Priority })}
            />
            <Select
              label="Default assignee"
              fieldSize="md"
              hint="New tickets of this type go to this agent (before the organisation's default)."
              value={draft.assignee}
              options={[
                { value: "", label: "None" },
                ...supporters.map((s) => ({ value: String(s.id), label: s.name })),
              ]}
              onChange={(e) => setDraft({ ...draft, assignee: e.target.value })}
            />
            <Checkbox
              label="Clients can choose it in the portal"
              checked={draft.portalVisible}
              disabled={draft.system}
              onChange={(e) => setDraft({ ...draft, portalVisible: e.target.checked })}
            />
            {draft.id !== null && (
              <Checkbox
                label="Archived (no longer offered for new tickets)"
                checked={draft.archived}
                disabled={draft.system}
                onChange={(e) => setDraft({ ...draft, archived: e.target.checked })}
              />
            )}
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
