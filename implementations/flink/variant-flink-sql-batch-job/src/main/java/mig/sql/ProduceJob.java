package mig.sql;

import java.util.Map;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * Job 3 — produce: the map and schema doors. Reads
 * {@code etlr_transformation.account_enriched} for one run, applies the status enum mapping
 * (map door) and the target-schema predicates, and
 * writes {@code account_target} for survivors plus {@code reject_log}/{@code record_lineage}
 * for both reject stages.
 *
 * <p>Surviving documents go to {@code etlr_transformation.account_target} and, when a broker
 * is configured, to the {@code target-system-target} Kafka topic — the lane's actual output
 *. The Kafka half is optional so that every Postgres-only test and the
 * 09-09-style GCP deploy with {@code enable_kafka=false} still run unchanged; when it is
 * configured, it rides in the same StatementSet, so the stage is still one Flink job.
 *
 * <p>Args: {@code <runId> <jdbcUrl> <jdbcUser> <jdbcPassword> [kafkaBootstrap] [targetTopic]}
 * — {@code KAFKA_BOOTSTRAP} / {@code TARGET_SYSTEM_TARGET_TOPIC} in the environment are the
 * fallback for both, matching how the DataStream variant and the orchestrator pass them.
 */
public final class ProduceJob {

  private static final String DEFAULT_TARGET_TOPIC = "target-system-target";

  public static void main(String[] args) throws Exception {
    if (args.length < 4 || args.length > 6) {
      System.err.println(
          "usage: ProduceJob <runId> <jdbcUrl> <jdbcUser> <jdbcPassword> [kafkaBootstrap] [targetTopic]");
      System.exit(2);
    }
    String runId = mig.common.RunId.require(args[0]);
    String jdbcUrl = args[1];
    String jdbcUser = args[2];
    String jdbcPassword = mig.common.ConnectionArgs.resolve(args[3]);
    String kafkaBootstrap = args.length > 4 ? args[4] : System.getenv("KAFKA_BOOTSTRAP");
    String targetTopic =
        args.length > 5
            ? args[5]
            : System.getenv().getOrDefault("TARGET_SYSTEM_TARGET_TOPIC", DEFAULT_TARGET_TOPIC);
    boolean kafkaConfigured = kafkaBootstrap != null && !kafkaBootstrap.isBlank();

    // Idempotent re-run. account_target belongs entirely to this job
    // for the run; reject_log/record_lineage only get its map/schema-stage rows — the
    // parse-stage rows IntakeJob already wrote for this run_id must survive.
    SqlJobSupport.deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_transformation.account_target", "run_id", runId);
    SqlJobSupport.deleteRunId(
        jdbcUrl, jdbcUser, jdbcPassword, "etlr_recon.reject_log", "run_id", runId, "stage IN ('map', 'schema')");
    SqlJobSupport.deleteRunId(
        jdbcUrl, jdbcUser, jdbcPassword, "etlr_recon.record_lineage", "run_id", runId, "stage IN ('map', 'schema')");

    StreamExecutionEnvironment env = SqlJobSupport.batchEnvironment(); // batch, asserted
    StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

    SqlJobSupport.jdbcTable(
        tEnv, "account_enriched",
        "account_id STRING, customer_id STRING, product_code STRING, currency STRING, "
            + "opened_on DATE, status_code STRING, balance_minor BIGINT, balance_amount STRING, "
            + "segment STRING, branch STRING, risk_rating INT, balance_band STRING, "
            + "account_age_years INT, branch_name STRING, branch_city STRING, branch_region STRING, "
            + "run_id STRING, account_key STRING",
        "etlr_transformation.account_enriched", jdbcUrl, jdbcUser, jdbcPassword);
    SqlJobSupport.jdbcTable(
        tEnv, "account_target",
        "run_id STRING, account_id STRING, account_key STRING, doc_json STRING",
        "etlr_transformation.account_target", jdbcUrl, jdbcUser, jdbcPassword);
    SqlJobSupport.jdbcTable(
        tEnv, "reject_log",
        "run_id STRING, batch_id BIGINT, source_key STRING, stage STRING, reason STRING, "
            + "detail STRING, raw_record STRING",
        "etlr_recon.reject_log", jdbcUrl, jdbcUser, jdbcPassword);
    SqlJobSupport.jdbcTable(
        tEnv, "record_lineage",
        "run_id STRING, source_key STRING, account_key STRING, door STRING, stage STRING, "
            + "reason STRING, detail STRING, source_file STRING",
        "etlr_recon.record_lineage", jdbcUrl, jdbcUser, jdbcPassword);

    java.util.List<String> templates =
        new java.util.ArrayList<>(
            java.util.List.of(SqlJobSupport.loadResource(ProduceJob.class, "/sql/30-produce.sql.template")));
    Map<String, String> params =
        new java.util.HashMap<>(
            Map.of(
                "RUN_ID", runId,
                "KAFKA_BOOTSTRAP", kafkaConfigured ? kafkaBootstrap : "",
                "KAFKA_TARGET_TOPIC", targetTopic,
                "KAFKA_SECURITY_OPTIONS", SqlJobSupport.kafkaSecurityOptions()));
    if (kafkaConfigured) {
      templates.add(SqlJobSupport.loadResource(ProduceJob.class, "/sql/35-produce-kafka.sql.template"));
      System.out.println("ProduceJob: Kafka sink enabled, topic=" + targetTopic + " bootstrap=" + kafkaBootstrap);
    } else {
      System.out.println("ProduceJob: no KAFKA_BOOTSTRAP configured — writing account_target only");
    }
    SqlJobSupport.executeTemplates(tEnv, templates, params);
  }
}
