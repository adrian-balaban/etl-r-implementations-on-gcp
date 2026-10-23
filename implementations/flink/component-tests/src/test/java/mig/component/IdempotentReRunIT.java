package mig.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.datastream.DataStreamJob;
import mig.sql.CurateEnrichJob;
import mig.sql.IntakeJob;
import mig.sql.ProduceJob;

/**
 *  — a re-run of the same run_id must not duplicate rows. Latent as long
 * as every test runs a job exactly once; on Cloud Composer a {@code KubernetesPodOperator}
 * retries a failed task, which resubmits the same job on the same run_id. Runs each variant
 * TWICE on the same run_id and checks row counts are unchanged the second time.
 *
 * <p>Skipped (not failed) when {@code podman} is not on PATH.
 */
class IdempotentReRunIT {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String CONTAINER = "mig-idempotent-rerun-it-postgres";
  private static final int PORT = 15438;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PORT + "/mig";
  private static boolean podmanAvailable;

  @BeforeAll
  static void startPostgres() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping live idempotency IT");

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

    Path ddlOnHost = Files.createTempFile("postgres-init", ".sql");
    Files.writeString(ddlOnHost, Files.readString(ROOT.resolve("sql/postgres-init.sql")));
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
  void sqlVariantIsIdempotentAcrossARetry() throws Exception {
    String runId = "run-idempotent-sql";
    runSqlLane(runId);
    assertRowCounts(runId, 5, 50, 5);

    runSqlLane(runId); // the "retry": same run_id, same input, run again
    assertRowCounts(runId, 5, 50, 5);
  }

  @Test
  void dataStreamVariantIsIdempotentAcrossARetry() throws Exception {
    String runId = "run-idempotent-datastream";
    DataStreamJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), runId, JDBC_URL, "mig", "mig", ROOT.toString()
        });
    assertRowCounts(runId, 5, 50, 5);

    DataStreamJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), runId, JDBC_URL, "mig", "mig", ROOT.toString()
        });
    assertRowCounts(runId, 5, 50, 5);
  }

  private static void runSqlLane(String runId) throws Exception {
    IntakeJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), runId, JDBC_URL, "mig", "mig", ROOT.toString()
        });
    CurateEnrichJob.main(new String[] {runId, "2026-09-07", JDBC_URL, "mig", "mig", ROOT.toString()});
    ProduceJob.main(new String[] {runId, JDBC_URL, "mig", "mig"});
  }

  private static void assertRowCounts(String runId, long rejects, long targets, long lineage) throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_recon.reject_log WHERE run_id='" + runId + "'"))
          .as("reject_log rows for " + runId)
          .isEqualTo(rejects);
      assertThat(
              scalarLong(
                  conn, "SELECT count(*) FROM etlr_transformation.account_target WHERE run_id='" + runId + "'"))
          .as("account_target rows for " + runId)
          .isEqualTo(targets);
      assertThat(
              scalarLong(conn, "SELECT count(*) FROM etlr_recon.record_lineage WHERE run_id='" + runId + "'"))
          .as("record_lineage rows for " + runId)
          .isEqualTo(lineage);
    }
  }

  private static long scalarLong(Connection conn, String sql) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static boolean commandExists(String cmd) {
    try {
      return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static int run(String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }
}
