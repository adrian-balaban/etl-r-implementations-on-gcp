package mig.jdbclane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.gatejdbc.GateJdbc;
import mig.gatejdbc.ReconJdbc;
import mig.sql.JdbcIntakeJob;

/** The case the key-only checksum missed: a non-key value changes between the gate and intake. */
class SourceDriftIT {

  private static final String SRC = "etlr-drift-it-source";
  private static final String LANE = "etlr-drift-it-lane";
  private static final String SRC_URL = Podman.jdbcUrl(15456, "source");
  private static final String LANE_URL = Podman.jdbcUrl(15457, "mig");
  private static boolean podman;

  @BeforeAll
  static void start() throws Exception {
    podman = Podman.available();
    assumeTrue(podman, "podman not on PATH — skipping source-drift IT");
    Podman.startSource(SRC, 15456);
    Podman.startLane(LANE, 15457);
  }

  @AfterAll
  static void stop() {
    if (podman) {
      Podman.remove(SRC, LANE);
    }
  }

  @Test
  void aBalanceChangedAfterTheGateFailsReconJdbc() throws Exception {
    String run = "run-drift";
    Podman.loadFixture(SRC, run);
    try (Connection src = DriverManager.getConnection(SRC_URL, Podman.READER, Podman.READER_PASSWORD)) {
      assertThat(GateJdbc.check(src, run).outcome()).isEqualTo(GateJdbc.Outcome.PASSED);
    }
    Podman.psql(SRC, "source", "UPDATE etlr_source.account SET balance = balance + 1 WHERE acct_id = 'ACC000000011';");
    JdbcIntakeJob.main(new String[] {
        run, SRC_URL, Podman.READER, Podman.READER_PASSWORD, LANE_URL, "mig", "mig", Podman.ROOT.toString()});
    try (Connection src = DriverManager.getConnection(SRC_URL, Podman.READER, Podman.READER_PASSWORD);
        Connection lane = DriverManager.getConnection(LANE_URL, "mig", "mig")) {
      ReconJdbc.Result r = ReconJdbc.compare(src, lane, run);
      assertThat(r.ok()).isFalse();
      assertThat(r.problems()).anyMatch(p -> p.startsWith("content digest")).noneMatch(p -> p.startsWith("row count"));
    }
    Process cli = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
        System.getProperty("java.class.path"), "mig.gatejdbc.ReconJdbc", run, SRC_URL, Podman.READER,
        Podman.READER_PASSWORD, LANE_URL, "mig", "mig").redirectErrorStream(true).start();
    cli.getInputStream().readAllBytes();
    assertThat(cli.waitFor()).as("recon-jdbc exit code on drift").isEqualTo(4);
  }
}
