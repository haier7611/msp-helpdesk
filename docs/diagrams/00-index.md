# Diagrams - index

Purpose: the essential diagrams of MSP Helpdesk, drawn as Mermaid so GitHub renders them and pull requests can diff them. They are drawn from the code (Flyway migrations, `.github/workflows/ci.yml`, `scripts/`, `docker-compose.yml`) and the docs. All names and addresses are synthetic.

The three overview diagrams in [docs/40-architecture.md](../40-architecture.md) (context and deployment, sign-in, data model) stay there as the one-page summary; this folder holds the detailed versions. The ticket lifecycle and inbound email diagrams also live in [docs/15-product-spec.md](../15-product-spec.md) (section 3 and section 6); the files here link to them instead of copying them.

## Diagrams

| File | What it shows | Use it when |
| --- | --- | --- |
| [01-system-context.md](01-system-context.md) | People and outside systems (C4 level 1) | You are new and want the big picture |
| [02-containers.md](02-containers.md) | Web/Caddy, api, Postgres, Mailpit, poller and jobs, attachment volume | You need to know which part does what |
| [03-deployment.md](03-deployment.md) | TLS edge, containers, mail, backups, deploy and rollback | Setting up, deploying or debugging the public URL |
| [04-data-model.md](04-data-model.md) | ER diagrams by area plus a table glossary | Writing a migration or query |
| [05-ticket-lifecycle.md](05-ticket-lifecycle.md) | Statuses, SLA pause, timers, follow-up | Touching any status or timer |
| [06-email-flows.md](06-email-flows.md) | Inbound matching, outbound sending, signatures, outbox retries | Debugging "email did not become a ticket" or "email not sent" |
| [07-sequences.md](07-sequences.md) | Six end-to-end sequences (sign-in, Microsoft, portal ticket, reply, inbound reply, SLA breach) | Following one flow step by step |
| [08-security-and-permissions.md](08-security-and-permissions.md) | Role matrix, AccessScope client wall, step-up, security_version | Changing access rules or auth |
| [09-support-business-process.md](09-support-business-process.md) | The MSP support process from intake to close, with who does what | You are a business reader |
| [10-ci-cd.md](10-ci-cd.md) | Pull request to running host: GitHub Actions jobs, manual deploy | Changing CI |
| [11-backup-restore.md](11-backup-restore.md) | Nightly backup, retention, restore drill, export and move | Restoring or moving the system |

## Conventions

- Solid boxes are built. A dashed box or a note "not built" marks something decided but not built yet.
- Each file lists the ADRs and spec sections it reflects. When you change the code or doc a diagram is drawn from, update the diagram in the same pull request.
- At most about 20 nodes per diagram; bigger ones are split.
