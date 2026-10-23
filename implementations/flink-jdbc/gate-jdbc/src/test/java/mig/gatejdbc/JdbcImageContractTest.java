package mig.gatejdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class JdbcImageContractTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();        // implementations/flink-jdbc
  private static final Path IMPL = ROOT.getParent();                                  // implementations/ = context
  private static final Path SPEC = IMPL.resolve("../spec").normalize();
  private static final List<String> LANE_DOCKERFILES =
      List.of("Dockerfile.orchestrator", "Dockerfile.flinkjob", "Dockerfile.seeder");

  /** Every plain COPY source must exist in its context: a typo otherwise surfaces only in a 10-minute build. */
  @Test
  void everyCopySourceExistsInItsContext() throws Exception {
    for (String file : LANE_DOCKERFILES) {
      for (String line : Files.readAllLines(ROOT.resolve("gcp/docker/" + file))) {
        if (!line.startsWith("COPY ")) {
          continue;
        }
        boolean fromSpec = line.contains("--from=spec");
        if (line.contains("--from=") && !fromSpec) {
          continue; // a build stage
        }
        String[] parts = line.replaceFirst("COPY (--from=\\S+ )?", "").split("\\s+");
        Path base = fromSpec ? SPEC : IMPL;
        for (int i = 0; i < parts.length - 1; i++) {
          assertThat(base.resolve(parts[i])).as(file + ": " + line).exists();
        }
      }
    }
  }

  @Test
  void theOrchestratorCarriesGateJdbcReconAllThreeTemplatesAndTheSweeper() throws Exception {
    assertThat(Files.readString(ROOT.resolve("gcp/docker/Dockerfile.orchestrator")))
        .contains("/app/gate-jdbc.jar").contains("/app/recon-service.jar")
        .contains("flink-jdbc/gcp/flink-deployments/flink_intake_jdbc.yaml.template")
        .contains("flink/gcp/flink-deployments/flink_curate_enrich.yaml.template")
        .contains("flink/gcp/flink-deployments/flink_produce.yaml.template")
        .contains("flink/gcp/ttl/sweep.sh").contains("google-cloud-cli-gke-gcloud-auth-plugin");
  }

  @Test
  void theJobImageCarriesTheLanesOneJobJarAndTheContracts() throws Exception {
    assertThat(Files.readString(ROOT.resolve("gcp/docker/Dockerfile.flinkjob")))
        .contains("/opt/flink/usrlib/flink-jdbc-job.jar").contains("COPY --from=spec contracts /opt/flink/contracts")
        .contains("FROM docker.io/library/flink:2.2.1-java17");
  }

  @Test
  void theSeederCarriesPythonTheHarnessAndPsql() throws Exception {
    assertThat(Files.readString(ROOT.resolve("gcp/docker/Dockerfile.seeder")))
        .contains("python:3.12-slim-bookworm").contains("postgresql-client")
        .contains("harness/load_db.py").contains("harness/generate.py").contains("seed.sh");
  }

  @Test
  void dryRunPrintsFourBuildsWithTheRightContexts() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("bash", ROOT.resolve("gcp/docker/build.sh").toString(), "reg.example/p/mig-flink", "t1");
    pb.environment().put("DRY_RUN", "1");
    Process p = pb.redirectErrorStream(true).start();
    List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    assertThat(p.waitFor()).isZero();
    assertThat(lines).hasSize(4);
    for (String image : List.of("orchestrator", "flink-jdbc-job", "source-seeder")) {
      assertThat(lines).anyMatch(l -> l.contains("-t reg.example/p/mig-flink/" + image + ":t1")
          && l.contains("--build-context spec=" + SPEC)
          && l.contains("--ignorefile " + ROOT.resolve("gcp/docker/build.ignore"))
          && l.endsWith(" " + IMPL));
    }
    assertThat(lines).anyMatch(l -> l.contains("target-system-mock:t1") && l.endsWith(" " + IMPL.resolve("flink")));
    assertThat(Files.readString(ROOT.resolve("gcp/docker/build.ignore")))
        .contains("**/terraform.tfstate*").contains("**/target/").contains("beam-dataflow/");
  }
}
