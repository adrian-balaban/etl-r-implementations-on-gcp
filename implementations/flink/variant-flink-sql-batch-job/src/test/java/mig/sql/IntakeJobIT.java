package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test: runs IntakeJob for real — a live Flink batch execution
 * (embedded mini cluster, {@code RuntimeExecutionMode.BATCH}) writing through
 * {@code flink-connector-jdbc-postgres} into a real Postgres started via podman — and checks
 * the exact numbers the plan pins: {@code written=52}, 3 parse-stage rejects with the literal
 * detail strings, and the two derived keys from
 *
 * <p>Skipped (not failed) when {@code podman} is not on PATH, so the rest of the suite stays
 * green in an environment without container support.
 */
class IntakeJobIT {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String CONTAINER = "mig-intake-job-it-postgres";
  private static final int PORT = 15433;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PORT + "/mig";
  private static boolean podmanAvailable;

  @BeforeAll
  static void startPostgres() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping live Flink+Postgres IT");

    run("podman", "rm", "-f", CONTAINER); // best-effort cleanup from a previous failed run
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
  void intakeJobMatchesTheDocumentedNumbersOnARealFlinkBatchRun() throws Exception {
    IntakeJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(),
          "run-deepdive-clean-20260907",
          JDBC_URL,
          "mig",
          "mig",
          ROOT.toString()
        });

    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_extraction.account_src")).isEqualTo(52);
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_recon.reject_log")).isEqualTo(3);
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_recon.record_lineage")).isEqualTo(3);

      // The oracle's parser strips every field, and the source pads CLIENT_TYPE to 4 chars
      // ('SME '). account_src is the extraction layer's own record of what parsed, so it must
      // hold the stripped value — found by the first row-by-row comparison of that table
      // against the oracle, 26 of 52 rows.
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_extraction.account_src WHERE client_type <> TRIM(client_type)"))
          .as("account_src values carry no padding — the oracle strips every field")
          .isZero();
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_extraction.account_src WHERE client_type = 'SME'"))
          .as("the padded 'SME ' records land as 'SME'")
          .isEqualTo(26);

      List<String> details = new ArrayList<>();
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("SELECT detail FROM etlr_recon.reject_log ORDER BY detail")) {
        while (rs.next()) {
          details.add(rs.getString(1));
        }
      }
      assertThat(details)
          .containsExactlyInAnyOrder(
              "CURRENCY needs column 4, row has 4",
              "OPEN_DATE='20191345'",
              "BALANCE='+0000000ABCD.56'");

      // sha256(truncated row)[:16]; sha256("ACC000900003")[:32].
      assertThat(scalarString(conn, "SELECT source_key FROM etlr_recon.record_lineage WHERE source_key LIKE 'raw:4d5d%'"))
          .isEqualTo("raw:4d5d9d96692584fa");
      assertThat(
              scalarString(
                  conn,
                  "SELECT _account_key FROM etlr_extraction.account_src WHERE acct_id='ACC000900003'"))
          .isEqualTo("f3895bd9ae16e8e2626b578dff6f434b");
    }
  }

  private static long scalarLong(Connection conn, String sql) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String scalarString(Connection conn, String sql) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
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
    p.getInputStream().readAllBytes(); // drain, avoid deadlock
    return p.waitFor();
  }
}
