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
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Acceptance test: chains IntakeJob -&gt; CurateEnrichJob -&gt; ProduceJob for
 * real, and checks the complete {@code reject_log}  plus {@code account_target}  against {@code fixtures/expected/*.tsv}. Also closes
 *b: the map/schema-stage {@code raw_record} (a snapshot of the enriched row, not the
 * original line) is now compared, not excluded.
 *
 * <p>Skipped (not failed) when {@code podman} is not on PATH.
 */
class ProduceJobIT {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String CONTAINER = "mig-produce-job-it-postgres";
  private static final int PORT = 15435;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PORT + "/mig";
  private static final String RUN_ID = "run-deepdive-clean-20260907";
  private static final String AS_OF_DATE = "2026-09-07";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static boolean podmanAvailable;

  @BeforeAll
  static void bootstrapLaneUpToProduce() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping live Flink+Postgres IT");

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

    IntakeJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), RUN_ID, JDBC_URL, "mig", "mig", ROOT.toString()
        });
    CurateEnrichJob.main(new String[] {RUN_ID, AS_OF_DATE, JDBC_URL, "mig", "mig", ROOT.toString()});
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
  void produceClosesTheFullRejectLogAndEmitsFiftyValidDocuments() throws Exception {
    ProduceJob.main(new String[] {RUN_ID, JDBC_URL, "mig", "mig"});

    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig")) {
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_transformation.account_target")).isEqualTo(50);
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_recon.reject_log")).isEqualTo(5);
      assertThat(scalarLong(conn, "SELECT count(*) FROM etlr_recon.record_lineage")).isEqualTo(5);

      // The complete reject_log — all 5 stages/reasons/details, literal.
      List<String[]> rows = new ArrayList<>();
      try (Statement st = conn.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT stage, reason, detail, source_key FROM etlr_recon.reject_log ORDER BY stage, reason")) {
        while (rs.next()) {
          rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
        }
      }
      assertThat(rows)
          .extracting(r -> r[0] + "/" + r[1] + ": " + r[2])
          .containsExactlyInAnyOrder(
              "parse/PARSE_BAD_COLUMN_COUNT: CURRENCY needs column 4, row has 4",
              "parse/PARSE_BAD_DATE: OPEN_DATE='20191345'",
              "parse/PARSE_BAD_NUMERIC: BALANCE='+0000000ABCD.56'",
              "map/MAP_UNMAPPED_ENUM_VALUE: status: 'X' not in ['A', 'C', 'D']",
              "schema/SCHEMA_INVALID: accountId: 'acc-bad!0001' does not match '^[A-Z0-9]+$'");

      // third rule : reject_log.source_key is account_id at map/schema,
      // empty string at parse — never `raw:<hash>` (that belongs to record_lineage only).
      for (String[] r : rows) {
        if (r[0].equals("parse")) {
          assertThat(r[3]).isEmpty();
        } else {
          assertThat(r[3]).isNotEmpty().doesNotStartWith("raw:");
        }
      }

      //b: raw_record for the map-stage reject is the enriched-row JSON snapshot —
      // compared structurally (parsed tree), not as a string (key order/spacing differ).
      String actualRawRecord =
          scalarString(conn, "SELECT raw_record FROM etlr_recon.reject_log WHERE stage='map'");
      JsonNode actual = MAPPER.readTree(actualRawRecord);
      JsonNode expected =
          MAPPER.readTree(
              "{\"account_age_years\": 26, \"account_id\": \"ACC000900003\", \"account_key\": "
                  + "\"f3895bd9ae16e8e2626b578dff6f434b\", \"balance_amount\": \"393458.24\", "
                  + "\"balance_band\": \"HIGH\", \"balance_minor\": 39345824, \"branch\": null, "
                  + "\"branch_city\": null, \"branch_name\": null, \"branch_region\": null, "
                  + "\"currency\": \"RON\", \"customer_id\": \"CUS0000003\", \"opened_on\": "
                  + "\"2000-12-18\", \"product_code\": \"CUR001\", \"risk_rating\": null, \"run_id\": "
                  + "\"run-deepdive-clean-20260907\", \"segment\": null, \"status_code\": \"X\"}");
      assertThat(actual).isEqualTo(expected);

      //c: doc_json compared as a parsed tree, not a raw string (JSON_OBJECT emits
      // compact JSON in argument order; the Python original emits sorted keys with spaces).
      String actualDoc =
          scalarString(
              conn, "SELECT doc_json FROM etlr_transformation.account_target WHERE account_id='ACC000000011'");
      JsonNode expectedDoc =
          MAPPER.readTree(
              "{\"accountId\": \"ACC000000011\", \"balance\": {\"amount\": \"300299.47\", "
                  + "\"currency\": \"USD\"}, \"currency\": \"USD\", \"customerId\": \"CUS0000011\", "
                  + "\"migration\": {\"dedupKey\": \"533a169b5baf2224fcd041bdf4f9e3c6\", \"runId\": "
                  + "\"run-deepdive-clean-20260907\", \"sourceKey\": \"ACC000000011\"}, \"openedOn\": "
                  + "\"2008-09-20\", \"productCode\": \"DEP002\", \"status\": \"DORMANT\"}");
      assertThat(MAPPER.readTree(actualDoc)).isEqualTo(expectedDoc);
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
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }
}
