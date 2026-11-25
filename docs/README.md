# Documentation

Numbered file names are kept stable so references in code comments resolve.

Code comments cite `ADR-NNN` for entries in the [decision log](10-decision-log.md). Some comments also carry identifiers such as `WP-12` or `AUD-29`: these are historical work-item and review identifiers from the private repository where the project is developed, kept for traceability; the items themselves are not published.

## Product

- [15-product-spec.md](15-product-spec.md) - the product as built: roles, tickets, email, SLAs, portal, admin
- [10-decision-log.md](10-decision-log.md) - design decisions with their options and reasoning
- [07-api-and-business-rules.md](07-api-and-business-rules.md) - API conventions and business rules
- [45-integrations-api.md](45-integrations-api.md) - the monitoring-alert API (API keys)
- [ui-design.md](ui-design.md) - design tokens, layout and accessibility rules

## Engineering

- [40-architecture.md](40-architecture.md) - modules, organisation isolation, jobs, data flow
- [03-database-design.md](03-database-design.md) - schema and migrations
- [35-security.md](35-security.md) - security design per area and the accepted risks
- [20-test-strategy.md](20-test-strategy.md) - test layers and CI
- [diagrams/00-index.md](diagrams/00-index.md) - system context, containers, deployment, data model, lifecycle, email flows, sequences, permissions, CI, backup
- [how-this-was-built.md](how-this-was-built.md) - the development process

## Running it

- [guides/local-development.md](guides/local-development.md) - run it on your machine (`scripts/dev-up.sh --demo`)
- [deployment.md](deployment.md) - deploy on your own server with Docker Compose
- [09-operations.md](09-operations.md) - day-to-day operations, upgrades, monitoring
- [30-reliability.md](30-reliability.md) - backup and restore, outbound mail, failure handling
- Runbooks: [rotate-data-key](runbooks/rotate-data-key.md), [entra-app-registration](runbooks/entra-app-registration.md), [connect-gmail](runbooks/connect-gmail.md), [move-domain-or-server](runbooks/move-domain-or-server.md)

## Using it

- [guides/admin-guide.md](guides/admin-guide.md) - for admins
- [guides/engineer-guide.md](guides/engineer-guide.md) - for agents (support engineers)
- [guides/client-guide.md](guides/client-guide.md) - for client contacts
- [guides/demo-walkthrough.md](guides/demo-walkthrough.md) - a scripted walk through the product with the demo data
