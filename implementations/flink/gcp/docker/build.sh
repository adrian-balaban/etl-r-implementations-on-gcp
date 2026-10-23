#!/bin/bash
# Builds this lane's images. Context: implementations/flink; spec/ as a named context.
#   gcp/docker/build.sh <registry> <tag>     registry e.g. europe-west1-docker.pkg.dev/<project>/mig-flink
# The orchestrator image carries gate.jar and recon-service.jar, so three images cover the lane.
# DRY_RUN=1 prints the commands instead of running them.
set -euo pipefail
REGISTRY="${1:?usage: build.sh <registry> <tag>}"
TAG="${2:?usage: build.sh <registry> <tag>}"
LANE="$(cd "$(dirname "$0")/../.." && pwd)"
SPEC="$(cd "$LANE/../../spec" && pwd)"

run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$*"; else "$@"; fi; }

for pair in orchestrator:Dockerfile.orchestrator flink-job:Dockerfile.flinkjob; do
  run podman build --build-context "spec=$SPEC" \
    -f "$LANE/gcp/docker/${pair#*:}" -t "$REGISTRY/${pair%%:*}:$TAG" "$LANE"
done
run podman build --build-arg MIG_MODULE=target-system-mock -f "$LANE/gcp/docker/Dockerfile.javaapp" \
  -t "$REGISTRY/target-system-mock:$TAG" "$LANE"
