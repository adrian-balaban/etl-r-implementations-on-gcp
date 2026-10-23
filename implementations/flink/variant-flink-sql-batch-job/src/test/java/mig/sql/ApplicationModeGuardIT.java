package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.flink.client.program.StreamContextEnvironment;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.core.execution.DefaultExecutorServiceLoader;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 *  guard (C2) — the one the fix's own plan flagged as "verify the API
 * before estimating" and then skipped, leaving (C1)'s text-level check as the only defence.
 *
 * <p>What it does: installs the same {@code StreamContextEnvironment} with
 * {@code enforceSingleJobExecution = true} that Flink's application-mode entrypoint
 * ({@code KubernetesApplicationClusterEntrypoint}, what the Flink Kubernetes Operator runs)
 * installs, then runs the real jobs under it. A second job submission from one program —
 * the CurateEnrichJob bug, in any future form — fails here, in {@code mvn test}, instead of
 * on a GKE cluster forty minutes into a deploy.
 *
 * <p>Why (C1) is not enough on its own: it parses the {@code .sql.template} files, so it
 * catches a template that would submit twice. It cannot catch a second {@code execute()}
 * written directly in a job's {@code main()}. This can.
 *
 * <p>The first test is a NEGATIVE CONTROL and it earns its place: it proves the guard is
 * actually armed. Without it, a wrong argument order in {@code setAsContext} (the two
 * trailing booleans are easy to swap) would leave every other assertion here passing
 * vacuously — a test that cannot fail, which is the exact failure mode is about.
 */
class ApplicationModeGuardIT {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String CONTAINER = "mig-appmode-guard-it-postgres";
  private static final int PORT = 15441;
  private static final String JDBC_URL = "jdbc:postgresql://localhost:" + PORT + "/mig";
  private static final String RUN_ID = "run-appmode-guard";
  private static final String AS_OF_DATE = "2026-09-07";
  private static boolean podmanAvailable;

  @BeforeAll
  static void startPostgres() throws Exception {
    podmanAvailable = commandExists("podman");
    assumeTrue(podmanAvailable, "podman not on PATH — skipping application-mode guard IT");

    run("podman", "rm", "-f", CONTAINER);
    run(
        "podman", "run", "--rm", "-d", "--name", CONTAINER,
        "-e", "POSTGRES_USER=mig", "-e", "POSTGRES_PASSWORD=mig", "-e", "POSTGRES_DB=mig",
        "-p", PORT + ":5432", "docker.io/library/postgres:16");
    boolean ready = false;
    for (int i = 0; i < 30 && !ready; i++) {
      ready = run("podman", "exec", CONTAINER, "pg_isready", "-U", "mig", "-d", "mig") == 0;
      if (!ready) {
        Thread.sleep(1000);
      }
    }
    assertThat(ready).as("postgres became ready").isTrue();

    Path ddlOnHost = Files.createTempFile("postgres-init", ".sql");
    Files.writeString(ddlOnHost, Files.readString(ROOT.resolve("sql/postgres-init.sql")));
    run("podman", "cp", ddlOnHost.toString(), CONTAINER + ":/tmp/init.sql");
    assertThat(run("podman", "exec", CONTAINER, "psql", "-U", "mig", "-d", "mig", "-f", "/tmp/init.sql"))
        .as("schema DDL applied")
        .isZero();
  }

  @AfterAll
  static void stopPostgres() throws Exception {
    if (podmanAvailable) {
      run("podman", "rm", "-f", CONTAINER);
    }
  }

  @AfterEach
  void disarm() {
    StreamContextEnvironment.unsetAsContext();
  }

  /**
   * Arms exactly what application mode arms. The two trailing booleans are, in order,
   * {@code enforceSingleJobExecution} and {@code suppressSysout} — asserted by the negative
   * control below rather than taken on faith from the signature.
   */
  private static void armApplicationModeGuard() {
    Configuration conf = new Configuration();
    conf.set(DeploymentOptions.TARGET, "local");
    conf.set(DeploymentOptions.ATTACHED, true);
    StreamContextEnvironment.setAsContext(
        new DefaultExecutorServiceLoader(),
        conf,
        ApplicationModeGuardIT.class.getClassLoader(),
        true,
        false);
  }

  @Test
  void negativeControl_twoSubmissionsFromOneProgramAreRejected() {
    armApplicationModeGuard();
    assertThatThrownBy(
            () -> {
              StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
              env.fromData(1, 2, 3).print();
              env.execute("first");
              env.fromData(4, 5, 6).print();
              env.execute("second"); // application mode forbids this
            })
        .as("the guard must actually be armed, or every other assertion here is vacuous")
        .hasStackTraceContaining("Cannot have more than one execute() or executeAsync() call");
  }

  @Test
  void allThreeSqlJobsSubmitExactlyOneJobEach() throws Exception {
    // Each job runs in its own armed context, exactly as application mode runs it: one
    // program, one submission. CurateEnrichJob is the one that failed this in
    for (Runnable job :
        java.util.List.of(
            (Runnable) () -> call(() -> IntakeJob.main(new String[] {
                ROOT.resolve("fixtures/ACCOUNT.src").toString(), RUN_ID, JDBC_URL, "mig", "mig", ROOT.toString()})),
            () -> call(() -> CurateEnrichJob.main(new String[] {
                RUN_ID, AS_OF_DATE, JDBC_URL, "mig", "mig", ROOT.toString()})),
            () -> call(() -> ProduceJob.main(new String[] {RUN_ID, JDBC_URL, "mig", "mig"})))) {
      armApplicationModeGuard();
      try {
        job.run();
      } finally {
        StreamContextEnvironment.unsetAsContext();
      }
    }

    // And the lane still produced the right answer under the guard, not just "did not throw".
    try (var conn = java.sql.DriverManager.getConnection(JDBC_URL, "mig", "mig");
        var st = conn.createStatement()) {
      assertThat(count(st, "SELECT COUNT(*) FROM etlr_extraction.account_src WHERE _run_id='" + RUN_ID + "'"))
          .isEqualTo(52);
      assertThat(count(st, "SELECT COUNT(*) FROM etlr_transformation.account_enriched WHERE run_id='" + RUN_ID + "'"))
          .isEqualTo(52);
      assertThat(count(st, "SELECT COUNT(*) FROM etlr_transformation.account_target WHERE run_id='" + RUN_ID + "'"))
          .isEqualTo(50);
      assertThat(count(st, "SELECT COUNT(*) FROM etlr_recon.reject_log WHERE run_id='" + RUN_ID + "'"))
          .isEqualTo(5);
    }
  }

  private static int count(java.sql.Statement st, String sql) throws Exception {
    try (var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void call(ThrowingRunnable r) {
    try {
      r.run();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean commandExists(String cmd) throws Exception {
    return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
  }

  private static int run(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }
}
