package mig.recon;

import java.sql.Connection;
import java.util.ArrayList;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mig.common.KafkaClients;

/**
 * Reconciliation — the balancing equation across the whole
 * lane, plus the settle loop against Target System's confirmation stream.
 *
 * <p>A trimmed port of {@code apps/recon-service/…/ReconService.java}: the vendored original
 * also reconciles the Extractor's {@code .RPT} artefact and a GCS-based Loader report,
 * neither of which exists in this repo; what is
 * kept is exactly the two checks: {@code src_read = written + rejected},
 * and every {@code account_target} row confirmed by Target System.
 *
 * <p>Args: {@code <runId> <jdbcUrl> <jdbcUser> <jdbcPassword>
 * [kafkaBootstrap confirmationTopic timeoutSeconds]}
 */
public final class ReconService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    if (args.length != 5 && args.length != 8) {
      System.err.println(
          "usage: ReconService <runId> <srcRead> <jdbcUrl> <jdbcUser> <jdbcPassword> "
              + "[kafkaBootstrap confirmationTopic timeoutSeconds]");
      System.exit(2);
    }
    String runId;
    try {
      runId = mig.common.RunId.require(args[0]);
    } catch (IllegalArgumentException e) {
      System.err.println("recon: " + e.getMessage());
      System.exit(2);
      return;
    }
    long srcRead = Long.parseLong(args[1]);
    String jdbcUrl = args[2];
    String jdbcUser = args[3];
    String jdbcPassword = mig.common.ConnectionArgs.resolve(args[4]);
    String kafkaBootstrap = args.length == 8 ? args[5] : null;
    String confirmationTopic = args.length == 8 ? args[6] : null;
    int timeoutSeconds = args.length == 8 ? Integer.parseInt(args[7]) : 30;

    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
      BalanceResult balance = checkBalance(conn, runId, srcRead);
      writeRunLedger(conn, runId, balance); // closes  debt #2 — run_ledger had no writer

      System.out.printf(
          "recon: src_read=%d written=%d rejected=%d balanced=%s%n",
          balance.srcRead, balance.written, balance.rejected, balance.balanced);

      List<StageCheck> stages = checkStages(conn, runId, srcRead);
      boolean stagesHold = true;
      for (StageCheck c : stages) {
        System.out.printf("recon: stage %-7s %s -> %d vs %d %s%n", c.stage(), c.equation(), c.lhs(), c.rhs(), c.holds() ? "ok" : "BROKEN");
        stagesHold &= c.holds();
      }
      if (stages.isEmpty()) {
        System.out.println("recon: per-stage equations skipped (no account_src rows — single-pass variant)");
      }

      BatchResult batches = checkBatches(conn, runId);
      System.out.printf(
          "recon: batches=%d (size 200, last=%d) consistent=%s%n",
          batches.batches(), batches.lastBatchSize(), batches.consistent());

      boolean ok = balance.balanced && stagesHold;
      if (!stagesHold) {
        System.err.println("RECONCILIATION FAILED: a per-stage equation does not close — see the 'stage' lines above");
      }
      if (!balance.balanced) {
        System.err.printf(
            "RECONCILIATION FAILED: %d != %d + %d (off by %d)%n",
            balance.srcRead, balance.written, balance.rejected,
            balance.srcRead - (balance.written + balance.rejected));
      }

      long reportTargetRows = 0;
      long reportConfirmedRows = 0;
      Set<String> reportUnconfirmed = Set.of();
      if (kafkaBootstrap != null) {
        SettleResult settle =
            settle(conn, runId, kafkaBootstrap, confirmationTopic, timeoutSeconds);
        reportTargetRows = settle.targetRows;
        reportConfirmedRows = settle.confirmedRows;
        reportUnconfirmed = settle.unconfirmedKeys;
        System.out.printf(
            "recon: target rows=%d confirmed=%d unconfirmed=%d%n",
            settle.targetRows, settle.confirmedRows, settle.unconfirmedKeys.size());
        if (!settle.unconfirmedKeys.isEmpty()) {
          System.err.println(
              "RECONCILIATION FAILED: unconfirmed account keys: " + new TreeSet<>(settle.unconfirmedKeys));
          ok = false;
        }
      } else {
        System.out.println(
            "recon: target system reconciliation skipped (no kafkaBootstrap given)");
      }

      Report.write(
          java.nio.file.Path.of(System.getenv().getOrDefault("RECON_REPORT_DIR", "work/recon")),
          runId, balance, batches, stages, reportTargetRows, reportConfirmedRows, reportUnconfirmed,
          kafkaBootstrap != null, ok);

      System.exit(ok ? 0 : 1);
    }
  }

  // ── the balancing equation  ────────────────────────────────────────────

  public record BalanceResult(long srcRead, long written, long rejected, boolean balanced) {}

  /**
   * {@code srcRead} is given, not derived from {@code account_src}: that table is written
   * only by the SQL variant's intake job — the DataStream variant
   * runs parse -&gt; map -&gt; schema in one pass and never materialises it. Taking the source
   * line count as an argument (the harness/caller counts the non-header lines once) keeps
   * this check meaningful and correct for both variants, at the cost of the extra
   * cross-check the vendored original had (account_src count vs. an independent extraction
   * report) — this repo has no such independent report to check against.
   */
  static BalanceResult checkBalance(Connection conn, String runId, long srcRead) throws Exception {
    long written = count(conn, "SELECT COUNT(*) FROM etlr_transformation.account_target WHERE run_id=?", runId);
    long rejected = count(conn, "SELECT COUNT(*) FROM etlr_recon.reject_log WHERE run_id=?", runId);

    return new BalanceResult(srcRead, written, rejected, srcRead == written + rejected);
  }

  // ── the per-stage equations  ──────────

  /** One stage's equation: {@code lhs} (what the stage accounts for) must equal {@code rhs} (what it was given). */
  public record StageCheck(String stage, String equation, long lhs, long rhs) {
    public boolean holds() {
      return lhs == rhs;
    }
  }

  /**
   *  promised that the equation closes <em>per stage</em>, and put
   * {@code COUNT(account_curated) = COUNT(account_enriched)} in recon. Neither was ever built: recon
   * only checked the end-to-end {@code src_read = written + rejected}. That leaves a hole exactly the
   * size of a stage — a row dropped inside curate and re-created by produce (or a duplicate in one
   * stage cancelled by a loss in the next) balances end to end and is wrong.
   *
   * <p>Four equations, each on the tables one job wrote:
   *
   * <ul>
   *   <li><b>intake</b>: {@code account_src + parse-stage rejects = src_read}
   *   <li><b>curate</b>: {@code account_curated = account_src}
   *   <li><b>enrich</b>: {@code account_enriched = account_curated}
   *   <li><b>produce</b>: {@code account_target + map/schema rejects = account_enriched}
   * </ul>
   *
   * <p>Returns an empty list when {@code account_src} has no rows for the run: the DataStream variant
   * routes parse -&gt; map -&gt; schema in one pass and never materialises the intermediate tables, so
   * there is nothing per stage to check, and inventing zeros would report a false failure.
   */
  public static List<StageCheck> checkStages(Connection conn, String runId, long srcRead) throws Exception {
    long src = count(conn, "SELECT COUNT(*) FROM etlr_extraction.account_src WHERE _run_id=?", runId);
    if (src == 0) {
      return List.of();
    }
    long parseRejects = count(conn, "SELECT COUNT(*) FROM etlr_recon.reject_log WHERE run_id=? AND stage='parse'", runId);
    long curated = count(conn, "SELECT COUNT(*) FROM etlr_transformation.account_curated WHERE run_id=?", runId);
    long enriched = count(conn, "SELECT COUNT(*) FROM etlr_transformation.account_enriched WHERE run_id=?", runId);
    long target = count(conn, "SELECT COUNT(*) FROM etlr_transformation.account_target WHERE run_id=?", runId);
    long laterRejects =
        count(conn, "SELECT COUNT(*) FROM etlr_recon.reject_log WHERE run_id=? AND stage IN ('map','schema')", runId);
    return List.of(
        new StageCheck("intake", "account_src + parse rejects = src_read", src + parseRejects, srcRead),
        new StageCheck("curate", "account_curated = account_src", curated, src),
        new StageCheck("enrich", "account_enriched = account_curated", enriched, curated),
        new StageCheck("produce", "account_target + map/schema rejects = account_enriched", target + laterRejects, enriched));
  }

  /** The result of  batch checks over a run's written documents. */
  public record BatchResult(long written, long batches, long lastBatchSize, boolean consistent) {}

  /**
   *  "loturile de 200", ported — the accounting the vendored Python
   * original did with {@code DIV(ROW_NUMBER() OVER (ORDER BY account_key) - 1, 200)} after
   * the reject gate, and the three things it then verified: every batch but the last holds
   * exactly 200, the batch count is {@code ceil(written / 200)}, and the per-batch sizes sum
   * to the total.
   *
   * <p>Two honest notes. First, this lane's {@code account_target} has no {@code batch_id}
   * column (neither does the oracle's — see fixtures/expected/account_target.tsv), so the
   * numbering is computed here over the written rows rather than stored; recon is where
   * assertions about a finished run belong anyway . Second, the 55-record fixture
   * produces 50 documents, i.e. ONE batch, so the fixture does not exercise any of this —
   * exactly as the plan warned. {@code ReconServiceTest} drives it at 200, 201 and 450
   * instead, which is where the arithmetic can actually be wrong.
   */
  public static BatchResult checkBatches(Connection conn, String runId) throws Exception {
    return checkBatches(conn, runId, 200);
  }

  static BatchResult checkBatches(Connection conn, String runId, int batchSize) throws Exception {
    List<Long> sizes = new ArrayList<>();
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT batch_id, COUNT(*) FROM ("
                + "  SELECT (ROW_NUMBER() OVER (ORDER BY account_key) - 1) / ? AS batch_id"
                + "  FROM etlr_transformation.account_target WHERE run_id = ?"
                + ") b GROUP BY batch_id ORDER BY batch_id")) {
      ps.setInt(1, batchSize);
      ps.setString(2, runId);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          sizes.add(rs.getLong(2));
        }
      }
    }

    long written = sizes.stream().mapToLong(Long::longValue).sum();
    long expectedBatches = (written + batchSize - 1) / batchSize; // ceil
    boolean everyFullBatchIsFull =
        sizes.subList(0, Math.max(0, sizes.size() - 1)).stream().allMatch(n -> n == batchSize);
    boolean consistent =
        sizes.size() == expectedBatches
            && everyFullBatchIsFull
            && (sizes.isEmpty() || sizes.get(sizes.size() - 1) <= batchSize);
    return new BatchResult(
        written, sizes.size(), sizes.isEmpty() ? 0 : sizes.get(sizes.size() - 1), consistent);
  }

  private static void writeRunLedger(Connection conn, String runId, BalanceResult b) throws Exception {
    // Idempotent, for the same reason every Flink job is: recon runs
    // as the last step of an orchestrated sequence, and that sequence gets retried — an
    // Airflow task retry, or a Cloud Run Job execution restarted after a mid-sequence
    // failure. Without this DELETE the ledger accumulates one row per attempt for the same
    // run_id, so "the ledger for this run" stops being a single row and every reader of it
    // has to guess which attempt is authoritative. Found by running recon twice against the
    // same run on the compose stack, which is exactly the retry shape.
    try (PreparedStatement ps =
        conn.prepareStatement("DELETE FROM etlr_recon.run_ledger WHERE run_id = ?")) {
      ps.setString(1, runId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO etlr_recon.run_ledger(run_id, src_read, extraction_written, rejected, balanced, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, runId);
      ps.setLong(2, b.srcRead);
      ps.setLong(3, b.written);
      ps.setLong(4, b.rejected);
      ps.setBoolean(5, b.balanced);
      ps.setDouble(6, Instant.now().toEpochMilli() / 1000.0);
      ps.executeUpdate();
    }
  }

  private static long count(Connection conn, String sql, String runId) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  // ── the settle loop  ───────────────────────────────────

  record SettleResult(long targetRows, long confirmedRows, Set<String> unconfirmedKeys) {}

  static SettleResult settle(
      Connection conn, String runId, String bootstrap, String topic, int timeoutSeconds) throws Exception {
    Set<String> targetKeys = new HashSet<>();
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT account_key FROM etlr_transformation.account_target WHERE run_id=?")) {
      ps.setString(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          targetKeys.add(rs.getString(1));
        }
      }
    }

    Set<String> confirmedKeys = readConfirmations(bootstrap, topic, runId, targetKeys, timeoutSeconds);
    Set<String> unconfirmed = new HashSet<>(targetKeys);
    unconfirmed.removeAll(confirmedKeys);

    return new SettleResult(targetKeys.size(), targetKeys.size() - unconfirmed.size(), unconfirmed);
  }

  /** Absolute ceiling on the settle wait, however steadily confirmations keep arriving. */
  static final int MAX_SETTLE_SECONDS = 1800;

  /**
   * A fresh consumer group per call — a re-run of recon must not skip confirmations a previous
   * recon's group committed past.
   *
   * <p>Reads until every EXPECTED key is confirmed, not until the topic's end offset at subscribe
   * time. The end-offset bound was a bug that only volume exposed: at 200,000 documents recon
   * subscribed while the mock was still confirming (3,193 of 200,000 published), the snapshot was
   * "reached" two seconds later, and a healthy run was reported as 196,807 unconfirmed. At 50
   * documents the mock is always already done, so no smaller test could see it.
   *
   * <p>{@code idleSeconds} is a no-progress timeout: it restarts whenever a new confirmation
   * arrives, so a slow-but-moving consumer is waited for and a stalled or dead one is reported
   * after the same delay as before. {@link #MAX_SETTLE_SECONDS} bounds the whole thing.
   */
  private static Set<String> readConfirmations(
      String bootstrap, String topic, String runId, Set<String> expected, int idleSeconds) {
    Set<String> confirmed = new HashSet<>();
    var props = KafkaClients.consumerProps(bootstrap, "recon-" + runId + "-" + System.nanoTime());
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      List<PartitionInfo> partitions = consumer.partitionsFor(topic);
      List<TopicPartition> tps =
          partitions.stream().map(p -> new TopicPartition(topic, p.partition())).toList();
      consumer.assign(tps);
      consumer.seekToBeginning(tps);

      collect(consumer, tps, runId, expected, idleSeconds * 1000L, MAX_SETTLE_SECONDS * 1000L, confirmed);
    } catch (Exception e) {
      System.err.println("recon: confirmation read failed: " + e.getMessage());
    }
    return confirmed;
  }

  /** The read loop, separate from consumer construction so it can be driven by a MockConsumer. */
  static void collect(
      org.apache.kafka.clients.consumer.Consumer<String, String> consumer,
      List<TopicPartition> tps,
      String runId,
      Set<String> expected,
      long idleMillis,
      long maxMillis,
      Set<String> confirmed)
      throws Exception {
    long start = System.currentTimeMillis();
    long lastProgress = start;
    while (!tps.isEmpty() && !confirmed.containsAll(expected)) {
      long now = System.currentTimeMillis();
      if (now - start >= maxMillis || now - lastProgress >= idleMillis) {
        break;
      }
      int before = confirmed.size();
      for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
        JsonNode doc = MAPPER.readTree(record.value());
        if (runId.equals(doc.path("runId").asText(""))) {
          confirmed.add(doc.path("accountKey").asText(record.key()));
        }
      }
      if (confirmed.size() > before) {
        lastProgress = System.currentTimeMillis();
      }
    }
  }
}
