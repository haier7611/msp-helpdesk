import { useEffect, useState } from "react";
import { Link } from "react-router";
import { listAgentOrganisations, type AgentOrganisation } from "../../api/agent";
import { DataTable, type Column } from "../../components/DataTable";
import { PageHeader } from "../../components/ui";
import { errorText } from "../../format";
import styles from "../pages.module.css";

const columns: Column<AgentOrganisation>[] = [
  {
    key: "name",
    header: "Organisation",
    rowHeader: true,
    render: (o) => <Link to={`/agent/queue?org=${o.id}`}>{o.name}</Link>,
  },
  { key: "prefix", header: "Prefix", nowrap: true, render: (o) => o.prefix ?? "" },
  { key: "domains", header: "Verified domains", render: (o) => o.verifiedDomains.join(", ") },
];

/** Organisations the supporter works on (read only, only admins edit them). */
export function AgentOrganisationsPage() {
  const [state, setState] = useState<{ rows: AgentOrganisation[]; error: string | null } | null>(
    null,
  );

  useEffect(() => {
    const controller = new AbortController();
    listAgentOrganisations(controller.signal).then(
      (rows) => setState({ rows, error: null }),
      (e: unknown) => {
        if (!controller.signal.aborted) setState({ rows: [], error: errorText(e) });
      },
    );
    return () => controller.abort();
  }, []);

  return (
    <div className={styles.column}>
      <PageHeader
        title="Organisations"
        meta={state ? `${state.rows.length} organisations` : undefined}
      />
      {state?.error && (
        <p className={styles.errorBox} role="alert">
          {state.error}
        </p>
      )}
      <DataTable
        stackOnPhone
        caption="Organisations"
        columns={columns}
        rows={state?.rows ?? []}
        loading={!state}
        rowKey={(o) => String(o.id)}
        emptyMessage="You are not assigned to any organisation."
      />
    </div>
  );
}
