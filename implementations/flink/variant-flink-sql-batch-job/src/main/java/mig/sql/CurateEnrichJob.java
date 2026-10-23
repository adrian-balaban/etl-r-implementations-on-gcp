package mig.sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Job 2 — curate + enrich. Reads {@code etlr_extraction.account_src}
 * for one run, normalises it (Dataform's {@code account_curated.sqlx}), then left-joins
 * {@code contracts/reference/branches.json} (the Data Enrichment stage). No doors: every row
 * that goes in comes out, with more columns.
 *
 * <p>Args: {@code <runId> <asOfDate:yyyy-MM-dd> <jdbcUrl> <jdbcUser> <jdbcPassword> <repoRoot>}
 */
public final class CurateEnrichJob {

  public static void main(String[] args) throws Exception {
    if (args.length != 6) {
      System.err.println(
          "usage: CurateEnrichJob <runId> <asOfDate:yyyy-MM-dd> <jdbcUrl> <jdbcUser> <jdbcPassword> <repoRoot>");
      System.exit(2);
    }
    String runId = mig.common.RunId.require(args[0]);
    String asOfDate = args[1];
    String jdbcUrl = args[2];
    String jdbcUser = args[3];
    String jdbcPassword = mig.common.ConnectionArgs.resolve(args[4]);
    Path repoRoot = Path.of(args[5]);
    LocalDate.parse(asOfDate); // fail fast on a malformed date rather than a cryptic SQL error

    // Idempotent re-run.
    SqlJobSupport.deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_transformation.account_curated", "run_id", runId);
    SqlJobSupport.deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_transformation.account_enriched", "run_id", runId);

    StreamExecutionEnvironment env = SqlJobSupport.batchEnvironment(); // batch, asserted
    StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

    SqlJobSupport.jdbcTable(
        tEnv, "account_src",
        "acct_id STRING, cust_id STRING, client_type STRING, product_code STRING, "
            + "currency STRING, open_date DATE, status STRING, balance STRING, "
            + "_run_id STRING, _batch_id BIGINT, _source_file STRING, _source_key STRING, "
            + "_account_key STRING, _ingested_at STRING",
        "etlr_extraction.account_src", jdbcUrl, jdbcUser, jdbcPassword);
    SqlJobSupport.jdbcTable(
        tEnv, "account_curated",
        curatedColumnsDdl(),
        "etlr_transformation.account_curated", jdbcUrl, jdbcUser, jdbcPassword);
    // Column order matches account_enriched_view's SELECT list in 20-curate-enrich.sql.template
    // exactly (branch_name/_city/_region before run_id/account_key, not after) — Flink's
    // INSERT INTO ... SELECT matches by position, not by name, so a mismatch here silently
    // shifts every column after the first divergence into the wrong sink column.
    SqlJobSupport.jdbcTable(
        tEnv, "account_enriched",
        "account_id STRING, customer_id STRING, product_code STRING, currency STRING, "
            + "opened_on DATE, status_code STRING, balance_minor BIGINT, balance_amount STRING, "
            + "segment STRING, branch STRING, risk_rating INT, balance_band STRING, "
            + "account_age_years INT, branch_name STRING, branch_city STRING, branch_region STRING, "
            + "run_id STRING, account_key STRING",
        "etlr_transformation.account_enriched", jdbcUrl, jdbcUser, jdbcPassword);

    tEnv.createTemporaryView("branches_ref", loadBranchesRef(tEnv, repoRoot));

    String template = SqlJobSupport.loadResource(CurateEnrichJob.class, "/sql/20-curate-enrich.sql.template");
    SqlJobSupport.executeTemplate(
        tEnv, template, Map.of("RUN_ID", runId, "AS_OF_DATE", asOfDate));
  }

  private static String curatedColumnsDdl() {
    return "account_id STRING, customer_id STRING, product_code STRING, currency STRING, "
        + "opened_on DATE, status_code STRING, balance_minor BIGINT, balance_amount STRING, "
        + "segment STRING, branch STRING, risk_rating INT, balance_band STRING, "
        + "account_age_years INT, run_id STRING, account_key STRING";
  }

  /**
   * contracts/reference/branches.json is a {@code {"branches": {code: {name, city, region}}}}
   * map, not row-shaped JSON-lines — loaded with Jackson and exposed as an in-memory VALUES
   * table (branch_code, branch_name, branch_city, branch_region) rather than through the
   * filesystem connector's {@code format='json'}, which expects one JSON object per line.
   */
  private static Table loadBranchesRef(StreamTableEnvironment tEnv, Path repoRoot) throws Exception {
    JsonNode root =
        new ObjectMapper()
            .readTree(Files.readString(repoRoot.resolve("contracts/reference/branches.json")));
    List<Row> rows = new ArrayList<>();
    root.path("branches")
        .fields()
        .forEachRemaining(
            e ->
                rows.add(
                    Row.of(
                        e.getKey(),
                        e.getValue().path("name").asText(),
                        e.getValue().path("city").asText(),
                        e.getValue().path("region").asText())));
    return tEnv.fromValues(rows).as("branch_code", "branch_name", "branch_city", "branch_region");
  }
}
