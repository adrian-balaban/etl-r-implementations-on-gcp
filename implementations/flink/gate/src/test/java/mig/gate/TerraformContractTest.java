package mig.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Static guards for Terraform mistakes that are invisible to {@code terraform validate},
 * invisible to every local test, and only fail when something in the cloud tries to use the
 * result — each was found by a real deploy.
 *
 * <p>Lives in {@code gate} beside {@code OrchestratorPathContractTest} because that
 * module already owns the "files that reference each other by string" contract tests and
 * has no Terraform-specific dependency to justify a module of its own.
 */
class TerraformContractTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  /**
   * The Managed Kafka bootstrap address must never be assembled from cluster id + region +
   * project. The module used to do exactly that
   * ({@code bootstrap.<id>.<region>.managedkafka.<project>.cloud.goog:9092}); no such host
   * exists. The address Google issues carries an opaque per-cluster id and a hash, and the
   * google provider does not export it, so it has to be passed in.
   *
   * <p>The failure this prevents is unusually misleading: the target-system-mock died at
   * startup with "No resolvable bootstrap urls given in bootstrap.servers", which reads as a
   * VPC/DNS fault and sends you looking at connectors and firewalls.
   */
  @Test
  void kafkaBootstrapAddressIsNeverDerivedFromClusterIdAndRegion() throws Exception {
    String kafkaModule = Files.readString(ROOT.resolve("terraform/modules/kafka/main.tf"));

    assertThat(strippedOfComments(kafkaModule))
        .as("modules/kafka must not synthesise a bootstrap hostname")
        .doesNotContainPattern(Pattern.compile("bootstrap\\.\\$\\{"))
        .doesNotContain(".managedkafka.${var.project_id}.cloud.goog");

    assertThat(kafkaModule)
        .as("the bootstrap address is an explicit input (variable), with the real-address command documented")
        .contains("variable \"bootstrap_address\"")
        .contains("gcloud managed-kafka clusters describe");
  }

  /**
   * Every Cloud Run resource that talks to Cloud SQL's private IP or to Managed Kafka needs a
   * VPC connector — the orchestrator's job was missing one, and would have run to its very
   * last step (recon) before failing.
   */
  @Test
  void cloudRunResourcesThatReachPrivateServicesHaveVpcAccess() throws Exception {
    for (String module : new String[] {"scheduler", "target_system_mock"}) {
      String tf = Files.readString(ROOT.resolve("terraform/modules/" + module + "/main.tf"));
      assertThat(tf)
          .as("modules/%s reaches VPC-internal services and needs vpc_access", module)
          .contains("vpc_access")
          .contains("PRIVATE_RANGES_ONLY");
    }
  }

  /**
   * Any {@code FlinkDeployment} whose job publishes to Kafka must carry the security
   * protocol into BOTH the JobManager and TaskManager environments — the sink runs in a
   * TaskManager, but the job graph is built in the JobManager, and
   * {@code SqlJobSupport.kafkaSecurityOptions} reads the variable in whichever JVM builds the
   * table definition.
   */
  @Test
  void produceManifestPassesKafkaSecurityToBothJobManagerAndTaskManager() throws Exception {
    String manifest =
        Files.readString(ROOT.resolve("gcp/flink-deployments/flink_produce.yaml.template"));
    assertThat(manifest)
        .contains("containerized.taskmanager.env.KAFKA_SECURITY_PROTOCOL")
        .contains("containerized.master.env.KAFKA_SECURITY_PROTOCOL")
        .contains("${KAFKA_BOOTSTRAP}")
        .contains("${KAFKA_TARGET_TOPIC}");
  }

  /**
   * The Cloud SQL password must not sit in the orchestrator job's plain
   * environment (readable by anyone with run.jobs.get). It comes from Secret Manager — and that
   * only works if Terraform actually WRITES a version: the secret used to be created empty.
   */
  @Test
  void theDatabasePasswordIsFetchedFromSecretManagerNotPutInThePlainEnvironment() throws Exception {
    String dev = strippedOfComments(Files.readString(ROOT.resolve("terraform/envs/dev/main.tf")));
    assertThat(dev)
        .as("the scheduler's env_vars carry the SECRET NAME, not the password")
        .contains("CLOUDSQL_APP_PASSWORD_SECRET")
        .doesNotContainPattern(Pattern.compile("CLOUDSQL_APP_PASSWORD\\s*="));

    String secrets = Files.readString(ROOT.resolve("terraform/modules/secrets/main.tf"));
    assertThat(secrets)
        .as("the module writes a version, so the secret is not empty")
        .contains("google_secret_manager_secret_version");
    assertThat(dev).as("the generated password is what gets stored").contains("versioned_secrets");
    // The variable's body contains `default = {}`, so "up to the first }" would stop too early;
    // the block ends at the first closing brace that starts a line.
    int start = secrets.indexOf("variable \"secret_values\"");
    String block = secrets.substring(start, secrets.indexOf("\n}", start));
    assertThat(block)
        .as("and the value is sensitive, so it stays out of plan output")
        .containsPattern(Pattern.compile("sensitive\\s*=\\s*true"));
  }

  /**
   * The TTL guard is the only enable_* flag that bounds HOW LONG something
   * bills, so unlike the others it must default ON — a guard that is off protects nothing. And it
   * must refuse a TTL short enough to sweep an environment mid-provisioning.
   */
  @Test
  void theTtlGuardDefaultsOnAndRefusesATtlShorterThanProvisioning() throws Exception {
    String dev = Files.readString(ROOT.resolve("terraform/envs/dev/main.tf"));
    assertThat(dev).containsPattern(Pattern.compile("variable \"enable_ttl_guard\" \\{[^}]*default\\s*=\\s*true", Pattern.DOTALL));

    String guard = Files.readString(ROOT.resolve("terraform/modules/ttl_guard/main.tf"));
    assertThat(guard).contains("var.ttl_hours >= 1");
    assertThat(guard)
        .as("the sweeper is invoked with a command override on an image that ships sweep.sh")
        .contains("/app/sweep.sh");
    assertThat(Files.readString(ROOT.resolve("gcp/docker/Dockerfile.orchestrator"))).contains("gcp/ttl/sweep.sh");
  }

  /**
   * The mock's counters are per process and its consumer group commits
   * offsets, so more than one instance makes them meaningless. One instance by default.
   */
  @Test
  void theMockDefaultsToASingleInstanceSoItsCountersMeanWhatTheySay() throws Exception {
    String mock = Files.readString(ROOT.resolve("terraform/modules/target_system_mock/main.tf"));
    assertThat(mock).containsPattern(Pattern.compile("variable \"max_instances\" \\{[^}]*default\\s*=\\s*1", Pattern.DOTALL));
    assertThat(strippedOfComments(mock)).contains("max_instance_count = var.max_instances");
  }

  private static String strippedOfComments(String hcl) {
    return hcl.lines()
        .map(l -> l.strip().startsWith("#") ? "" : l)
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }
}
