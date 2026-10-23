package mig.gatejdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Static guards on implementations/flink-jdbc/terraform — the mistakes `terraform validate` cannot see, several of
 * which the Flink lane found only on real GCP (../flink/).
 */
class JdbcTerraformContractTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize(); // implementations/flink-jdbc
  private static final Path DEV = ROOT.resolve("terraform/envs/dev/main.tf");
  private static final Path FLINK_MODULES = ROOT.resolve("../flink/terraform/modules").normalize();

  private static String dev() throws Exception {
    return Files.readString(DEV);
  }

  private static String code(String hcl) {
    return hcl.lines().map(l -> l.strip().startsWith("#") ? "" : l).reduce((a, b) -> a + "\n" + b).orElse("");
  }

  private static Pattern defaultOf(String variable, String value) {
    return Pattern.compile("variable \"" + variable + "\" \\{[^}]*default\\s*=\\s*" + Pattern.quote(value), Pattern.DOTALL);
  }

  @Test
  void everyBillableOrImageDependentFlagDefaultsOff() throws Exception {
    for (String flag : List.of("enable_cloudsql", "enable_kafka", "enable_flink_cluster", "enable_scheduler", "enable_seeder")) {
      assertThat(dev()).as(flag + " must default to false").containsPattern(defaultOf(flag, "false"));
    }
  }

  @Test
  void theTtlGuardDefaultsOnAndItsPrefixCoversBothCloudSqlInstances() throws Exception {
    assertThat(dev()).containsPattern(defaultOf("enable_ttl_guard", "true"));
    // gcp/ttl/sweep.sh deletes only resources whose name starts with NAME_PREFIX (default "mig-flink").
    Matcher m = Pattern.compile("instance_name\\s*=\\s*\"([^\"]+)").matcher(code(dev()));
    int instances = 0;
    while (m.find()) {
      instances++;
      assertThat(m.group(1)).as("Cloud SQL instance name").startsWith("mig-flink");
    }
    assertThat(instances).as("two Cloud SQL instances: lane and Source System").isEqualTo(2);
    assertThat(Files.readString(ROOT.resolve("gcp/docker/Dockerfile.orchestrator")))
        .as("the sweeper runs from the orchestrator image").contains("flink/gcp/ttl/sweep.sh");
  }

  @Test
  void modulesAreReusedFromTheFlinkLaneByRelativePathAndNeverCopied() throws Exception {
    Matcher m = Pattern.compile("(?m)^\\s*source\\s*=\\s*\"([^\"]+)\"").matcher(code(dev()));
    int modules = 0;
    while (m.find()) {
      modules++;
      assertThat(m.group(1)).startsWith("../../../../flink/terraform/modules/");
      assertThat(DEV.getParent().resolve(m.group(1)).normalize()).isDirectory();
    }
    assertThat(modules).isGreaterThanOrEqualTo(12);
    assertThat(ROOT.resolve("terraform/modules")).doesNotExist();
  }

  @Test
  void theSourceInstanceIsPrivateSmallestTierAndSharesTheLanesPeering() throws Exception {
    String cloudsql = Files.readString(FLINK_MODULES.resolve("cloudsql/main.tf"));
    assertThat(cloudsql).contains("ipv4_enabled    = false").contains("default     = \"db-custom-1-3840\"");
    String dev = code(dev());
    assertThat(dev).as("no tier override: the module's smallest tier").doesNotContainPattern("\\btier\\s*=");
    assertThat(dev).contains("manage_peering      = false").contains("database_name       = \"source\"");
  }

  @Test
  void theReaderPasswordLivesInSecretManagerAsEtlrSourceDb() throws Exception {
    String dev = code(dev());
    assertThat(dev).contains("\"etlr-source-db\"").contains("SOURCE_READER_PASSWORD_SECRET")
        .doesNotContainPattern(Pattern.compile("SOURCE_READER_PASSWORD\\s*="))
        .doesNotContainPattern(Pattern.compile("CLOUDSQL_APP_PASSWORD\\s*="));
    assertThat(dev).as("the seeder reads secrets as Cloud Run secret env, never as plain values")
        .contains("secret_key_ref");
  }

  @Test
  void cloudRunJobsThatReachPrivateIpsHaveTheConnector() throws Exception {
    String dev = code(dev());
    assertThat(dev).contains("enabled    = var.enable_cloudsql || var.enable_kafka");
    int seeder = dev.indexOf("resource \"google_cloud_run_v2_job\" \"source_seeder\"");
    assertThat(seeder).isPositive();
    assertThat(dev.substring(seeder)).contains("vpc_access").contains("PRIVATE_RANGES_ONLY");
  }

  @Test
  void theReusedModulesStillDefaultToTheFlinkLanesNames() throws Exception {
    String cloudsql = Files.readString(FLINK_MODULES.resolve("cloudsql/main.tf"));
    assertThat(cloudsql)
        .containsPattern(defaultOf("instance_name", "\"mig-flink-pg\""))
        .containsPattern(defaultOf("peering_range_name", "\"mig-flink-cloudsql-range\""))
        .containsPattern(defaultOf("manage_peering", "true"))
        .containsPattern(defaultOf("database_name", "\"mig\""))
        .containsPattern(defaultOf("app_user_name", "\"mig\""));
    assertThat(Files.readString(FLINK_MODULES.resolve("secrets/main.tf")))
        .containsPattern(defaultOf("extra_secret_names", "[]"));
  }

  @Test
  void kafkaBootstrapIsAnExplicitInputWithAWarningCheck() throws Exception {
    assertThat(dev()).contains("variable \"kafka_bootstrap\"").contains("check \"kafka_bootstrap_provided\"")
        .contains("gcloud managed-kafka clusters describe");
  }
}
