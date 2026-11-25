#!/usr/bin/env bash
# Fails if any tracked file contains a leftover merge-conflict marker. Runs first in CI.
# "<<<<<<<" and ">>>>>>>" lines always count. A bare "=======" line counts only in a file that also
# has an opening "<<<<<<<" marker, so a Markdown heading underline of seven '=' is not a false alarm.
set -euo pipefail
cd "$(dirname "$0")/../.."
found=0
if git grep -nE '^(<<<<<<<|>>>>>>>)( |$)'; then found=1; fi
while IFS= read -r file; do
  if grep -nE '^=======$' "$file" | sed "s|^|$file:|"; then found=1; fi
done < <(git grep -lE '^<<<<<<<( |$)' || true)
if [ "$found" -ne 0 ]; then
  echo "conflict-markers: leftover merge-conflict marker(s) above - resolve them" >&2
  exit 1
fi
echo "conflict-markers: none found"
