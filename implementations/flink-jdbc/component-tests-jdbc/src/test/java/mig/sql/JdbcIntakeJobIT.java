package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.flink.client.program.StreamContextEnvironment;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.core.execution.DefaultExecutorServiceLoader;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.gatejdbc.ReconJdbc;
import mig.jdbclane.Podman;

class JdbcIntakeJobIT {

  private static final String SRC = "etlr-intake-it-source";
  private static final String LANE = "etlr-intake-it-lane";
  private static final String SRC_URL = Podman.jdbcUrl(15454, "source");
  private static final String LANE_URL = Podman.jdbcUrl(15455, "mig");
  private static final String RUN = "run-intake-jdbc";
  private static final String TRUNCATED = "ACC000900000|CUS0000000|RETL|CUR001||||";
  private static boolean podman;

  @BeforeAll
  static void start() throws Exception {
    podman = Podman.available();
    assumeTrue(podman, "podman not on PATH — skipping JdbcIntakeJob IT");
    Podman.startSource(SRC, 15454);
    Podman.startLane(LANE, 15455);
    Podman.loadFixture(SRC, RUN);
    JdbcIntakeJob.main(args(RUN));
  }

  @AfterAll
  static void stop() {
    if (podman) {
      Podman.remove(SRC, LANE);
    }
  }

  static String[] args(String runId) {
    return new String[] {runId, SRC_URL, Podman.READER, Podman.READER_PASSWORD, LANE_URL, "mig", "mig", Podman.ROOT.toString()};
  }

  private static String one(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(LANE_URL, "mig", "mig");
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as("a row for: " + sql).isTrue();
      return rs.getString(1);
    }
  }

  @Test
  void theIntakeSplitIs52Plus1() throws Exception {
    assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE _run_id = '" + RUN + "'")).isEqualTo("52");
    assertThat(one("SELECT count(*) FROM etlr_recon.reject_log WHERE run_id = '" + RUN + "'")).isEqualTo("1");
  }

  @Test
  void theOneParseRejectIsTheTruncatedRowAsACanonicalLine() throws Exception {
    String where = " FROM etlr_recon.reject_log WHERE run_id = '" + RUN + "'";
    assertThat(one("SELECT stage || '/' || reason || '/' || detail" + where)).isEqualTo("parse/PARSE_MISSING_FIELD/CURRENCY");
    assertThat(one("SELECT raw_record" + where)).isEqualTo(TRUNCATED);
    assertThat(one("SELECT source_key" + where)).isEmpty();
    String lineage = " FROM etlr_recon.record_lineage WHERE run_id = '" + RUN + "'";
    assertThat(one("SELECT source_key" + lineage)).isEqualTo("raw:bf47701944c6abec");
    assertThat(one("SELECT source_file" + lineage)).isEqualTo("etlr_source.account");
  }

  @Test
  void survivorsAreStoredTrimmedAndTyped() throws Exception {
    String run = "_run_id = '" + RUN + "'";
    assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE " + run + " AND client_type = 'SME'")).isEqualTo("26");
    assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE " + run + " AND client_type <> btrim(client_type)")).isEqualTo("0");
    String acc11 = " FROM etlr_extraction.account_src WHERE " + run + " AND acct_id = 'ACC000000011'";
    assertThat(one("SELECT open_date::text" + acc11)).isEqualTo("2008-09-20");
    assertThat(one("SELECT balance" + acc11)).isEqualTo("300299.47");
    assertThat(one("SELECT _source_file" + acc11)).isEqualTo("etlr_source.account");
    assertThat(one("SELECT _account_key" + acc11)).isEqualTo("533a169b5baf2224fcd041bdf4f9e3c6");
  }

  @Test
  void intakeMeasuredWhatTheSourceVouchedFor() throws Exception {
    assertThat(one("SELECT row_count FROM etlr_recon.source_read WHERE run_id = '" + RUN + "'")).isEqualTo("53");
    assertThat(new BigDecimal(one("SELECT content_digest FROM etlr_recon.source_read WHERE run_id = '" + RUN + "'")))
        .isEqualByComparingTo(new BigDecimal("31503124396361923406"));
    try (Connection src = DriverManager.getConnection(SRC_URL, Podman.READER, Podman.READER_PASSWORD);
        Connection lane = DriverManager.getConnection(LANE_URL, "mig", "mig")) {
      assertThat(ReconJdbc.compare(src, lane, RUN).ok()).isTrue();
    }
  }

  @Test
  void theStatementSetReadsTheSourceOnce() {
    StreamTableEnvironment tEnv = StreamTableEnvironment.create(SqlJobSupport.batchEnvironment());
    JdbcIntakeJob.register(tEnv, SRC_URL, Podman.READER, Podman.READER_PASSWORD, LANE_URL, "mig", "mig");
    SqlJobSupport.Plan plan = SqlJobSupport.planTemplate(JdbcIntakeJob.template(), JdbcIntakeJob.params("run-explain"));
    plan.sequential().forEach(tEnv::executeSql);
    StatementSet set = tEnv.createStatementSet();
    plan.batchedInserts().forEach(set::addInsertSql);
    String explained = set.explain();
    String optimized = explained.substring(explained.indexOf("== Optimized Execution Plan =="));
    String scan = "TableSourceScan(table=[[default_catalog, default_database, source_account";
    assertThat(optimized.split(java.util.regex.Pattern.quote(scan), -1).length - 1)
        .as("JDBC scans of the source in the optimised plan:\n" + optimized)
        .isEqualTo(1);
  }

  @Test
  void aMissingSourceColumnFailsBeforeAnyRead() throws Exception {
    Podman.psql(SRC, "source", "ALTER TABLE etlr_source.account RENAME COLUMN balance TO bal;");
    try {
      assertThatThrownBy(() -> JdbcIntakeJob.main(args("run-missing-col")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("[balance]");
      assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE _run_id = 'run-missing-col'")).isEqualTo("0");
    } finally {
      Podman.psql(SRC, "source", "ALTER TABLE etlr_source.account RENAME COLUMN bal TO balance;");
    }
  }

  @Test
  void anUnreachableSourceFailsBeforeAnyWriteAndTheErrorNamesItsHostAndPort() throws Exception {
    Podman.loadFixture(SRC, "run-unreachable");
    JdbcIntakeJob.main(args("run-unreachable"));
    assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE _run_id = 'run-unreachable'")).isEqualTo("52");

    String[] unreachable = args("run-unreachable");
    unreachable[1] = "jdbc:postgresql://localhost:1/source";
    assertThatThrownBy(() -> JdbcIntakeJob.main(unreachable)).hasStackTraceContaining("localhost:1");
    assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE _run_id = 'run-unreachable'"))
        .as("not even the re-run's delete ran").isEqualTo("52");
    assertThat(one("SELECT count(*) FROM etlr_recon.source_read WHERE run_id = 'run-unreachable'")).isEqualTo("1");
  }

  @Test
  void intakeSubmitsExactlyOneJobUnderTheApplicationModeGuard() throws Exception {
    Podman.loadFixture(SRC, "run-appmode");
    Configuration conf = new Configuration();
    conf.set(DeploymentOptions.TARGET, "local");
    conf.set(DeploymentOptions.ATTACHED, true);
    // (loader, config, classLoader, enforceSingleJobExecution, suppressSysout) — order proven by
    // ../flink ApplicationModeGuardIT's negative control.
    StreamContextEnvironment.setAsContext(
        new DefaultExecutorServiceLoader(), conf, JdbcIntakeJobIT.class.getClassLoader(), true, false);
    try {
      JdbcIntakeJob.main(args("run-appmode"));
    } finally {
      StreamContextEnvironment.unsetAsContext();
    }
    assertThat(one("SELECT row_count FROM etlr_recon.source_read WHERE run_id = 'run-appmode'")).isEqualTo("53");
    assertThat(one("SELECT count(*) FROM etlr_extraction.account_src WHERE _run_id = 'run-appmode'")).isEqualTo("52");
  }
}
