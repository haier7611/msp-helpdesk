#!/usr/bin/env bash
# Folds the changelog fragments in changelog.d/*.md into CHANGELOG.md and deletes them (ADR-029).
# Run by the maintainer at release time (or whenever main should show a combined changelog):
#   scripts/changelog-assemble.sh            fold, write CHANGELOG.md, delete the fragments
#   scripts/changelog-assemble.sh --dry-run  print the new CHANGELOG.md, change nothing
#
# A fragment is one file per PR, changelog.d/<short-name>.md, holding Keep a Changelog sections:
#   ### Added
#
#   - Thing that was added.
# Allowed headings: Added, Changed, Deprecated, Removed, Fixed, Security. Each section's lines are
# inserted at the top of the same heading under "## [Unreleased]" (newest first, fragments in file
# name order); a heading Unreleased lacks is appended at its end. changelog.d/README.md is kept.
# Turning [Unreleased] into a version heading stays a manual step at release.
set -euo pipefail
cd "$(dirname "$0")/.."

DRY_RUN=0
case "${1:-}" in
  --dry-run) DRY_RUN=1 ;;
  "") ;;
  *) echo "usage: scripts/changelog-assemble.sh [--dry-run]" >&2; exit 2 ;;
esac

shopt -s nullglob
fragments=()
for f in changelog.d/*.md; do
  [ "$(basename "$f")" = README.md ] && continue
  fragments+=("$f")
done
if [ "${#fragments[@]}" -eq 0 ]; then
  echo "changelog-assemble: no fragments in changelog.d/"
  exit 0
fi

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
python3 - "$out" CHANGELOG.md "${fragments[@]}" <<'PY'
import sys

out_path, changelog, *fragments = sys.argv[1:]
order = ["Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"]
collected = {k: [] for k in order}
for path in sorted(fragments):
    section = None
    for n, line in enumerate(open(path, encoding="utf-8").read().splitlines(), 1):
        if line.startswith("### "):
            section = line[4:].strip()
            if section not in collected:
                sys.exit(f"{path}:{n}: unknown section '{section}' (allowed: {', '.join(order)})")
        elif line.strip():
            if section is None:
                sys.exit(f"{path}:{n}: text before the first '### <Section>' heading")
            collected[section].append(line.rstrip())
    if section is None:
        sys.exit(f"{path}: no '### <Section>' heading")

lines = open(changelog, encoding="utf-8").read().splitlines()
try:
    start = lines.index("## [Unreleased]")
except ValueError:
    sys.exit("CHANGELOG.md: no '## [Unreleased]' heading")
end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## [")), len(lines))
unreleased = lines[start:end]

# A heading that appears twice under [Unreleased] (it happened: two "### Changed") is merged into
# its first occurrence, keeping every line in order, so entries are never split or lost.
def merge_duplicates(block):
    head, sections, current = [], [], None
    for line in block:
        if line.startswith("### "):
            current = [line, []]
            sections.append(current)
        elif current is None:
            head.append(line)
        else:
            current[1].append(line)
    merged = {}
    for heading, body in sections:
        body = [l for l in body]
        while body and not body[-1].strip():
            body.pop()
        if heading in merged:
            merged[heading] += [l for l in body if l.strip()]
        else:
            merged[heading] = body
    out = list(head)
    for heading, body in merged.items():
        out += [heading] + body + [""]
    return out

unreleased = merge_duplicates(unreleased)
for section in order:
    entries = collected[section]
    if not entries:
        continue
    heading = f"### {section}"
    if heading in unreleased:
        at = unreleased.index(heading) + 1
        while at < len(unreleased) and not unreleased[at].strip():
            at += 1
        unreleased[at:at] = entries
    else:
        while unreleased and not unreleased[-1].strip():
            unreleased.pop()
        unreleased += ["", heading, ""] + entries
if end < len(lines) and unreleased[-1].strip():
    unreleased.append("")
with open(out_path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines[:start] + unreleased + lines[end:]) + "\n")
PY

if [ "$DRY_RUN" -eq 1 ]; then
  cat "$out"
  exit 0
fi
cp "$out" CHANGELOG.md
rm -f -- "${fragments[@]}"
echo "changelog-assemble: folded ${#fragments[@]} fragment(s) into CHANGELOG.md and deleted them"
