# Changelog fragments

One file per pull request: `changelog.d/<short-name>.md` (for example `sla-pause-on-vendor.md`). Do not edit `CHANGELOG.md` in a pull request - parallel pull requests would conflict on its top lines.

A fragment holds [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) sections, only these headings: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`.

```markdown
### Fixed

- Short description of what was fixed, written for someone running the app.
```

At release the maintainer runs `scripts/changelog-assemble.sh` (`--dry-run` to preview): it inserts every fragment's lines at the top of the matching heading under `## [Unreleased]` in `CHANGELOG.md` and deletes the fragments. This README stays.
