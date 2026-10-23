.NOTPARALLEL:
.PHONY: help up down build generate seed run lane cluster-submit test verify conformance

# Evaluated ONCE (:=). With ?= the $(shell date) would re-run at every use, so `make run` could seed one run id
# and gate another.
ifndef RUN_ID
RUN_ID := run-local-$(shell date +%Y%m%d%H%M%S)
endif
ACCOUNTS ?= 2000
AS_OF_DATE ?= 2026-09-07
SRC_URL ?= jdbc:postgresql://localhost:5443/source
LANE_URL ?= jdbc:postgresql://localhost:5442/mig
KAFKA_BOOTSTRAP ?= localhost:19092
SPEC := ../../spec
JOB_JAR := intake-jdbc/target/flink-jdbc-job.jar
GATE_JAR := gate-jdbc/target/gate-jdbc.jar
RECON_JAR := ../flink/recon/target/recon-service.jar
SRC_PSQL := podman exec -i etlr-jdbc-source psql -U mig -d source -v ON_ERROR_STOP=1 -q
COMPOSE := cd local-development-podman && podman-compose -f podman-compose.yml

help: ## Show the common verbs
	@grep -E '^[a-z-]+:.*## ' Makefile | sed 's/:.*## /\t/'

up: ## Start the local stack (source + lane Postgres, Redpanda, Flink cluster, mock, kafka-ui)
	$(COMPOSE) up -d --build

down: ## Stop the local stack and drop its volumes
	$(COMPOSE) down -v

build:
	mvn -q -DskipTests package

generate: ## Load ACCOUNTS synthetic accounts into the source and vouch for them under RUN_ID (needs `make up`)
	mkdir -p work/source
	python3 -I $(SPEC)/harness/generate.py --accounts $(ACCOUNTS) --out work/source
	python3 -I $(SPEC)/harness/load_db.py work/source/ACCOUNT.src --run-id $(RUN_ID) | $(SRC_PSQL)
	@echo "source loaded for $(RUN_ID); run the lane with: make lane RUN_ID=$(RUN_ID)"

seed:
	python3 -I $(SPEC)/harness/load_db.py fixtures/ACCOUNT.src --run-id $(RUN_ID) | $(SRC_PSQL)

run: build seed lane ## Run the lane once on the fixture, in-process (needs `make up`)

# gate-jdbc → intake → curate+enrich → produce (Kafka) → recon-jdbc → recon (balance), in-process.
lane:
	src_read=$$(java -jar $(GATE_JAR) $(RUN_ID) $(SRC_URL) etlr_reader reader) && src_read=$${src_read#src_read=} && \
	java -cp $(JOB_JAR) mig.sql.JdbcIntakeJob $(RUN_ID) $(SRC_URL) etlr_reader reader $(LANE_URL) mig mig . && \
	java -cp $(JOB_JAR) mig.sql.CurateEnrichJob $(RUN_ID) $(AS_OF_DATE) $(LANE_URL) mig mig . && \
	java -cp $(JOB_JAR) mig.sql.ProduceJob $(RUN_ID) $(LANE_URL) mig mig $(KAFKA_BOOTSTRAP) target-system-target && \
	java -cp $(GATE_JAR) mig.gatejdbc.ReconJdbc $(RUN_ID) $(SRC_URL) etlr_reader reader $(LANE_URL) mig mig && \
	java -jar $(RECON_JAR) $(RUN_ID) $$src_read $(LANE_URL) mig mig

# The three Flink jobs submitted to the REAL compose cluster (application-mode path), gate and recon on the host.
cluster-submit: build seed ## Run the lane on the compose Flink cluster (needs `make up`)
	mkdir -p work/contracts && cp -r contracts/* work/contracts/
	cp $(JOB_JAR) work/job.jar
	src_read=$$(java -jar $(GATE_JAR) $(RUN_ID) $(SRC_URL) etlr_reader reader) && src_read=$${src_read#src_read=} && \
	podman exec etlr-jdbc-flink-jm flink run -c mig.sql.JdbcIntakeJob /opt/mig/work/job.jar $(RUN_ID) \
		jdbc:postgresql://source-postgres:5432/source etlr_reader reader jdbc:postgresql://lane-postgres:5432/mig mig mig /opt/mig/work && \
	podman exec etlr-jdbc-flink-jm flink run -c mig.sql.CurateEnrichJob /opt/mig/work/job.jar $(RUN_ID) $(AS_OF_DATE) \
		jdbc:postgresql://lane-postgres:5432/mig mig mig /opt/mig/work && \
	podman exec etlr-jdbc-flink-jm flink run -c mig.sql.ProduceJob /opt/mig/work/job.jar $(RUN_ID) \
		jdbc:postgresql://lane-postgres:5432/mig mig mig redpanda:29092 target-system-target && \
	java -cp $(GATE_JAR) mig.gatejdbc.ReconJdbc $(RUN_ID) $(SRC_URL) etlr_reader reader $(LANE_URL) mig mig && \
	java -jar $(RECON_JAR) $(RUN_ID) $$src_read $(LANE_URL) mig mig

# Retries: the reused ../flink ITs wait for Postgres with pg_isready, which passes during the image's temporary
# init server, so one of them fails now and then with a DDL rc=2 (not a lane defect; Podman.java here polls over TCP).
test: ## All unit and integration tests (podman ITs skip cleanly without podman)
	mvn -q -Dsurefire.rerunFailingTestsCount=3 test

verify: ## This lane's own acceptance suite: the component ITs
	mvn -q -pl component-tests-jdbc -am test

conformance: ## The fixture, typed, through the lane; compared with spec/fixtures/db/expected
	$(COMPOSE) up -d source-postgres lane-postgres redpanda redpanda-topics
	@echo "waiting for both Postgres and redpanda..."
	@for i in $$(seq 1 60); do \
		podman exec etlr-jdbc-source pg_isready -U mig -d source >/dev/null 2>&1 \
		&& podman exec etlr-jdbc-lane pg_isready -U mig -d mig >/dev/null 2>&1 \
		&& [ "$$(podman inspect -f '{{.State.Health.Status}}' etlr-jdbc-redpanda 2>/dev/null)" = healthy ] && break; \
		sleep 2; done
	bash harness/conformance.sh
