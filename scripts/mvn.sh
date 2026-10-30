#!/usr/bin/env bash
# Runs Maven inside Docker (no local JDK needed). Testcontainers talks to the host Docker daemon.
# Build output is handed back to the calling user so target/ stays deletable.
set -euo pipefail
cd "$(dirname "$0")/.."
args=$(printf '%q ' "$@")
exec docker run --rm \
  --network host \
  --security-opt label=disable \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD":/workspace -w /workspace \
  -v roster-sync-m2:/root/.m2 \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  maven:3.9-eclipse-temurin-21 \
  sh -c "mvn -B -q $args; rc=\$?; chown -R $(id -u):$(id -g) /workspace/target 2>/dev/null; exit \$rc"
