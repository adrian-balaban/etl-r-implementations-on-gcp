package mig.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import java.util.List;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ReconService}'s balancing equation. {@link ReconService#checkBalance}
 * and the {@code run_ledger} write only need Postgres, so this test drives them directly
 * against a real (podman) instance, same pattern as {@code IntakeJobIT} — skips cleanly
 * when {@code podman} is not on PATH.
 */
class ReconServiceTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String CONTAINER = "mig-recon-service-test-postgres";
  private static final int PORT = 15434;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PORT + "/mig";
  private static boolean podmanAvailable;

  @BeforeAll
  static void startPostgres() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping ReconService Postgres test");

    run("podman", "rm", "-f", CONTAINER);
    run(
        "podman", "run", "--rm", "-d", "--name", CONTAINER,
        "-e", "POSTGRES_USER=mig", "-e", "POSTGRES_PASSWORD=mig", "-e", "POSTGRES_DB=mig",
        "-p", PORT + ":5432", "docker.io/library/postgres:16");

    boolean ready = false;
    for (int i = 0; i < 30 && !ready; i++) {
      ready = run("podman", "exec", CONTAINER, "pg_isready", "-U", "mig", "-d", "mig") == 0;
      if (!ready) {
        Thread.sleep(1000);
      }
    }
    assertThat(ready).as("postgres became ready").isTrue();

    String ddl = Files.readString(ROOT.resolve("sql/postgres-init.sql"));
    Path ddlOnHost = Files.createTempFile("postgres-init", ".sql");
    Files.writeString(ddlOnHost, ddl);
    run("podman", "cp", ddlOnHost.toString(), CONTAINER + ":/tmp/init.sql");
    int rc = run("podman", "exec", CONTAINER, "psql", "-U", "mig", "-d", "mig", "-f", "/tmp/init.sql");
    assertThat(rc).as("schema DDL applied").isEqualTo(0);
  }

  @AfterAll
  static void stopPostgres() {
    if (podmanAvailable) {
      try {
        run("podman", "rm", "-f", CONTAINER);
      } catch (Exception ignored) {
        // best-effort teardown
      }
    }
  }

  @Test
  void balancedWhenSrcReadEqualsWrittenPlusRejected() throws Exception {
    String runId = "run-recon-balanced";
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      insertAccountTarget(conn, runId, 3);
      insertRejectLog(conn, runId, 2);

      ReconService.BalanceResult result = ReconService.checkBalance(conn, runId, 5);

      assertThat(result.srcRead()).isEqualTo(5);
      assertThat(result.written()).isEqualTo(3);
      assertThat(result.rejected()).isEqualTo(2);
      assertThat(result.balanced()).isTrue();
    }
  }

  @Test
  void notBalancedWhenCountsDoNotAddUp() throws Exception {
    String runId = "run-recon-unbalanced";
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      insertAccountTarget(conn, runId, 3);
      insertRejectLog(conn, runId, 1); // 3 + 1 = 4, not 5

      ReconService.BalanceResult result = ReconService.checkBalance(conn, runId, 5);

      assertThat(result.balanced()).isFalse();
    }
  }

  @Test
  void checkBalanceScopesCountsToTheGivenRunId() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      insertAccountTarget(conn, "run-recon-other", 10);
      insertRejectLog(conn, "run-recon-other", 10);

      String runId = "run-recon-scoped";
      insertAccountTarget(conn, runId, 1);
      insertRejectLog(conn, runId, 1);

      ReconService.BalanceResult result = ReconService.checkBalance(conn, runId, 2);

      assertThat(result.written()).isEqualTo(1);
      assertThat(result.rejected()).isEqualTo(1);
      assertThat(result.balanced()).isTrue();
    }
  }

  private static void insertAccountTarget(Connection conn, String runId, int count) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO etlr_transformation.account_target"
                + "(account_key, account_id, run_id, doc_json) VALUES (?, ?, ?, '{}')")) {
      for (int i = 0; i < count; i++) {
        ps.setString(1, runId + "-key-" + i);
        ps.setString(2, runId + "-acct-" + i);
        ps.setString(3, runId);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void insertRejectLog(Connection conn, String runId, int count) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO etlr_recon.reject_log"
                + "(run_id, batch_id, source_key, stage, reason, detail, raw_record) "
                + "VALUES (?, ?, '', 'parse', 'PARSE_BAD_DATE', 'test', '{}')")) {
      for (int i = 0; i < count; i++) {
        ps.setString(1, runId);
        ps.setLong(2, i);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static boolean commandExists(String cmd) throws Exception {
    return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
  }

  private static int run(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  /**
   *  batch checks, at sizes the 55-record fixture cannot reach. 50
   * documents is one batch, so the fixture proves nothing here — these three cases are where
   * the arithmetic could actually be wrong: exactly one full batch, one full batch plus a
   * remainder of one, and several full batches plus a partial.
   */
  @Test
  void batchAccountingMatchesTheOriginalAtSizesTheFixtureCannotReach() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      insertAccountTarget(conn, "run-batch-200", 200);
      ReconService.BatchResult exactlyOneFull = ReconService.checkBatches(conn, "run-batch-200");
      assertThat(exactlyOneFull.written()).isEqualTo(200);
      assertThat(exactlyOneFull.batches()).isEqualTo(1);
      assertThat(exactlyOneFull.lastBatchSize()).isEqualTo(200);
      assertThat(exactlyOneFull.consistent()).isTrue();

      insertAccountTarget(conn, "run-batch-201", 201);
      ReconService.BatchResult remainderOfOne = ReconService.checkBatches(conn, "run-batch-201");
      assertThat(remainderOfOne.batches()).as("ceil(201/200)").isEqualTo(2);
      assertThat(remainderOfOne.lastBatchSize()).isEqualTo(1);
      assertThat(remainderOfOne.consistent()).isTrue();

      insertAccountTarget(conn, "run-batch-450", 450);
      ReconService.BatchResult severalPlusPartial = ReconService.checkBatches(conn, "run-batch-450");
      assertThat(severalPlusPartial.written()).isEqualTo(450);
      assertThat(severalPlusPartial.batches()).isEqualTo(3);
      assertThat(severalPlusPartial.lastBatchSize()).isEqualTo(50);
      assertThat(severalPlusPartial.consistent()).isTrue();
    }
  }

  /** The fixture's own shape, stated rather than implied: 50 documents is a single batch. */
  @Test
  void theFixtureSizedRunIsOneBatch() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      insertAccountTarget(conn, "run-batch-50", 50);
      ReconService.BatchResult result = ReconService.checkBatches(conn, "run-batch-50");
      assertThat(result.batches()).isEqualTo(1);
      assertThat(result.lastBatchSize()).isEqualTo(50);
      assertThat(result.consistent()).isTrue();
    }
  }

  // ── per-stage equations ───────────────────────────────────────────

  /** A consistent SQL-variant run: 55 read = 52 into account_src + 3 parse rejects; 50 written + 2 map/schema rejects. */
  private void seedConsistentLane(Connection conn, String runId) throws Exception {
    seedLane(conn, runId, 52, 52, 52, 50, 3, 2);
  }

  private void seedLane(Connection conn, String runId, int src, int curated, int enriched, int target, int parseRejects, int laterRejects)
      throws Exception {
    try (var st = conn.createStatement()) {
      st.executeUpdate(
          "INSERT INTO etlr_extraction.account_src(acct_id, cust_id, client_type, product_code, currency, open_date, status, balance, _run_id) "
              + "SELECT 'a'||g, 'c', 't', 'p', 'RON', DATE '2020-01-01', 'A', '1.00', '" + runId + "' FROM generate_series(1," + src + ") g");
      String cols =
          "(account_id, customer_id, product_code, currency, opened_on, status_code, balance_minor, balance_amount, balance_band, account_age_years, run_id, account_key)";
      String vals = "'a'||g, 'c', 'p', 'RON', DATE '2020-01-01', 'A', 100, '1.00', 'LOW', 6, '" + runId + "', 'k'||g";
      st.executeUpdate("INSERT INTO etlr_transformation.account_curated" + cols + " SELECT " + vals + " FROM generate_series(1," + curated + ") g");
      st.executeUpdate("INSERT INTO etlr_transformation.account_enriched" + cols + " SELECT " + vals + " FROM generate_series(1," + enriched + ") g");
      for (int i = 0; i < parseRejects; i++) {
        st.executeUpdate("INSERT INTO etlr_recon.reject_log VALUES ('" + runId + "', 0, '', 'parse', 'PARSE_BAD_DATE', 'd', '{}')");
      }
      for (int i = 0; i < laterRejects; i++) {
        st.executeUpdate("INSERT INTO etlr_recon.reject_log VALUES ('" + runId + "', 0, 'x', 'map', 'MAP_UNMAPPED_ENUM_VALUE', 'd', '{}')");
      }
    }
    insertAccountTarget(conn, runId, target);
  }

  private static Map<String, Boolean> holdsByStage(List<ReconService.StageCheck> checks) {
    Map<String, Boolean> out = new java.util.LinkedHashMap<>();
    checks.forEach(c -> out.put(c.stage(), c.holds()));
    return out;
  }

  @Test
  void everyStageEquationClosesOnAConsistentRun() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      seedConsistentLane(conn, "run-stages-ok");
      List<ReconService.StageCheck> checks = ReconService.checkStages(conn, "run-stages-ok", 55);

      assertThat(holdsByStage(checks)).containsExactly(
          Map.entry("intake", true), Map.entry("curate", true), Map.entry("enrich", true), Map.entry("produce", true));
    }
  }

  /**
   * The hole this closes: a row lost in curate that a later stage happens to compensate for. The
   * end-to-end equation cannot see it — it only compares what came in with what came out.
   */
  @Test
  void aRowLostInCurateIsCaughtEvenThoughTheEndToEndEquationStillBalances() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      // curate lost one row (51 of 52); enrich and produce then carry 51 -> 49 written + 2 rejected;
      // add one extra target row so the END-TO-END totals still read 50 + 5 = 55.
      seedLane(conn, "run-stages-lost", 52, 51, 51, 50, 3, 2);

      assertThat(ReconService.checkBalance(conn, "run-stages-lost", 55).balanced())
          .as("the end-to-end equation is satisfied — this is the blind spot")
          .isTrue();

      Map<String, Boolean> stages = holdsByStage(ReconService.checkStages(conn, "run-stages-lost", 55));
      assertThat(stages.get("curate")).as("but curate's own equation is broken").isFalse();
      assertThat(stages.get("produce")).as("and so is produce's: 50 + 2 != 51").isFalse();
    }
  }

  @Test
  void aDuplicateInEnrichIsCaught() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      seedLane(conn, "run-stages-dup", 52, 52, 53, 50, 3, 2); // enrich wrote a row twice

      assertThat(holdsByStage(ReconService.checkStages(conn, "run-stages-dup", 55)).get("enrich")).isFalse();
    }
  }

  @Test
  void aSourceCountThatDoesNotMatchWhatWasReadIsCaughtAtIntake() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      seedConsistentLane(conn, "run-stages-intake");
      // the producer says 56 data records but intake accounted for only 52 + 3
      assertThat(holdsByStage(ReconService.checkStages(conn, "run-stages-intake", 56)).get("intake")).isFalse();
    }
  }

  /** The DataStream variant never materialises the intermediate tables — nothing to check, and no false alarm. */
  @Test
  void aSinglePassRunWithNoAccountSrcRowsHasNoStageChecksAndNoFalseFailure() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      insertAccountTarget(conn, "run-stages-ds", 50);
      insertRejectLog(conn, "run-stages-ds", 5);

      assertThat(ReconService.checkStages(conn, "run-stages-ds", 55)).isEmpty();
    }
  }
}
