#!/bin/bash
# Builds this lane's images. Context: implementations/ (both lanes' sources); spec/ as a named context.
#   gcp/docker/build.sh <registry> <tag>     registry e.g. europe-west1-docker.pkg.dev/<project>/mig-flink
# DRY_RUN=1 prints the four commands instead of running them (JdbcImageContractTest checks them).
set -euo pipefail
REGISTRY="${1:?usage: build.sh <registry> <tag>}"
TAG="${2:?usage: build.sh <registry> <tag>}"
LANE="$(cd "$(dirname "$0")/../.." && pwd)"
IMPL="$(cd "$LANE/.." && pwd)"
SPEC="$(cd "$IMPL/../spec" && pwd)"
IGNORE="$LANE/gcp/docker/build.ignore"

run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$*"; else "$@"; fi; }

for pair in orchestrator:Dockerfile.orchestrator flink-jdbc-job:Dockerfile.flinkjob source-seeder:Dockerfile.seeder; do
  run podman build --build-context "spec=$SPEC" --ignorefile "$IGNORE" \
    -f "$LANE/gcp/docker/${pair#*:}" -t "$REGISTRY/${pair%%:*}:$TAG" "$IMPL"
done
# The mock is the Flink lane's, built exactly as that lane builds it.
run podman build --build-arg MIG_MODULE=target-system-mock -f "$IMPL/flink/gcp/docker/Dockerfile.javaapp" \
  -t "$REGISTRY/target-system-mock:$TAG" "$IMPL/flink"
