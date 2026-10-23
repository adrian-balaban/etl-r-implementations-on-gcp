package mig.sql;

import java.nio.file.Path;
import java.util.Map;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

import mig.common.Mapping;
import mig.common.Tds;

/**
 * Job 1 — intake. Runs the {@code parsed}/{@code CASE} logic from
 * {@code sql/10-intake.sql.template} (Table API executeSql, one statement at a time — Flink
 * SQL's multi-statement STATEMENT SET is driven through {@link org.apache.flink.table.api.StatementSet}
 * rather than a raw multi-line string) against a source built the way the raw-format spike
 * ({@code RawFormatSpikeTest}) resolved it: {@code FileSource} + {@code TextLineInputFormat} in the DataStream layer,
 * bridged into Table API with {@code fromDataStream} — not the SQL-only {@code
 * 'format'='raw'} filesystem connector the original SQL sketch assumed, which was not verified
 * to give one row per line.
 *
 * <p>Args: {@code <inputFile> <runId> <jdbcUrl> <jdbcUser> <jdbcPassword> <repoRoot>} —
 * {@code repoRoot} is the directory containing {@code contracts/} (used the same way
 * component-tests' {@code ParityTest} resolves it), needed only to read the TDS contract for
 * {@code headerLine()}.
 */
public final class IntakeJob {

  public static void main(String[] args) throws Exception {
    if (args.length != 6) {
      System.err.println(
          "usage: IntakeJob <inputFile> <runId> <jdbcUrl> <jdbcUser> <jdbcPassword> <repoRoot>");
      System.exit(2);
    }
    String inputFile = args[0];
    String runId = mig.common.RunId.require(args[1]);
    String jdbcUrl = args[2];
    String jdbcUser = args[3];
    String jdbcPassword = mig.common.ConnectionArgs.resolve(args[4]);
    Path repoRoot = Path.of(args[5]);

    // Idempotent re-run: a Composer task retry submits this job a
    // second time on the same run_id, so any prior rows for it must go first.
    SqlJobSupport.deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_extraction.account_src", "_run_id", runId);
    SqlJobSupport.deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_recon.reject_log", "run_id", runId);
    SqlJobSupport.deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_recon.record_lineage", "run_id", runId);

    StreamExecutionEnvironment env = SqlJobSupport.batchEnvironment(); // batch, asserted
    StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

    // The raw line, untouched — 'format'='raw' equivalent via FileSource (see RawFormatSpikeTest).
    FileSource<String> source =
        FileSource.forRecordStreamFormat(new TextLineInputFormat(), new org.apache.flink.core.fs.Path(inputFile))
            .build();
    DataStream<String> lines = env.fromSource(source, WatermarkStrategy.noWatermarks(), "account-src-lines");
    Table linesTable = tEnv.fromDataStream(lines).as("line");
    tEnv.createTemporaryView("lines_source", linesTable);

    SqlJobSupport.jdbcTable(
        tEnv, "account_src",
        "acct_id STRING, cust_id STRING, client_type STRING, product_code STRING, "
            + "currency STRING, open_date DATE, status STRING, balance STRING, "
            + "_run_id STRING, _batch_id BIGINT, _source_file STRING, _source_key STRING, "
            + "_account_key STRING, _ingested_at STRING",
        "etlr_extraction.account_src", jdbcUrl, jdbcUser, jdbcPassword);
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

    String template = SqlJobSupport.loadResource(IntakeJob.class, "/sql/10-intake.sql.template");

    // The header text comes from the TDS contract, not a literal in the SQL.
    Mapping.Config mappingCfg =
        Mapping.load(repoRoot.resolve("contracts/mappings/mapping-project1.yaml"), repoRoot);
    Tds.RecordParser srcParser = new Tds.RecordParser(mappingCfg.srcRecord(), mappingCfg.sourceLayout());

    SqlJobSupport.executeTemplate(
        tEnv,
        template,
        Map.of(
            "RUN_ID", runId,
            "HEADER_LINE", srcParser.headerLine(),
            "INGESTED_AT_EPOCH", String.valueOf(System.currentTimeMillis() / 1000.0)));
  }
}
