# How this was built

This page describes the development process once, factually. The rest of the documentation describes the product.

## The process

**Spec first.** No feature was built without a section in the product spec ([15-product-spec.md](15-product-spec.md)) describing the behaviour, the access rules and the failure cases. A change in behaviour updated the spec, the rules doc and the diagrams in the same change.

**Decisions written down.** Every product or design decision with real alternatives went into the decision log ([10-decision-log.md](10-decision-log.md)) with its context, the options considered and the reasoning. The log is append-only: a later decision overrides an earlier one and says so. It holds 50 entries; the ones that only concerned the original installation are reduced to one line in this public copy.

**One author, one separate reviewer, for every change.** Each piece of work was written by one author and then reviewed by a different reviewer who had not written it - for correctness, for the spec, and with a security focus for anything touching authentication, permissions, storage, secrets or personal data. A change merged only after a recorded review approved the exact commit being merged, and only with CI green on that commit. Findings that blocked a merge were fixed and reviewed again. The review records themselves stay in the private development repository, because they refer to its infrastructure.

**Rules enforced by tests, not by convention.** Organisation isolation is checked by ArchUnit rules in [`ArchitectureTest`](../api/src/test/java/com/ticketsystem/api/architecture/ArchitectureTest.java): client-data repositories must live in `clientdata` packages and take an access scope in every method, controllers cannot reach them directly, only those packages use JDBC, only scheduled jobs may create a system scope, only the reference module builds ticket references, and portal and public models never carry internal notes. Several rules have negative tests proving they fail on a deliberately bad example, so they cannot pass vacuously. Beside that, every client-data feature has integration tests (Testcontainers, real PostgreSQL) showing another organisation is denied.

**CI on every change.** Formatting, compile, unit and integration tests, the web lint/typecheck/tests/build, real-container checks of the web server configuration, and a dependency vulnerability scan, all through the scripts in `scripts/ci/` that also run by hand ([20-test-strategy.md](20-test-strategy.md)).

## Use of an AI assistant

The project was built with Claude (Anthropic's AI assistant) under the owner's direction. The owner set the product scope, made the product and security decisions recorded in the decision log, and set the rules that reviews checked against. Claude drafted specifications, wrote code, tests and documentation, and coordinated the work as a lead: splitting it into items, making smaller engineering calls (recorded in the decision log as such), and assigning each change to an author session and a separate reviewer session with their own instructions. Decisions about product behaviour, production and anything destructive went back to the owner.

## What is in this repository

Counted from this repository:

| Artefact | Count |
|---|---|
| Decision log entries | 50 |
| Flyway database migrations | 32 |
| api test classes (`*Test.java`) | 134 |
| web test files | 33 |
| Tests in `ArchitectureTest` | 15 |

This repository is published as cleaned snapshots of a private development repository (see ADR-050 in the decision log), so its history starts at the first public snapshot.
