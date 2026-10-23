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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.datastream.DataStreamJob;
import mig.sql.CurateEnrichJob;
import mig.sql.IntakeJob;
import mig.sql.ProduceJob;

/**
 * The parity test that decides, run three times: SQL vs. evidence,
 * DataStream vs. evidence, SQL vs. DataStream. Runs both variants for real against one live
 * Postgres (podman), each under its own {@code run_id}, and compares {@code reject_log} rows
 * (stage/reason/detail/source_key) across all three pairs.
 *
 * <p>This is only informative if the two variants are genuinely independent
 * implementations. The SQL variant's predicates are hand-written SQL (10/20/30-*.sql.template);
 * the DataStream variant calls {@link mig.common.RecordRouter} directly. Neither reaches into
 * the other, so a real divergence between them is possible here — not tautological.
 *
 * <p>Skipped (not failed) when {@code podman} is not on PATH.
 */
class ThreeWayParityIT {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String CONTAINER = "mig-threeway-parity-it-postgres";
  private static final int PORT = 15436;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PORT + "/mig";
  private static final String SQL_RUN_ID = "run-parity-sql";
  private static final String DATASTREAM_RUN_ID = "run-parity-datastream";
  private static final String AS_OF_DATE = "2026-09-07";
  private static boolean podmanAvailable;

  private record RejectRow(String stage, String reason, String detail, String sourceKey) {}

  @BeforeAll
  static void runBothVariants() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping live three-way parity IT");

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

    // SQL variant — the three jobs, one run_id.
    IntakeJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), SQL_RUN_ID, JDBC_URL, "mig", "mig", ROOT.toString()
        });
    CurateEnrichJob.main(new String[] {SQL_RUN_ID, AS_OF_DATE, JDBC_URL, "mig", "mig", ROOT.toString()});
    ProduceJob.main(new String[] {SQL_RUN_ID, JDBC_URL, "mig", "mig"});

    // DataStream variant — one job, a different run_id, same Postgres.
    DataStreamJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), DATASTREAM_RUN_ID, JDBC_URL, "mig", "mig", ROOT.toString()
        });
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
  void sqlVariantMatchesEvidence() throws Exception {
    assertRejectLogMatchesEvidence(rejectLogFor(SQL_RUN_ID));
  }

  @Test
  void dataStreamVariantMatchesEvidence() throws Exception {
    assertRejectLogMatchesEvidence(rejectLogFor(DATASTREAM_RUN_ID));
  }

  @Test
  void sqlVariantMatchesDataStreamVariant() throws Exception {
    List<RejectRow> sql = sortedByContent(rejectLogFor(SQL_RUN_ID));
    List<RejectRow> dataStream = sortedByContent(rejectLogFor(DATASTREAM_RUN_ID));
    assertThat(sql).containsExactlyElementsOf(dataStream);
  }

  /**
   * The second table, for all three pairs — {@code record_lineage} row by row, which is
   * where the OTHER half of the source_key rule lives ({@code raw:&lt;hash&gt;} at
   * parse, {@code account_id} at map/schema — the exact opposite of reject_log's rule at
   * parse). The earlier cut of this test flagged its absence; without it the three-way comparison
   * covered one of the two tables.
   *
   * <p>{@code source_file} is excluded deliberately and is the only exclusion: the evidence
   * says {@code bq_transformation} (a BigQuery dataset name), this lane says
   * {@code etlr_transformation}. The comparator does not compare it, and the spec says to record the
   * deviation rather than falsify it — so it is recorded here, not quietly dropped.
   */
  @Test
  void recordLineageMatchesAcrossAllThreePairs() throws Exception {
    List<LineageRow> expected =
        List.of(
            new LineageRow("raw:4d5d9d96692584fa", "", "rejected", "parse", "PARSE_BAD_COLUMN_COUNT",
                "CURRENCY needs column 4, row has 4"),
            new LineageRow("raw:8e81e78cbd3f33ee", "", "rejected", "parse", "PARSE_BAD_DATE",
                "OPEN_DATE='20191345'"),
            new LineageRow("raw:4e22eef52fbd1338", "", "rejected", "parse", "PARSE_BAD_NUMERIC",
                "BALANCE='+0000000ABCD.56'"),
            new LineageRow("ACC000900003", "f3895bd9ae16e8e2626b578dff6f434b", "rejected", "map",
                "MAP_UNMAPPED_ENUM_VALUE", "status: 'X' not in ['A', 'C', 'D']"),
            new LineageRow("acc-bad!0001", "5551f11728f2bdd063674462659e002a", "rejected", "schema",
                "SCHEMA_INVALID", "accountId: 'acc-bad!0001' does not match '^[A-Z0-9]+$'"));

    List<LineageRow> sql = sortedLineage(lineageFor(SQL_RUN_ID));
    List<LineageRow> dataStream = sortedLineage(lineageFor(DATASTREAM_RUN_ID));
    assertThat(sql).as("SQL vs. evidence").containsExactlyElementsOf(sortedLineage(expected));
    assertThat(dataStream).as("DataStream vs. evidence").containsExactlyElementsOf(sortedLineage(expected));
    assertThat(sql).as("SQL vs. DataStream").containsExactlyElementsOf(dataStream);
  }

  /**
   * The fifth column, {@code raw_record} — and the one place the two variants genuinely
   * disagree, asserted rather than excluded.
   *
   * <p>The spec said the parity test's {@code raw_record} exemption should be
   * lifted once the produce job landed. Lifting it wholesale would have been wrong, and this test is
   * why: for a PARSE-stage reject both variants write the original {@code .DAT} line, so
   * that column is compared across all three pairs. For a MAP/SCHEMA-stage reject the
   * oracle writes {@code json.dumps(enriched_row, sort_keys=True)}, and only the SQL variant
   * can reproduce it — it reads {@code account_enriched}, so the row exists to serialise
   * ({@code ProduceJobIT} compares it against the evidence as a parsed JSON tree). The
   * DataStream variant routes parse -> map -> schema in ONE pass from the raw line and never
   * materialises an enriched row, so it writes the raw line there instead.
   *
   * <p>That is a real, permanent divergence of the single-pass design, not an oversight, and
   * an exclusion would have hidden it. Asserting it means the day someone changes either
   * side, this test says so.
   */
  @Test
  void rawRecordAgreesAtParseAndDivergesKnowinglyAtMapAndSchema() throws Exception {
    Map<String, String> sql = rawRecordByStageAndKey(SQL_RUN_ID);
    Map<String, String> dataStream = rawRecordByStageAndKey(DATASTREAM_RUN_ID);

    // Parse stage: identical in both variants AND identical to the evidence's raw lines.
    assertThat(sql.get("parse|")).isEqualTo(dataStream.get("parse|"));
    assertThat(sql)
        .as("parse-stage raw_record is the original .DAT line, both variants")
        .containsEntry("parse|PARSE_BAD_COLUMN_COUNT", "ACC000900000|CUS0000000|RETL|CUR001")
        .containsEntry(
            "parse|PARSE_BAD_NUMERIC", "ACC000900001|CUS0000001|RETL|LON003|EUR|20050725|A|+0000000ABCD.56")
        .containsEntry(
            "parse|PARSE_BAD_DATE", "ACC000900002|CUS0000002|RETL|LON003|RON|20191345|D|+00000250092.20");
    assertThat(dataStream)
        .containsEntry("parse|PARSE_BAD_COLUMN_COUNT", "ACC000900000|CUS0000000|RETL|CUR001")
        .containsEntry(
            "parse|PARSE_BAD_NUMERIC", "ACC000900001|CUS0000001|RETL|LON003|EUR|20050725|A|+0000000ABCD.56")
        .containsEntry(
            "parse|PARSE_BAD_DATE", "ACC000900002|CUS0000002|RETL|LON003|RON|20191345|D|+00000250092.20");

    // Map/schema stage: the SQL variant reproduces the oracle's enriched-row JSON snapshot.
    for (String key : List.of("map|MAP_UNMAPPED_ENUM_VALUE", "schema|SCHEMA_INVALID")) {
      assertThat(sql.get(key))
          .as("SQL variant's %s raw_record is the enriched-row JSON snapshot", key)
          .startsWith("{")
          .contains("\"balance_band\"")
          .contains("\"account_age_years\"");
      assertThat(dataStream.get(key))
          .as("DataStream variant's %s raw_record is the original .DAT line (single pass)", key)
          .doesNotStartWith("{")
          .contains("|");
    }
  }

  private static void assertRejectLogMatchesEvidence(List<RejectRow> actual) {
    List<RejectRow> expected =
        List.of(
            new RejectRow("parse", "PARSE_BAD_COLUMN_COUNT", "CURRENCY needs column 4, row has 4", ""),
            new RejectRow("parse", "PARSE_BAD_DATE", "OPEN_DATE='20191345'", ""),
            new RejectRow("parse", "PARSE_BAD_NUMERIC", "BALANCE='+0000000ABCD.56'", ""),
            new RejectRow("map", "MAP_UNMAPPED_ENUM_VALUE", "status: 'X' not in ['A', 'C', 'D']", "ACC000900003"),
            new RejectRow(
                "schema",
                "SCHEMA_INVALID",
                "accountId: 'acc-bad!0001' does not match '^[A-Z0-9]+$'",
                "acc-bad!0001"));
    assertThat(sortedByContent(actual)).containsExactlyElementsOf(sortedByContent(expected));
  }

  private record LineageRow(
      String sourceKey, String accountKey, String door, String stage, String reason, String detail) {}

  private static List<LineageRow> sortedLineage(List<LineageRow> rows) {
    List<LineageRow> sorted = new ArrayList<>(rows);
    sorted.sort(
        java.util.Comparator.comparing(LineageRow::stage)
            .thenComparing(LineageRow::reason)
            .thenComparing(LineageRow::sourceKey));
    return sorted;
  }

  private static List<LineageRow> lineageFor(String runId) throws Exception {
    List<LineageRow> rows = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig");
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT source_key, account_key, door, stage, reason, detail FROM etlr_recon.record_lineage"
                    + " WHERE run_id = '" + runId + "'")) {
      while (rs.next()) {
        rows.add(
            new LineageRow(
                nullToEmpty(rs.getString(1)), nullToEmpty(rs.getString(2)), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)));
      }
    }
    return rows;
  }

  private static Map<String, String> rawRecordByStageAndKey(String runId) throws Exception {
    Map<String, String> out = new java.util.HashMap<>();
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig");
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT stage, reason, raw_record FROM etlr_recon.reject_log WHERE run_id = '" + runId + "'")) {
      while (rs.next()) {
        out.put(rs.getString(1) + "|" + rs.getString(2), nullToEmpty(rs.getString(3)));
      }
    }
    return out;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static List<RejectRow> sortedByContent(List<RejectRow> rows) {
    List<RejectRow> sorted = new ArrayList<>(rows);
    sorted.sort(
        java.util.Comparator.comparing(RejectRow::stage)
            .thenComparing(RejectRow::reason)
            .thenComparing(RejectRow::sourceKey));
    return sorted;
  }

  private static List<RejectRow> rejectLogFor(String runId) throws Exception {
    List<RejectRow> rows = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(JDBC_URL, "mig", "mig");
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT stage, reason, detail, source_key FROM etlr_recon.reject_log WHERE run_id = '"
                    + runId + "'")) {
      while (rs.next()) {
        rows.add(new RejectRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
      }
    }
    return rows;
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
