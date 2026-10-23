package mig.jdbclane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Spec failure table: refused and failed gates write zero rows — checked across every lane table. */
class GateLeavesTheLaneEmptyIT {

  private static final String SRC = "etlr-gate-empty-it-source";
  private static final String LANE = "etlr-gate-empty-it-lane";
  private static final String SRC_URL = Podman.jdbcUrl(15460, "source");
  private static final String LANE_URL = Podman.jdbcUrl(15461, "mig");
  private static final List<String> LANE_TABLES = List.of(
      "etlr_extraction.account_src", "etlr_transformation.account_curated", "etlr_transformation.account_enriched",
      "etlr_transformation.account_target", "etlr_recon.reject_log", "etlr_recon.record_lineage",
      "etlr_recon.run_ledger", "etlr_recon.source_read");
  private static boolean podman;

  @BeforeAll
  static void start() throws Exception {
    podman = Podman.available();
    assumeTrue(podman, "podman not on PATH — skipping GateLeavesTheLaneEmptyIT");
    Podman.startSource(SRC, 15460);
    Podman.startLane(LANE, 15461);
  }

  @AfterAll
  static void stop() {
    if (podman) {
      Podman.remove(SRC, LANE);
    }
  }

  private static int gate(String runId) throws Exception {
    String java = System.getProperty("java.home") + "/bin/java";
    Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "mig.gatejdbc.GateJdbc",
        runId, SRC_URL, Podman.READER, Podman.READER_PASSWORD).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  private static long laneRows() throws Exception {
    long total = 0;
    try (Connection c = DriverManager.getConnection(LANE_URL, "mig", "mig"); Statement st = c.createStatement()) {
      for (String t : LANE_TABLES) {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + t)) {
          rs.next();
          total += rs.getLong(1);
        }
      }
    }
    return total;
  }

  @Test
  void aRefusedGateExitsThreeAndTheLaneStaysEmpty() throws Exception {
    Podman.loadFixture(SRC, "empty-other");
    assertThat(gate("empty-none")).isEqualTo(3);
    assertThat(laneRows()).isZero();
  }

  @Test
  void aFailedGateExitsFourAndTheLaneStaysEmpty() throws Exception {
    Podman.loadFixture(SRC, "empty-tamper");
    Podman.psql(SRC, "source", "UPDATE etlr_source.account SET balance = balance + 1 WHERE acct_id = 'ACC000000011';");
    assertThat(gate("empty-tamper")).isEqualTo(4);
    assertThat(laneRows()).isZero();
  }
}
