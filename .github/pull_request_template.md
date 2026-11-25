## What and why

<!-- One or two sentences; link the issue it closes (Closes #123). -->

## What changed

-

## Docs

- [ ] Product spec (docs/15-product-spec.md) updated, or behaviour not affected
- [ ] Decision log entry added for a design decision, or none needed
- [ ] Changelog fragment added in `changelog.d/` (see changelog.d/README.md)

## Tests

- Added:
- Run: `scripts/ci/api.sh`, `scripts/ci/web.sh` (or the parts you changed)

## Screenshots (UI changes)

## Checklist

- [ ] Every query on client-owned data is scoped by organisation, with a test that another organisation is denied
- [ ] No real personal data, company names or secrets in code, tests or docs (synthetic `.example` data only)
- [ ] Commits are signed off (`git commit -s`, see CONTRIBUTING.md)
