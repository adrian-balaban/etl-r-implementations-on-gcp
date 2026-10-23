package mig.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import mig.sql.CurateEnrichJob;
import mig.sql.IntakeJob;
import mig.sql.ProduceJob;

/**
 * The settle loop for the SQL variant — the half {@link SettleLoopIT} could not
 * prove, because until now only the DataStream variant had a Kafka sink.
 *
 * <p>Why this test exists at all: the lane's output must end in Kafka, and the design
 * puts the sink in the SQL lane specifically
 * (`upsert-kafka`, key = account_key). The SQL variant is also the ONLY variant deployed to
 * GCP (`gcp/flink-deployments/*` run `mig.sql.*`), so a settle loop proven only through
 * `DataStreamJob` proved it for a lane that never ships. This runs the real three SQL jobs
 * against a real broker and a real target-system-mock, then makes `recon` pass or fail on
 * what the external system actually confirmed.
 *
 * <p>Skipped (not failed) when {@code podman} is not on PATH.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlLaneSettleIT {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String PG_CONTAINER = "mig-sqlsettle-it-postgres";
  private static final String RP_CONTAINER = "mig-sqlsettle-it-redpanda";
  private static final int PG_PORT = 15439;
  private static final int RP_PORT = 19095;
  private static final int MOCK_PORT = 18099;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PG_PORT + "/mig";
  private static final String KAFKA_BOOTSTRAP = "localhost:" + RP_PORT;
  private static final String TARGET_TOPIC = "target-system-target";
  private static final String CONFIRM_TOPIC = "target-system-confirmations";
  private static final String AS_OF_DATE = "2026-09-07";
  private static boolean podmanAvailable;
  private static Process mockProcess;

  @BeforeAll
  static void startInfra() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping live SQL-lane settle IT");

    run("podman", "rm", "-f", PG_CONTAINER);
    run("podman", "rm", "-f", RP_CONTAINER);
    run(
        "podman", "run", "--rm", "-d", "--name", PG_CONTAINER,
        "-e", "POSTGRES_USER=mig", "-e", "POSTGRES_PASSWORD=mig", "-e", "POSTGRES_DB=mig",
        "-p", PG_PORT + ":5432", "docker.io/library/postgres:16");
    run(
        "podman", "run", "--rm", "-d", "--name", RP_CONTAINER,
        "-p", RP_PORT + ":9092",
        "docker.io/redpandadata/redpanda:latest", "redpanda", "start",
        "--smp=1", "--memory=512M", "--overprovisioned", "--node-id=0", "--check=false",
        "--kafka-addr=internal://0.0.0.0:29092,external://0.0.0.0:9092",
        "--advertise-kafka-addr=internal://127.0.0.1:29092,external://localhost:" + RP_PORT);

    boolean pgReady = false;
    for (int i = 0; i < 30 && !pgReady; i++) {
      pgReady = run("podman", "exec", PG_CONTAINER, "pg_isready", "-U", "mig", "-d", "mig") == 0;
      if (!pgReady) {
        Thread.sleep(1000);
      }
    }
    assertThat(pgReady).as("postgres became ready").isTrue();

    boolean rpReady = false;
    for (int i = 0; i < 40 && !rpReady; i++) {
      rpReady =
          runCapture("podman", "exec", RP_CONTAINER, "rpk", "cluster", "health")
              .replaceAll("\\s+", " ")
              .contains("Healthy: true");
      if (!rpReady) {
        Thread.sleep(1000);
      }
    }
    assertThat(rpReady).as("redpanda became healthy").isTrue();
    run("podman", "exec", RP_CONTAINER, "rpk", "topic", "create", TARGET_TOPIC, CONFIRM_TOPIC);

    Path ddlOnHost = Files.createTempFile("postgres-init", ".sql");
    Files.writeString(ddlOnHost, Files.readString(ROOT.resolve("sql/postgres-init.sql")));
    run("podman", "cp", ddlOnHost.toString(), PG_CONTAINER + ":/tmp/init.sql");
    int rc = run("podman", "exec", PG_CONTAINER, "psql", "-U", "mig", "-d", "mig", "-f", "/tmp/init.sql");
    assertThat(rc).as("schema DDL applied").isEqualTo(0);

    ProcessBuilder pb =
        new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java");
    pb.command().addAll(CoverageAgent.jvmArgs());
    pb.command().addAll(List.of("-cp", System.getProperty("java.class.path"), "mig.mock.TargetSystemMock"));
    pb.environment().put("KAFKA_BOOTSTRAP", KAFKA_BOOTSTRAP);
    pb.environment().put("TARGET_SYSTEM_TARGET_TOPIC", TARGET_TOPIC);
    pb.environment().put("TARGET_SYSTEM_CONFIRMATION_TOPIC", CONFIRM_TOPIC);
    pb.environment().put("TARGET_SYSTEM_PORT", String.valueOf(MOCK_PORT));
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    mockProcess = pb.start();

    boolean mockReady = false;
    for (int i = 0; i < 20 && !mockReady; i++) {
      try {
        mockReady =
            ((java.net.HttpURLConnection)
                    new java.net.URL("http://localhost:" + MOCK_PORT + "/__admin/health").openConnection())
                .getResponseCode()
                == 200;
      } catch (IOException e) {
        Thread.sleep(500);
      }
    }
    assertThat(mockReady).as("target-system-mock became ready").isTrue();
  }

  @AfterAll
  static void stopInfra() {
    if (mockProcess != null) {
      mockProcess.destroy(); // SIGTERM, not SIGKILL: JaCoCo dumps its data in a shutdown hook
      try { if (!mockProcess.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) mockProcess.destroyForcibly(); } catch (InterruptedException e) { mockProcess.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    if (podmanAvailable) {
      try {
        run("podman", "rm", "-f", PG_CONTAINER);
        run("podman", "rm", "-f", RP_CONTAINER);
      } catch (Exception ignored) {
        // best-effort teardown
      }
    }
  }

  @Test
  @Order(1)
  void sqlLaneReachesKafkaAndEveryDocumentSettles() throws Exception {
    httpPost("/__admin/reset");
    String runId = "run-sqllane-clean";
    runSqlLane(runId);

    // 50 documents on the topic, 50 accepted by the external system, 50 confirmations back.
    waitForConfirmations(50);
    Map<String, Object> stats = httpGetJson("/__admin/stats");
    assertThat(((Number) stats.get("received")).longValue())
        .as("documents the SQL variant's upsert-kafka sink actually published")
        .isEqualTo(50);
    assertThat(((Number) stats.get("accepted")).longValue()).isEqualTo(50);
    assertThat(((Number) stats.get("duplicatesIgnored")).longValue()).isZero();

    // The counters above are in-memory and per process, and on GCP they did
    // not add up (252 received after six 50-message runs) with nothing to say why. The stats now
    // report the consumer's real Kafka position, so the counters can be CHECKED against it: once
    // caught up, what the process counted must equal how far its consumer actually advanced, and
    // there must be nothing left on the topic.
    Map<String, Object> settled = awaitCaughtUp();
    assertThat(((Number) settled.get("assignedPartitions")).intValue()).isEqualTo(1);
    assertThat(((Number) settled.get("lag")).longValue()).as("nothing left unconsumed on the topic").isZero();
    assertThat(((Number) settled.get("positionSinceStart")).longValue())
        .as("the consumer advanced exactly as far as the process counted")
        .isEqualTo(((Number) settled.get("consumedFromTopic")).longValue())
        .isEqualTo(50L);
    assertThat(settled.get("instanceId")).as("one process throughout").isEqualTo(stats.get("instanceId"));

    assertThat(runRecon(runId, 55, true))
        .as("recon exit code on a clean, fully-confirmed SQL-lane run")
        .isEqualTo(0);

    //  asks recon for a JSON + HTML report; until this session it printed to
    // stdout and left no artefact behind. Asserting the files exist keeps that from
    // regressing into "the code is there but nothing writes".
    Path reportDir = Path.of("work/recon");
    assertThat(reportDir.resolve("recon-" + runId + ".json")).exists();
    assertThat(reportDir.resolve("recon-" + runId + ".html")).exists();
    assertThat(Files.readString(reportDir.resolve("recon-" + runId + ".json")))
        .contains("\"ok\" : true")
        .contains("\"confirmed\" : 50");
  }

  @Test
  @Order(2)
  void suppressedConfirmationFailsTheSqlLaneRun() throws Exception {
    httpPost("/__admin/reset");
    httpPost("/__admin/suppress-next-confirmation");
    String runId = "run-sqllane-suppressed";
    runSqlLane(runId);
    waitForConfirmations(49); // one deliberately suppressed

    assertThat(runRecon(runId, 55, true))
        .as("recon exit code with one document sent-but-not-confirmed")
        .isEqualTo(1);
  }

  @Test
  @Order(3)
  void replayOfTheSqlLaneProducesDuplicatesNotNewAccounts() throws Exception {
    httpPost("/__admin/reset");
    String runId = "run-sqllane-replay";
    runSqlLane(runId);
    waitForConfirmations(50);

    // The whole SQL lane again, same run_id. Unlike SettleLoopIT's replay (which re-ran only
    // the Kafka path), this re-runs the Postgres writes too — which is safe precisely
    // because of deleteRunId, and is what a Cloud Run Job retry actually does.
    runSqlLane(runId);
    Thread.sleep(3000);

    Map<String, Object> stats = httpGetJson("/__admin/stats");
    assertThat(((Number) stats.get("received")).longValue()).isEqualTo(100);
    assertThat(((Number) stats.get("accepted")).longValue()).isEqualTo(50);
    assertThat(((Number) stats.get("duplicatesIgnored")).longValue())
        .as("the replay half: 50 duplicate, not 50 new accounts")
        .isEqualTo(50);
    assertThat(((Number) stats.get("distinctAccounts")).longValue()).isEqualTo(50);

    // The replay must not have doubled Postgres either.
    try (var conn = java.sql.DriverManager.getConnection(JDBC_URL, "mig", "mig");
        var st = conn.createStatement();
        var rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM etlr_transformation.account_target WHERE run_id='" + runId + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).as("account_target rows after a full replay").isEqualTo(50);
    }
  }

  private static void runSqlLane(String runId) throws Exception {
    IntakeJob.main(
        new String[] {
          ROOT.resolve("fixtures/ACCOUNT.src").toString(), runId, JDBC_URL, "mig", "mig", ROOT.toString()
        });
    CurateEnrichJob.main(new String[] {runId, AS_OF_DATE, JDBC_URL, "mig", "mig", ROOT.toString()});
    ProduceJob.main(
        new String[] {runId, JDBC_URL, "mig", "mig", KAFKA_BOOTSTRAP, TARGET_TOPIC});
  }

  /** Polls /__admin/stats until the mock's position snapshot (refreshed ~1/s) shows it has caught up. */
  private static Map<String, Object> awaitCaughtUp() throws Exception {
    Map<String, Object> stats = httpGetJson("/__admin/stats");
    for (int i = 0; i < 40; i++) {
      stats = httpGetJson("/__admin/stats");
      if (((Number) stats.get("assignedPartitions")).intValue() > 0
          && ((Number) stats.get("lag")).longValue() == 0
          && ((Number) stats.get("positionSinceStart")).longValue() == ((Number) stats.get("consumedFromTopic")).longValue()) {
        return stats;
      }
      Thread.sleep(500);
    }
    return stats;
  }

  private static void waitForConfirmations(long expected) throws Exception {
    for (int i = 0; i < 40; i++) {
      Map<String, Object> stats = httpGetJson("/__admin/stats");
      if (((Number) stats.get("confirmationsPublished")).longValue() >= expected) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private static int runRecon(String runId, long srcRead, boolean withSettle) throws Exception {
    List<String> args =
        new java.util.ArrayList<>(List.of(runId, String.valueOf(srcRead), JDBC_URL, "mig", "mig"));
    if (withSettle) {
      args.addAll(List.of(KAFKA_BOOTSTRAP, CONFIRM_TOPIC, "20"));
    }
    ProcessBuilder pb =
        new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java");
    pb.command().addAll(CoverageAgent.jvmArgs());
    pb.command().addAll(List.of("-cp", System.getProperty("java.class.path"), "mig.recon.ReconService"));
    pb.command().addAll(args);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    System.out.println("[recon output for " + runId + "]\n" + output);
    return exit;
  }

  private static void httpPost(String path) throws IOException {
    var conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + MOCK_PORT + path).openConnection();
    conn.setRequestMethod("POST");
    conn.getResponseCode();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> httpGetJson(String path) throws IOException {
    var conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + MOCK_PORT + path).openConnection();
    byte[] body = conn.getInputStream().readAllBytes();
    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
  }

  private static boolean commandExists(String cmd) {
    try {
      return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static int run(String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  private static String runCapture(String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out;
  }
}
