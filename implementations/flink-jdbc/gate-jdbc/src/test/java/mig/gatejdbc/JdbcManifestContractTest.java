package mig.gatejdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JdbcManifestContractTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @Test
  void theIntakeManifestRunsJdbcIntakeJobInBatchFromTheLanesJarWithBothPasswordsFromSecrets() throws Exception {
    String m = Files.readString(ROOT.resolve("gcp/flink-deployments/flink_intake_jdbc.yaml.template"));
    assertThat(m)
        .contains("name: flink-intake-jdbc-${RUN_ID}")
        .contains("entryClass: mig.sql.JdbcIntakeJob")
        .contains("execution.runtime-mode: BATCH")
        .contains("jarURI: local:///opt/flink/usrlib/flink-jdbc-job.jar")
        .contains("/mig-flink/flink-jdbc-job:${IMAGE_TAG}")
        .contains("\"env:SOURCE_JDBC_PASSWORD\"").contains("\"${JDBC_PASSWORD}\"")
        .contains("name: etlr-source-db").contains("name: mig-db")
        .contains("parallelism: 1");
    // JdbcIntakeJob's 8 arguments, in order.
    String args = m.substring(m.indexOf("args:"), m.indexOf("parallelism:"));
    assertThat(args.lines().filter(l -> l.strip().startsWith("- ")).count()).isEqualTo(8);
  }
}
