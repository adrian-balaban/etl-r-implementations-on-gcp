# Root entry points. Each implementation keeps its own Makefile; these only delegate.
#
#   make spec-test          the comparator's own tests (it must fail on damage, not just pass)
#   make conformance-flink  the Flink lane's SQL and DataStream variants on the oracle fixture (needs podman)
#   make conformance-flink-jdbc  the flink-jdbc lane on the typed-source oracle (needs podman)
#   make conformance-beam   the Beam lane on the oracle input           (needs `make bootstrap` once there)
#   make conformance-all    all of the above
#   make down-all           stop every local stack
#
# Nothing here touches GCP.

PYTEST ?= uv run --no-project --with pytest python -m pytest

.PHONY: spec-test conformance-flink conformance-flink-jdbc conformance-beam conformance-all down-all

spec-test:
	$(PYTEST) spec -q -p no:cacheprovider

conformance-flink:
	$(MAKE) -C implementations/flink conformance

# Tear the stack down afterwards, pass or fail: its Redpanda holds host port 19092, which Beam's stack also binds.
conformance-flink-jdbc:
	$(MAKE) -C implementations/flink-jdbc conformance; rc=$$?; $(MAKE) -C implementations/flink-jdbc down; exit $$rc

conformance-beam:
	$(MAKE) -C implementations/beam-dataflow up init-infra conformance

conformance-all: spec-test conformance-flink conformance-flink-jdbc conformance-beam

down-all:
	-$(MAKE) -C implementations/flink down
	-$(MAKE) -C implementations/flink-jdbc down
	-$(MAKE) -C implementations/beam-dataflow down
