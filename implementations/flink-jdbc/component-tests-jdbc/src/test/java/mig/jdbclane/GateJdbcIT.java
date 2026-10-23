package mig.jdbclane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.gatejdbc.CanonicalSql;
import mig.gatejdbc.GateJdbc;
import mig.gatejdbc.GateJdbc.Outcome;

class GateJdbcIT {

  private static final String SRC = "etlr-gate-it-source";
  private static final int PORT = 15453;
  private static final String URL = Podman.jdbcUrl(PORT, "source");
  private static boolean podman;

  @BeforeAll
  static void start() throws Exception {
    podman = Podman.available();
    assumeTrue(podman, "podman not on PATH — skipping gate-jdbc IT");
    Podman.startSource(SRC, PORT);
  }

  @AfterAll
  static void stop() {
    if (podman) {
      Podman.remove(SRC);
    }
  }

  private static GateJdbc.Result check(String runId) throws Exception {
    try (Connection c = DriverManager.getConnection(URL, Podman.READER, Podman.READER_PASSWORD)) {
      return GateJdbc.check(c, runId);
    }
  }

  @Test
  void anUnchangedSnapshotPassesWithItsCountAndThePinnedDigest() throws Exception {
    Podman.loadFixture(SRC, "gate-ok");
    GateJdbc.Result r = check("gate-ok");
    assertThat(r.outcome()).isEqualTo(Outcome.PASSED);
    assertThat(r.srcRead()).isEqualTo(53);
    try (Connection c = DriverManager.getConnection(URL, Podman.READER, Podman.READER_PASSWORD);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT content_digest FROM etlr_source.extract_control WHERE run_id = 'gate-ok'")) {
      rs.next();
      assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("31503124396361923406"));
    }
  }

  @Test
  void noControlRowRefuses() throws Exception {
    Podman.loadFixture(SRC, "gate-other");
    assertThat(check("gate-none").outcome()).isEqualTo(Outcome.REFUSED);
  }

  @Test
  void aWrongCountFails() throws Exception {
    Podman.loadFixture(SRC, "gate-count");
    Podman.psql(SRC, "source", "UPDATE etlr_source.extract_control SET row_count = 54 WHERE run_id = 'gate-count';");
    GateJdbc.Result r = check("gate-count");
    assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(r.problems()).anyMatch(p -> p.startsWith("row count"));
  }

  @Test
  void aChangedBalanceWithTheSameCountFailsOnTheDigest() throws Exception {
    Podman.loadFixture(SRC, "gate-balance");
    Podman.psql(SRC, "source", "UPDATE etlr_source.account SET balance = balance + 1 WHERE acct_id = 'ACC000000011';");
    GateJdbc.Result r = check("gate-balance");
    assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(r.problems()).anyMatch(p -> p.startsWith("content digest")).noneMatch(p -> p.startsWith("row count"));
  }

  @Test
  void keysThatCollideAfterTrimFail() throws Exception {
    Podman.loadFixture(SRC, "gate-dup");
    Podman.psql(SRC, "source",
        "INSERT INTO etlr_source.account SELECT 'ACC000000011 ', cust_id, client_type, product_code, currency,"
            + " open_date, status, balance FROM etlr_source.account WHERE acct_id = 'ACC000000011';\n"
            + "UPDATE etlr_source.extract_control SET (row_count, content_digest) = ("
            + CanonicalSql.countAndDigest("etlr_source.account") + ") WHERE run_id = 'gate-dup';");
    GateJdbc.Result r = check("gate-dup");
    assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(r.problems()).containsExactly("duplicate keys after TRIM: [ACC000000011]");
  }

  @Test
  void theCommandLineExitsThreeWhenRefusedAndPrintsSrcReadWhenPassed() throws Exception {
    Podman.loadFixture(SRC, "gate-cli");
    String java = System.getProperty("java.home") + "/bin/java";
    String cp = System.getProperty("java.class.path");
    Process refused = new ProcessBuilder(java, "-cp", cp, "mig.gatejdbc.GateJdbc", "gate-nothing", URL,
        Podman.READER, Podman.READER_PASSWORD).redirectErrorStream(true).start();
    refused.getInputStream().readAllBytes();
    assertThat(refused.waitFor()).isEqualTo(3);
    Process passed = new ProcessBuilder(java, "-cp", cp, "mig.gatejdbc.GateJdbc", "gate-cli", URL,
        Podman.READER, Podman.READER_PASSWORD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    String out = new String(passed.getInputStream().readAllBytes()).trim();
    assertThat(passed.waitFor()).isZero();
    assertThat(List.of(out.split("\n"))).containsExactly("src_read=53");
  }

  @Test
  void discoverPrintsEachVouchedRunWithItsReadyAtAsAUtcInstant() throws Exception {
    Podman.loadFixture(SRC, "gate-disc");
    Podman.psql(SRC, "source",
        "UPDATE etlr_source.extract_control SET ready_at = '2026-09-26T01:30:00+02:00' WHERE run_id = 'gate-disc';");
    String java = System.getProperty("java.home") + "/bin/java";
    String cp = System.getProperty("java.class.path");
    Process p = new ProcessBuilder(java, "-cp", cp, "mig.gatejdbc.GateJdbc", "--discover", URL,
        Podman.READER, Podman.READER_PASSWORD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    List<String> lines = new String(p.getInputStream().readAllBytes()).lines().toList();
    assertThat(p.waitFor()).isZero();
    assertThat(lines).contains("gate-disc\t2026-09-25T23:30:00Z");
    assertThat(lines).allMatch(l -> l.matches("[a-z0-9-]+\t\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
  }
}
