# Security policy

## Supported versions

Only the latest commit on `main` is supported. There are no maintained release branches; fixes land on `main`.

## Reporting a vulnerability

Please do **not** open a public issue, pull request or discussion for a security problem.

Report it privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability** (GitHub private vulnerability reporting). Include:

- what is affected (endpoint, screen, file) and the commit you tested;
- steps to reproduce, or a proof of concept;
- the impact you expect (for example, one organisation reading another organisation's tickets).

Test only against your own local installation (`scripts/dev-up.sh`), never against someone else's deployment.

## What happens next

- An acknowledgement within 7 days.
- An assessment and, for a confirmed issue, a planned fix within 30 days of the report. Severe issues (cross-organisation data access, authentication bypass, remote code execution) are worked on first.
- A GitHub security advisory once the fix is on `main`, crediting you unless you prefer otherwise.

The project is maintained by one person, so these windows are targets, not guarantees.

## Scope and known limits

The security design and the risks that were accepted on purpose (for example, an invitation link on its own activates an account) are described in [docs/35-security.md](docs/35-security.md). Reports about an accepted risk are still welcome when they show a concrete way to exploit it.
