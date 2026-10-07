#!/usr/bin/env bash
# Turns Gradle's compile errors and "What went wrong" blocks in a build log into check-run
# annotations, for failures that never reach a JUnit XML file (the cloud session can't read logs).
log="${1:?usage: gradle-errors.sh <build.log>}"
[ -f "$log" ] || exit 0
grep -E '^e: ' "$log" | head -20 | while IFS= read -r line; do
  echo "::error title=Compile error::${line:0:400}"
done
awk '/^\* What went wrong:/{flag=1; n=0; next} /^\* Try:/{flag=0} flag && NF {n++; if (n<=6) printf "%s ", $0} END{print ""}' "$log" \
  | sed 's/  */ /g' | grep -v '^ *$' | head -5 | while IFS= read -r line; do
  echo "::error title=Gradle failed::${line:0:600}"
done
