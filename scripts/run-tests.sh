#!/usr/bin/env bash
# Unit + integration tests (Testcontainers starts PostgreSQL). Benchmarks: ./scripts/run-tests.sh -Pbench
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/mvn.sh test "$@"
for f in target/surefire-reports/*.txt; do sed -n 4p "$f" | sed 's/, Time elapsed.*in / /'; done
[ -f target/bench-results.md ] && cat target/bench-results.md || true
