.PHONY: help up down generate run verify parity test coverage coverage-check run-sql run-datastream cluster-submit conformance

help: ## Show the common verbs
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

up: ## Start the local stack (postgres, redpanda, Flink cluster, target-system-mock)
	cd local-development-podman && podman-compose -f podman-compose.yml up -d --build

down: ## Stop the local stack and drop its volumes
	cd local-development-podman && podman-compose -f podman-compose.yml down --remove-orphans -v

generate: ## Generate synthetic Source System data (harness)
	python3 -m harness.generate --accounts 2000

# RUN_ID is overridable; these run the jobs IN-PROCESS (a LocalStreamEnvironment) — for the
# real cluster, see
# cluster-submit below, which is the distinction was written about.
RUN_ID ?= run-local-$(shell date +%Y%m%d%H%M%S)
JDBC_URL ?= jdbc:postgresql://localhost:5432/mig
AS_OF_DATE ?= 2026-09-07
KAFKA_BOOTSTRAP ?= localhost:9092

run-sql:
	mvn -q -pl variant-flink-sql-batch-job -am -DskipTests package
	java -cp variant-flink-sql-batch-job/target/variant-flink-sql-batch-job.jar mig.sql.IntakeJob \
		fixtures/ACCOUNT.src $(RUN_ID) $(JDBC_URL) mig mig .
	java -cp variant-flink-sql-batch-job/target/variant-flink-sql-batch-job.jar mig.sql.CurateEnrichJob \
		$(RUN_ID) $(AS_OF_DATE) $(JDBC_URL) mig mig .
	java -cp variant-flink-sql-batch-job/target/variant-flink-sql-batch-job.jar mig.sql.ProduceJob \
		$(RUN_ID) $(JDBC_URL) mig mig $(KAFKA_BOOTSTRAP) target-system-target

run-datastream:
	mvn -q -pl variant-flink-datastream-batch-job -am -DskipTests package
	java -cp variant-flink-datastream-batch-job/target/variant-flink-datastream-batch-job.jar \
		mig.datastream.DataStreamJob fixtures/ACCOUNT.src $(RUN_ID) $(JDBC_URL) mig mig . \
		$(KAFKA_BOOTSTRAP) target-system-target

# The lane end to end, locally: the deployed SQL variant, then recon (which writes run_ledger).
# Needs postgres + redpanda (`make up`, or `make conformance` starts just those).
run: run-sql ## Run the lane once on the fixture, SQL variant + recon (needs `make up`)
	mvn -q -pl recon -am -DskipTests package
	java -jar recon/target/recon-service.jar $(RUN_ID) 55 $(JDBC_URL) mig mig

# Submits the three SQL jobs to the REAL cluster from `make up`, not to an embedded mini
# cluster — the gap that hid the bug for an entire GCP deploy cycle. Needs `make up`
# first; the job jar and fixture travel through the ./work bind mount the compose file
# declares at /opt/mig/work.
cluster-submit:
	mvn -q -pl variant-flink-sql-batch-job -am -DskipTests package
	mkdir -p work/contracts && cp -r contracts/* work/contracts/
	cp variant-flink-sql-batch-job/target/variant-flink-sql-batch-job.jar work/job.jar
	cp fixtures/ACCOUNT.src work/ACCOUNT.src
	podman exec mig-flink-jm flink run -c mig.sql.IntakeJob /opt/mig/work/job.jar \
		/opt/mig/work/ACCOUNT.src $(RUN_ID) jdbc:postgresql://postgres:5432/mig mig mig /opt/mig/work
	podman exec mig-flink-jm flink run -c mig.sql.CurateEnrichJob /opt/mig/work/job.jar \
		$(RUN_ID) $(AS_OF_DATE) jdbc:postgresql://postgres:5432/mig mig mig /opt/mig/work
	podman exec mig-flink-jm flink run -c mig.sql.ProduceJob /opt/mig/work/job.jar \
		$(RUN_ID) jdbc:postgresql://postgres:5432/mig mig mig redpanda:29092 target-system-target

test: ## All unit and integration tests (podman ITs skip cleanly without podman)
	mvn -q test

parity:
	mvn -q -pl component-tests -am test

verify: ## This implementation's own acceptance tests (SQL variant module)
	mvn -q -pl variant-flink-sql-batch-job -am test

# Whole-repo LINE coverage, aggregated across every module (see
# component-tests/pom.xml's jacoco:report-aggregate — it's the one module depending on
# all the others). `mvn test` alone does not produce this: report-aggregate is bound to
# the `verify` phase.
coverage:
	mvn -q verify

# Fails (non-zero exit) below the 65% floor. Not wired as a JaCoCo `check` goal — see
# component-tests/pom.xml's comment on why a multi-module aggregate check isn't a clean
# fit for jacoco-maven-plugin's `check` goal — this is the enforcement instead.
coverage-check: coverage
	@awk -F',' 'NR>1{lm+=$$8; lc+=$$9} END { \
		pct = 100*lc/(lm+lc); \
		printf "line coverage: %.2f%% (%d/%d)\n", pct, lc, lm+lc; \
		if (pct < 65) { print "FAILED: below the 65% floor"; exit 1 } \
	}' component-tests/target/site/jacoco-aggregate/jacoco.csv

# Shared conformance (../../spec/conformance): run the oracle fixture through BOTH variants
# and compare the seven tables with spec/fixtures/expected, row for row. Starts only what the
# in-process jobs need (postgres, redpanda, topics) — not the Flink cluster image.
conformance: ## Run the oracle fixture through both variants and compare with spec/fixtures/expected
	cd local-development-podman && podman-compose -f podman-compose.yml up -d postgres redpanda redpanda-topics
	@echo "waiting for postgres and redpanda..."
	@for i in $$(seq 1 60); do \
		podman exec mig-postgres pg_isready -U mig -d mig >/dev/null 2>&1 \
		&& [ "$$(podman inspect -f '{{.State.Health.Status}}' mig-redpanda 2>/dev/null)" = healthy ] && break; \
		sleep 2; done
	bash harness/conformance.sh sql
	bash harness/conformance.sh datastream
