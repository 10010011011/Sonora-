#!/usr/bin/env bash
# Prints a SHORT markdown digest of build/test failures so you can paste it into the chat instead of the whole log.
echo "## Build error digest"
for f in "$@"; do
  [ -f "$f" ] || continue
  echo "### $f"
  printf '%s\n' '```'
  grep -E '^(e: |w: .*deprecat)|error:|FAILURE:|Execution failed|Could not resolve|Unresolved reference|AAPT:|requires libraries and applications' "$f" | sed 's#file:///home/runner/work/[^/]*/[^/]*/native/##' | head -50
  printf '%s\n' '```'
  awk '/\* What went wrong:/{flag=1} /\* Try:/{flag=0} flag' "$f" | head -25
done
