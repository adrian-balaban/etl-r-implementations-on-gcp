package mig.datastream;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import mig.common.Doors;
import mig.common.Doors.Lineage;
import mig.common.Doors.Reject;
import mig.common.KafkaClients;
import mig.common.Mapping;
import mig.common.RecordRouter;
import mig.common.RecordRouter.Outcome;
import mig.common.TargetSchemaValidator;

/**
 * The DataStream variant. Where the SQL variant translates the two
 * doors into {@code CASE} expressions across three separate jobs (intake / curate+enrich /
 * produce), this variant runs {@link RecordRouter} directly — the same Java port the SQL
 * variant's parity is checked against in {@code ParityTest} — inside one {@link
 * ProcessFunction}, splitting rejects to a side output via {@link OutputTag} the way the
 * Python original's Beam {@code DoFn.process} used {@code TaggedOutput}.
 *
 * <p>Deliberately does NOT call into anything the SQL variant's Postgres tables hold (no
 * account_curated/account_enriched — those columns are never read by {@link
 * Mapping.Engine#mapRecord}, so replicating them here would just be unexercised plumbing) —
 * it writes {@code account_target}, {@code reject_log}, {@code record_lineage} straight from
 * the router's output, which is exactly what the three-way parity test compares.
 *
 * <p>Args: {@code <inputFile> <runId> <jdbcUrl> <jdbcUser> <jdbcPassword> <repoRoot>
 * [kafkaBootstrap] [kafkaTopic]} — the last two are optional; when omitted, no document is
 * published to Kafka.
 */
public final class DataStreamJob {

  static final OutputTag<Reject> REJECT_TAG = new OutputTag<>("rejects", TypeInformation.of(Reject.class));
  static final OutputTag<Lineage> LINEAGE_TAG =
      new OutputTag<>("lineage", TypeInformation.of(Lineage.class));

  public static void main(String[] args) throws Exception {
    if (args.length != 6 && args.length != 8) {
      System.err.println(
          "usage: DataStreamJob <inputFile> <runId> <jdbcUrl> <jdbcUser> <jdbcPassword> <repoRoot> "
              + "[kafkaBootstrap] [kafkaTopic]");
      System.exit(2);
    }
    String inputFile = args[0];
    String runId = mig.common.RunId.require(args[1]);
    String jdbcUrl = args[2];
    String jdbcUser = args[3];
    String jdbcPassword = mig.common.ConnectionArgs.resolve(args[4]);
    Path repoRoot = Path.of(args[5]);
    String kafkaBootstrap = args.length == 8 ? args[6] : null;
    String kafkaTopic = args.length == 8 ? args[7] : null;

    // Idempotent re-run: this job owns account_target/reject_log/
    // record_lineage entirely for its own run_id (single-pass, unlike the SQL variant's
    // three jobs), so a full DELETE per table is correct here.
    deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_transformation.account_target", runId);
    deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_recon.reject_log", runId);
    deleteRunId(jdbcUrl, jdbcUser, jdbcPassword, "etlr_recon.record_lineage", runId);

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setRuntimeMode(RuntimeExecutionMode.BATCH); // batch, not streaming
    // ...and verified, not just requested. The SQL variant asserts this in SqlJobSupport.requireBatch;
    // this variant is a different module with its own env, so it carries the same check.
    RuntimeExecutionMode mode = env.getConfiguration().get(ExecutionOptions.RUNTIME_MODE);
    if (mode != RuntimeExecutionMode.BATCH) {
      throw new IllegalStateException(
          "the run requires batch execution; this environment is " + mode);
    }

    FileSource<String> source =
        FileSource.forRecordStreamFormat(new TextLineInputFormat(), new org.apache.flink.core.fs.Path(inputFile))
            .build();
    DataStream<String> lines = env.fromSource(source, WatermarkStrategy.noWatermarks(), "account-src-lines");

    SingleOutputStreamOperator<TargetRow> targets =
        lines.process(new RouteFn(runId, repoRoot.toString()));

    DataStream<Reject> rejects = targets.getSideOutput(REJECT_TAG);
    DataStream<Lineage> lineage = targets.getSideOutput(LINEAGE_TAG);

    targets.addSink(new TargetSink(jdbcUrl, jdbcUser, jdbcPassword));
    rejects.addSink(new RejectSink(jdbcUrl, jdbcUser, jdbcPassword));
    lineage.addSink(new LineageSink(jdbcUrl, jdbcUser, jdbcPassword));
    if (kafkaBootstrap != null) {
      // Batch has no checkpoints, so this is deliberately a plain at-least-once
      // producer (not a transactional/exactly-once sink) — migration.dedupKey plus the
      // mock's idempotency map is what makes a replay safe, exactly as the document
      // originally argued.
      targets.addSink(new KafkaTargetSink(kafkaBootstrap, kafkaTopic));
    }

    env.execute("mig-datastream-" + runId);
  }

  /** One row of {@code etlr_transformation.account_target}. */
  public record TargetRow(String runId, String accountId, String accountKey, String docJson) {}

  /**
   * The single {@link ProcessFunction} that runs parse -&gt; map -&gt; schema per line and
   * tags each outcome — the direct DataStream equivalent of Beam's {@code TaggedOutput} the
   * Python {@code json_producer}'s {@code MapAndValidateFn} used.
   */
  static final class RouteFn extends ProcessFunction<String, TargetRow> {
    private final String runId;
    private final String repoRoot;
    private transient RecordRouter router;
    private transient Mapping.Engine engine;
    private transient ObjectMapper mapper;
    private transient long batchId;

    RouteFn(String runId, String repoRoot) {
      this.runId = runId;
      this.repoRoot = repoRoot;
    }

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
      Path root = Path.of(repoRoot);
      Mapping.Config cfg = Mapping.load(root.resolve("contracts/mappings/mapping-project1.yaml"), root);
      engine = new Mapping.Engine(cfg);
      TargetSchemaValidator validator = new TargetSchemaValidator(cfg.targetSchemaPath());
      router = new RecordRouter(engine, validator, runId);
      mapper = new ObjectMapper();
      batchId = 0;
    }

    @Override
    public void processElement(String raw, Context ctx, Collector<TargetRow> out) throws Exception {
      if (raw.isBlank() || engine.isHeader(raw)) {
        return;
      }
      Outcome outcome = router.route(raw, batchId);
      if (outcome.door() == Doors.Door.WRITTEN) {
        out.collect(
            new TargetRow(
                runId, outcome.sourceKey(), outcome.accountKey(), mapper.writeValueAsString(outcome.doc())));
      } else {
        ctx.output(REJECT_TAG, outcome.reject());
        ctx.output(LINEAGE_TAG, RecordRouter.lineageOf(outcome, "variant-flink-datastream-batch-job"));
      }
    }
  }

  private abstract static class JdbcSink<T> extends RichSinkFunction<T> {
    final String url;
    final String user;
    final String password;
    transient Connection conn;
    transient PreparedStatement stmt;

    JdbcSink(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) throws SQLException {
      conn = DriverManager.getConnection(url, user, password);
      conn.setAutoCommit(true);
      stmt = conn.prepareStatement(insertSql());
    }

    abstract String insertSql();

    @Override
    public void close() throws SQLException {
      if (stmt != null) {
        stmt.close();
      }
      if (conn != null) {
        conn.close();
      }
    }
  }

  private static final class TargetSink extends JdbcSink<TargetRow> {
    TargetSink(String url, String user, String password) {
      super(url, user, password);
    }

    @Override
    String insertSql() {
      return "INSERT INTO etlr_transformation.account_target(run_id, account_id, account_key, doc_json) "
          + "VALUES (?, ?, ?, ?)";
    }

    @Override
    public void invoke(TargetRow row, Context context) throws Exception {
      stmt.setString(1, row.runId());
      stmt.setString(2, row.accountId());
      stmt.setString(3, row.accountKey());
      stmt.setString(4, row.docJson());
      stmt.executeUpdate();
    }
  }

  private static final class RejectSink extends JdbcSink<Reject> {
    RejectSink(String url, String user, String password) {
      super(url, user, password);
    }

    @Override
    String insertSql() {
      return "INSERT INTO etlr_recon.reject_log(run_id, batch_id, source_key, stage, reason, detail, raw_record) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public void invoke(Reject r, Context context) throws Exception {
      stmt.setString(1, r.runId());
      stmt.setLong(2, r.batchId());
      stmt.setString(3, r.sourceKey());
      stmt.setString(4, r.stage());
      stmt.setString(5, r.reason());
      stmt.setString(6, r.detail());
      stmt.setString(7, r.rawRecord());
      stmt.executeUpdate();
    }
  }

  private static final class LineageSink extends JdbcSink<Lineage> {
    LineageSink(String url, String user, String password) {
      super(url, user, password);
    }

    @Override
    String insertSql() {
      return "INSERT INTO etlr_recon.record_lineage"
          + "(run_id, source_key, account_key, door, stage, reason, detail, source_file) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public void invoke(Lineage l, Context context) throws Exception {
      stmt.setString(1, l.runId());
      stmt.setString(2, l.sourceKey());
      stmt.setString(3, l.accountKey());
      stmt.setString(4, l.door());
      stmt.setString(5, l.stage());
      stmt.setString(6, l.reason());
      stmt.setString(7, l.detail());
      stmt.setString(8, l.sourceFile());
      stmt.executeUpdate();
    }
  }

  /**
   * Kafka sink — key {@code account_key}, value {@code doc_json}, plain at-least-once
   * (no transactions: batch has no checkpoints to coordinate them with). Target System's mock
   * dedups on this same key, which is what makes a replay safe.
   */
  private static final class KafkaTargetSink extends RichSinkFunction<TargetRow> {
    private final String bootstrap;
    private final String topic;
    private transient KafkaProducer<String, String> producer;

    KafkaTargetSink(String bootstrap, String topic) {
      this.bootstrap = bootstrap;
      this.topic = topic;
    }

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
      producer = new KafkaProducer<>(KafkaClients.producerProps(bootstrap));
    }

    @Override
    public void invoke(TargetRow row, Context context) throws Exception {
      producer.send(new ProducerRecord<>(topic, row.accountKey(), row.docJson())).get();
    }

    @Override
    public void close() {
      if (producer != null) {
        producer.flush();
        producer.close(java.time.Duration.ofSeconds(5));
      }
    }
  }

  private static void deleteRunId(String url, String user, String password, String table, String runId) {
    String sql = "DELETE FROM " + table + " WHERE run_id = ?";
    try (Connection conn = DriverManager.getConnection(url, user, password);
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, runId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("could not clear prior rows for run_id=" + runId + " from " + table, e);
    }
  }
}
