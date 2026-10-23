package mig.jdbclane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import mig.gatejdbc.GateJdbc;
import mig.gatejdbc.ReconJdbc;
import mig.sql.CurateEnrichJob;
import mig.sql.JdbcIntakeJob;
import mig.sql.ProduceJob;

/** The whole flink-jdbc lane with Redpanda and the Target System mock: 53 = 50 + 3, settled. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcLaneSettleIT {

  private static final String SRC = "etlr-settle-it-source";
  private static final String LANE = "etlr-settle-it-lane";
  private static final String RP = "etlr-settle-it-redpanda";
  private static final int RP_PORT = 19098;
  private static final int MOCK_PORT = 18101;
  private static final String SRC_URL = Podman.jdbcUrl(15458, "source");
  private static final String LANE_URL = Podman.jdbcUrl(15459, "mig");
  private static final String KAFKA = "localhost:" + RP_PORT;
  private static final String TARGET_TOPIC = "target-system-target";
  private static final String CONFIRM_TOPIC = "target-system-confirmations";
  private static final String AS_OF_DATE = "2026-09-07";
  private static boolean podman;
  private static Process mock;

  @BeforeAll
  static void start() throws Exception {
    podman = Podman.available();
    assumeTrue(podman, "podman not on PATH — skipping flink-jdbc lane settle IT");
    Podman.startSource(SRC, 15458);
    Podman.startLane(LANE, 15459);
    Podman.run("podman", "rm", "-f", RP);
    Podman.run("podman", "run", "--rm", "-d", "--name", RP, "-p", RP_PORT + ":9092",
        "docker.io/redpandadata/redpanda:latest", "redpanda", "start",
        "--smp=1", "--memory=512M", "--overprovisioned", "--node-id=0", "--check=false",
        "--kafka-addr=internal://0.0.0.0:29092,external://0.0.0.0:9092",
        "--advertise-kafka-addr=internal://127.0.0.1:29092,external://localhost:" + RP_PORT);
    boolean healthy = false;
    for (int i = 0; i < 40 && !healthy; i++) {
      Process p = new ProcessBuilder("podman", "exec", RP, "rpk", "cluster", "health").redirectErrorStream(true).start();
      healthy = new String(p.getInputStream().readAllBytes()).replaceAll("\\s+", " ").contains("Healthy: true");
      p.waitFor();
      if (!healthy) {
        Thread.sleep(1000);
      }
    }
    assertThat(healthy).as("redpanda became healthy").isTrue();
    Podman.run("podman", "exec", RP, "rpk", "topic", "create", TARGET_TOPIC, CONFIRM_TOPIC);

    ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
        "-cp", System.getProperty("java.class.path"), "mig.mock.TargetSystemMock");
    pb.environment().put("KAFKA_BOOTSTRAP", KAFKA);
    pb.environment().put("TARGET_SYSTEM_TARGET_TOPIC", TARGET_TOPIC);
    pb.environment().put("TARGET_SYSTEM_CONFIRMATION_TOPIC", CONFIRM_TOPIC);
    pb.environment().put("TARGET_SYSTEM_PORT", String.valueOf(MOCK_PORT));
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
    mock = pb.start();
    boolean ready = false;
    for (int i = 0; i < 20 && !ready; i++) {
      try {
        ready = ((java.net.HttpURLConnection) new java.net.URL("http://localhost:" + MOCK_PORT + "/__admin/health")
            .openConnection()).getResponseCode() == 200;
      } catch (IOException e) {
        Thread.sleep(500);
      }
    }
    assertThat(ready).as("target-system-mock became ready").isTrue();
  }

  @AfterAll
  static void stop() throws Exception {
    if (mock != null) {
      mock.destroy();
      mock.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
    }
    if (podman) {
      Podman.remove(SRC, LANE, RP);
    }
  }

  @Test
  @Order(1)
  void theLaneBalances53To50Plus3AndEveryDocumentSettles() throws Exception {
    httpPost("/__admin/reset");
    String run = "run-jdbclane-clean";
    long srcRead = runLane(run);
    assertThat(srcRead).isEqualTo(53);
    waitForConfirmations(50);
    assertThat(runRecon(run, srcRead, true)).as("recon exit code, clean run").isZero();
    try (Connection lane = DriverManager.getConnection(LANE_URL, "mig", "mig");
        Statement st = lane.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT src_read, extraction_written, rejected, balanced FROM etlr_recon.run_ledger WHERE run_id = '" + run + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3))).containsExactly(53L, 50L, 3L);
      assertThat(rs.getBoolean(4)).isTrue();
    }
  }

  @Test
  @Order(2)
  void aSuppressedConfirmationFailsTheRun() throws Exception {
    httpPost("/__admin/reset");
    httpPost("/__admin/suppress-next-confirmation");
    String run = "run-jdbclane-suppressed";
    long srcRead = runLane(run);
    waitForConfirmations(49);
    assertThat(runRecon(run, srcRead, true)).as("recon exit code, one document unconfirmed").isEqualTo(1);
  }

  /** The spec's IdempotentReRunJdbcIT: it needs this class's Kafka + mock, so it lives here. */
  @Test
  @Order(3)
  void aReplayOfTheSameRunIdReplacesItsRows() throws Exception {
    httpPost("/__admin/reset");
    String run = "run-jdbclane-replay";
    runLane(run);
    waitForConfirmations(50);
    long srcRead = runLane(run);
    try (Connection lane = DriverManager.getConnection(LANE_URL, "mig", "mig"); Statement st = lane.createStatement()) {
      assertThat(count(st, "etlr_extraction.account_src WHERE _run_id = '" + run + "'")).isEqualTo(52);
      assertThat(count(st, "etlr_transformation.account_target WHERE run_id = '" + run + "'")).isEqualTo(50);
      assertThat(count(st, "etlr_recon.reject_log WHERE run_id = '" + run + "'")).isEqualTo(3);
      assertThat(count(st, "etlr_recon.source_read WHERE run_id = '" + run + "'")).isEqualTo(1);
    }
    assertThat(runRecon(run, srcRead, false)).as("recon balance after a replay").isZero();
  }

  private static long runLane(String run) throws Exception {
    Podman.loadFixture(SRC, run);
    GateJdbc.Result gate;
    try (Connection src = DriverManager.getConnection(SRC_URL, Podman.READER, Podman.READER_PASSWORD)) {
      gate = GateJdbc.check(src, run);
    }
    assertThat(gate.outcome()).as("gate for " + run + ": " + gate.problems()).isEqualTo(GateJdbc.Outcome.PASSED);
    String root = Podman.ROOT.toString();
    JdbcIntakeJob.main(new String[] {run, SRC_URL, Podman.READER, Podman.READER_PASSWORD, LANE_URL, "mig", "mig", root});
    CurateEnrichJob.main(new String[] {run, AS_OF_DATE, LANE_URL, "mig", "mig", root});
    ProduceJob.main(new String[] {run, LANE_URL, "mig", "mig", KAFKA, TARGET_TOPIC});
    try (Connection src = DriverManager.getConnection(SRC_URL, Podman.READER, Podman.READER_PASSWORD);
        Connection lane = DriverManager.getConnection(LANE_URL, "mig", "mig")) {
      ReconJdbc.Result check = ReconJdbc.compare(src, lane, run);
      assertThat(check.ok()).as("recon-jdbc for " + run + ": " + check.problems()).isTrue();
    }
    return gate.srcRead();
  }

  private static long count(Statement st, String fromWhere) throws Exception {
    try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + fromWhere)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static int runRecon(String run, long srcRead, boolean withSettle) throws Exception {
    List<String> cmd = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java",
        "-cp", System.getProperty("java.class.path"), "mig.recon.ReconService",
        run, String.valueOf(srcRead), LANE_URL, "mig", "mig"));
    if (withSettle) {
      cmd.addAll(List.of(KAFKA, CONFIRM_TOPIC, "20"));
    }
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    System.out.println("[recon output for " + run + "]\n" + out);
    return exit;
  }

  private static void waitForConfirmations(long expected) throws Exception {
    for (int i = 0; i < 40; i++) {
      if (((Number) httpGetJson("/__admin/stats").get("confirmationsPublished")).longValue() >= expected) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private static void httpPost(String path) throws IOException {
    var conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + MOCK_PORT + path).openConnection();
    conn.setRequestMethod("POST");
    conn.getResponseCode();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> httpGetJson(String path) throws IOException {
    var conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + MOCK_PORT + path).openConnection();
    return new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream().readAllBytes(), Map.class);
  }
}
