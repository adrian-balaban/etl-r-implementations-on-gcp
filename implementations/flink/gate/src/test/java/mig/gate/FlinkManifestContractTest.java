package mig.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The FlinkDeployment templates are rendered by {@code run.sh} with {@code sed}, so a mistake in
 * one is invisible until a real cluster rejects it — and the only cluster is on GCP. Parsing the
 * rendered YAML and checking its structure costs nothing and catches the whole class of
 * "indentation slipped" and "key in the wrong place" errors offline.
 *
 * <p>This does NOT prove the operator accepts them (that needs the operator); it proves they are
 * well-formed YAML with the shape the operator's CRD expects for the parts the orchestrator
 * changes (the run_id substitution).
 */
class FlinkManifestContractTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Z_]+\\}");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> render(String job) throws Exception {
    String text = Files.readString(ROOT.resolve("gcp/flink-deployments/flink_" + job + ".yaml.template"));
    // Same effect as run.sh's sed for our purposes: every ${VAR} becomes a plain scalar.
    return new Yaml().load(PLACEHOLDER.matcher(text).replaceAll("x"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void everyManifestIsValidYamlAndMountsTheDatabaseSecretAsAnEnvironmentVariable() throws Exception {
    for (String job : List.of("intake", "curate_enrich", "produce")) {
      Map<String, Object> doc = render(job);
      assertThat(doc.get("kind")).as(job).isEqualTo("FlinkDeployment");

      Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
      Map<String, Object> podTemplate = (Map<String, Object>) spec.get("podTemplate");
      assertThat(podTemplate).as("%s has a podTemplate", job).isNotNull();

      List<Map<String, Object>> containers =
          (List<Map<String, Object>>) ((Map<String, Object>) podTemplate.get("spec")).get("containers");
      // The operator merges the pod template into the container named exactly this; any other name
      // adds a SECOND container instead and the env var never reaches the job.
      assertThat(containers.get(0).get("name")).as(job).isEqualTo("flink-main-container");

      List<Map<String, Object>> env = (List<Map<String, Object>>) containers.get(0).get("env");
      Map<String, Object> ref =
          (Map<String, Object>) ((Map<String, Object>) env.get(0).get("valueFrom")).get("secretKeyRef");
      assertThat(env.get(0).get("name")).as(job).isEqualTo("JDBC_PASSWORD");
      assertThat(ref.get("name")).isEqualTo("mig-db");
      assertThat(ref.get("key")).isEqualTo("password");
      assertThat(ref.get("optional")).as("optional so DB_PASSWORD_MODE=arg still starts").isEqualTo(true);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void theJobsPasswordArgumentIsTheTemplatePlaceholderNotALiteral() throws Exception {
    // run.sh substitutes ${JDBC_PASSWORD} with `env:JDBC_PASSWORD` (or the literal in arg mode).
    // If the placeholder were replaced by a literal here, the password would be baked into git.
    for (String job : List.of("intake", "curate_enrich", "produce")) {
      String text = Files.readString(ROOT.resolve("gcp/flink-deployments/flink_" + job + ".yaml.template"));
      assertThat(text).as(job).contains("\"${JDBC_PASSWORD}\"");
    }
  }
}
