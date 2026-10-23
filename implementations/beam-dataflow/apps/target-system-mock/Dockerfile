# Shared contracts/harness come from the monorepo's spec/ via a NAMED BUILD CONTEXT, not from this
# directory (there they are symlinks, which a build context does not follow). Build with:
#   podman build --build-context spec=../../spec -f apps/target-system-mock/Dockerfile ... .   (from implementations/beam-dataflow)
# Without --build-context, `COPY --from=spec` is read as an image called "spec" and fails confusingly.

# Target System mock — the Target System stand-in the Loader App pushes to.
#
# Multi-stage: Maven builds the app from the repo root context (so the `common`
# module is available), then only the JRE and the installed app survive into the
# runtime layer. Built automatically by `make up` via local/docker-compose.yml.
#
# ── build ────────────────────────────────────────────────────────────────────
FROM docker.io/maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build
# apps/common looks for the contracts at spec/contracts outside a container (parent pom property
# etlr.contracts.dir); here they are copied to /build/contracts, so say so.
ENV MAVEN_ARGS="-Detlr.contracts.dir=/build/contracts"

# POMs first so the dependency layer caches across source changes. All five module
# poms are needed even though only two are built: the root pom lists every module,
# and Maven refuses to read the reactor if any declared module is missing.
COPY pom.xml ./
COPY apps/common/pom.xml ./apps/common/pom.xml
COPY apps/extract/pom.xml ./apps/extract/pom.xml
COPY apps/load/pom.xml ./apps/load/pom.xml
COPY apps/recon/pom.xml ./apps/recon/pom.xml
COPY apps/target-system-mock/pom.xml ./apps/target-system-mock/pom.xml
RUN mvn -B -q -pl apps/target-system-mock -am dependency:go-offline -DskipTests

# Source last — it changes most often.
# apps/common embeds contracts/artefacts.json as a classpath resource; the Maven
# enforcer fails the build without it rather than shipping a jar that dies on first use.
COPY --from=spec contracts/artefacts.json ./contracts/artefacts.json

COPY apps/common/src ./apps/common/src
COPY apps/target-system-mock/src ./apps/target-system-mock/src

# The shade plugin writes one self-contained jar with every dependency inside.
RUN mvn -B -q -pl apps/target-system-mock -am package -DskipTests

# ── runtime ───────────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:25-jre

# Temurin JRE has no HTTP client on PATH; install curl so the compose healthcheck
# can probe /__admin/health without a separate tool image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

ENV TARGET_SYSTEM_PORT=8080 \
    TARGET_SYSTEM_FAILURE_RATE=0.15 \
    TARGET_SYSTEM_SEED=20260803

COPY --from=build /build/apps/target-system-mock/target/target-system-mock.jar /opt/target-system-mock.jar

EXPOSE 8080

# MaxRAMPercentage: the default heap is 25% of the container (about 128 MB of 512 MiB), which is what
# let 200,000 applied documents kill the consumer thread on 2026-10-17. The Cloud Run limit is 2 GiB.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/opt/target-system-mock.jar"]
