package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.Test;

class JdbcIntakeSourceOptionsTest {

  private static final String SRC = "jdbc:postgresql://localhost:1/source";

  @Test
  void theSourceIsReadInFetchSizedRoundTripsOutsideAutocommit() {
    assertThat(JdbcIntakeJob.sourceTableDdl(SRC, "etlr_reader", "pw"))
        .contains("'table-name'='etlr_source.account'")
        .contains("'scan.fetch-size'='1000'")
        .contains("'scan.auto-commit'='false'");
  }

  @Test
  void theSourceIsNotPartitionedSoItIsReadAsOneQueryInOneSnapshot() {
    assertThat(JdbcIntakeJob.sourceTableDdl(SRC, "etlr_reader", "pw")).doesNotContain("scan.partition");
  }

  /** Unknown option names fail planning with "Unsupported options"; planning does not connect. */
  @Test
  void flinkAcceptsTheOptions() {
    StreamTableEnvironment tEnv = StreamTableEnvironment.create(SqlJobSupport.batchEnvironment());
    JdbcIntakeJob.register(tEnv, SRC, "etlr_reader", "pw", "jdbc:postgresql://localhost:1/mig", "mig", "mig");
    assertThat(tEnv.explainSql("SELECT acct_id, balance FROM source_account")).contains("source_account");
  }
}
