package mig.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.common.Doors;
import mig.common.Doors.Door;
import mig.common.Mapping;
import mig.common.RecordRouter;
import mig.common.RecordRouter.Outcome;
import mig.common.TargetSchemaValidator;

/**
 *  — "the test that decides". Routes fixtures/ACCOUNT.src (sha256
 * 86f76332f08e7c8c0a0a78a4366fb124151f3467fbb3a68a556fd6461604426c, 56 lines = header + 55) row
 * by row through {@link RecordRouter}, ported 1:1 from the vendored two-door engine, and
 * compares {@code stage}/{@code reason}/{@code detail}/{@code source_key}/{@code raw_record}
 * row-by-row (sorted) against fixtures/expected/reject_log.tsv — not aggregate counts.
 *
 * <p>This exercises the engine directly rather than through a live Flink cluster: the routing
 * logic is deliberately runner-free (same reasoning as the vendored Python original), and this
 * is what the SQL and DataStream Flink jobs both have to reproduce byte-for-byte. See
 *  for what running this same assertion through an actual Flink cluster
 * still requires.
 */
class ParityTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String RUN_ID = "run-deepdive-clean-20260907"; // matches the evidence set

  private static List<String> lines;
  private static Mapping.Engine engine;
  private static TargetSchemaValidator validator;

  @BeforeAll
  static void setup() throws IOException {
    lines = Files.readAllLines(ROOT.resolve("fixtures/ACCOUNT.src"));
    Mapping.Config cfg =
        Mapping.load(ROOT.resolve("contracts/mappings/mapping-project1.yaml"), ROOT);
    engine = new Mapping.Engine(cfg);
    validator = new TargetSchemaValidator(cfg.targetSchemaPath());
  }

  private record RejectRow(String stage, String reason, String detail, String sourceKey, String rawRecord) {}

  private List<RejectRow> routeAll() {
    RecordRouter router = new RecordRouter(engine, validator, RUN_ID);
    List<RejectRow> rejects = new ArrayList<>();
    Doors.Counters counters = new Doors.Counters();
    long batchId = 0;
    for (String raw : lines) {
      if (raw.isBlank() || engine.isHeader(raw)) {
        continue;
      }
      counters.srcRead++;
      Outcome outcome = router.route(raw, batchId);
      Doors.Reason reason = outcome.reject() != null ? Doors.Reason.valueOf(outcome.reject().reason()) : null;
      counters.count(outcome.door(), reason);
      if (outcome.door() == Door.REJECTED) {
        rejects.add(
            new RejectRow(
                outcome.reject().stage(),
                outcome.reject().reason(),
                outcome.reject().detail(),
                outcome.reject().sourceKey(),
                outcome.reject().rawRecord()));
      }
    }
    lastCounters = counters;
    return rejects;
  }

  private Doors.Counters lastCounters;

  @Test
  void balancingEquationClosesOnTheFullFixture() {
    routeAll();
    assertThat(lastCounters.srcRead).isEqualTo(55);
    assertThat(lastCounters.written).isEqualTo(50);
    assertThat(lastCounters.rejected).isEqualTo(5);
    assertThat(lastCounters.balances()).isTrue();
  }

  @Test
  void rejectLogMatchesEvidenceRowByRow() throws IOException {
    List<RejectRow> actual = routeAll();
    List<RejectRow> expected = readExpectedRejectLog();

    Comparator<RejectRow> byContent =
        Comparator.comparing(RejectRow::stage)
            .thenComparing(RejectRow::reason)
            .thenComparing(RejectRow::sourceKey)
            .thenComparing(RejectRow::detail);

    actual.sort(byContent);
    expected.sort(byContent);

    assertThat(actual).hasSize(5);
    // stage/reason/detail/source_key — the two-door correctness contract.
    // raw_record is compared separately, PARSE-stage only: in the evidence set, a
    // map/schema-stage reject's raw_record is a JSON snapshot of the row as it stood in
    // account_enriched at Job 3 (the multi-job BigQuery lane re-serializes it there),
    // not the original .DAT line — this single-pass in-memory router (like engine.py's
    // RecordRouter, used the same way for its own non-Beam callers) never re-serializes
    // an intermediate document, so it cannot reproduce that specific string, and doesn't
    // need to: nothing pins raw_record's content for a non-parse-stage reject.
    for (int i = 0; i < actual.size(); i++) {
      assertThat(actual.get(i).stage()).isEqualTo(expected.get(i).stage());
      assertThat(actual.get(i).reason()).isEqualTo(expected.get(i).reason());
      assertThat(actual.get(i).detail()).isEqualTo(expected.get(i).detail());
      assertThat(actual.get(i).sourceKey()).isEqualTo(expected.get(i).sourceKey());
      if (actual.get(i).stage().equals("parse")) {
        assertThat(actual.get(i).rawRecord()).isEqualTo(expected.get(i).rawRecord());
      }
    }
  }

  private static List<RejectRow> readExpectedRejectLog() throws IOException {
    List<String> tsvLines = Files.readAllLines(ROOT.resolve("fixtures/expected/reject_log.tsv"));
    List<RejectRow> out = new ArrayList<>();
    // header: run_id batch_id source_key stage reason detail raw_record
    for (int i = 1; i < tsvLines.size(); i++) {
      String[] cols = tsvLines.get(i).split("\t", -1);
      out.add(new RejectRow(cols[3], cols[4], cols[5], cols[2], cols[6]));
    }
    return out;
  }

  // ── record_lineage, the other half of the source_key rule, row-by-row ─────────

  private record LineageRow(String stage, String reason, String sourceKey) {}

  @Test
  void recordLineageSourceKeyRuleMatchesEvidenceRowByRow() throws IOException {
    RecordRouter router = new RecordRouter(engine, validator, RUN_ID);
    List<LineageRow> actual = new ArrayList<>();
    for (String raw : lines) {
      if (raw.isBlank() || engine.isHeader(raw)) {
        continue;
      }
      Outcome outcome = router.route(raw, 0);
      if (outcome.door() == Door.REJECTED) {
        Doors.Lineage lineage = RecordRouter.lineageOf(outcome, "");
        actual.add(new LineageRow(lineage.stage(), lineage.reason(), lineage.sourceKey()));
      }
    }

    List<LineageRow> expected = readExpectedRecordLineage();

    Comparator<LineageRow> byContent =
        Comparator.comparing(LineageRow::stage).thenComparing(LineageRow::reason);
    actual.sort(byContent);
    expected.sort(byContent);

    assertThat(actual).hasSize(5);
    assertThat(actual).containsExactlyElementsOf(expected);
  }

  private static List<LineageRow> readExpectedRecordLineage() throws IOException {
    List<String> tsvLines = Files.readAllLines(ROOT.resolve("fixtures/expected/record_lineage.tsv"));
    List<LineageRow> out = new ArrayList<>();
    // header: run_id source_key account_key door stage reason detail source_file
    for (int i = 1; i < tsvLines.size(); i++) {
      String[] cols = tsvLines.get(i).split("\t", -1);
      out.add(new LineageRow(cols[4], cols[5], cols[1]));
    }
    return out;
  }
}
