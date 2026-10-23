package mig.gatejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * gcp/orchestrator/run.sh run for real against stub gsutil/gcloud/kubectl/java and the real templates — the
 * pattern of ../flink's OrchestratorBehaviourTest, whose stubs these are.
 */
class JdbcOrchestratorBehaviourTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize(); // implementations/flink-jdbc
  private static final String BUCKET = "test-landing";

  private Path sandbox;
  private Path bin;
  private Path gcs;
  private Path manifests;
  private Path work;
  private Path log;

  record Result(int exit, String out) {}

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(new ProcessBuilder("bash", "-c", "command -v bash").start().waitFor() == 0, "bash not on PATH");
    sandbox = Files.createTempDirectory("jdbc-orchestrator");
    bin = Files.createDirectories(sandbox.resolve("bin"));
    gcs = Files.createDirectories(sandbox.resolve("gcs").resolve(BUCKET));
    work = Files.createDirectories(sandbox.resolve("work"));
    Files.createDirectories(sandbox.resolve("mount"));
    Files.createDirectories(sandbox.resolve("app"));
    // the image's /manifests: this lane's intake + the Flink lane's curate and produce, unchanged
    manifests = Files.createDirectories(sandbox.resolve("manifests"));
    Files.copy(ROOT.resolve("gcp/flink-deployments/flink_intake_jdbc.yaml.template"), manifests.resolve("flink_intake_jdbc.yaml.template"));
    for (String t : List.of("flink_curate_enrich", "flink_produce")) {
      Files.copy(ROOT.resolve("../flink/gcp/flink-deployments/" + t + ".yaml.template").normalize(), manifests.resolve(t + ".yaml.template"));
    }
    log = sandbox.resolve("commands.log");
    Files.writeString(log, "");
    writeStubs();
  }

  // ── the happy path ────────────────────────────────────────────────────────

  @Test
  void aCleanRunGatesSubmitsThreeJobsReconcilesTwiceAndWritesTheDoneMarker() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "READY_AT", "2026-09-07T00:00:00Z"));

    assertThat(r.exit).as(r.out).isZero();
    assertThat(r.out).contains("src_read=53", "orchestrator: run run-a complete");
    assertThat(exists("done/run-a.done")).isTrue();
    assertThat(exists("attempts/run-a.count")).isFalse();
    assertThat(exists("locks/run-a.lock")).isFalse();
    List<String> applied = commandsMatching("kubectl apply -f " + work);
    assertThat(applied).hasSize(3);
    assertThat(applied.get(0)).contains("flink_intake_jdbc");
    assertThat(applied.get(1)).contains("flink_curate_enrich");
    assertThat(applied.get(2)).contains("flink_produce");
    List<String> lines = Files.readAllLines(log);
    int reconJdbc = indexOf(lines, "java recon-jdbc ");
    int recon = indexOf(lines, "java recon ");
    assertThat(reconJdbc).as("recon-jdbc runs").isNotNegative();
    assertThat(recon).as("and recon after it").isGreaterThan(reconJdbc);
    assertThat(lines.get(recon)).contains("run-a 53 ").contains("env:CLOUDSQL_APP_PASSWORD");
  }

  @Test
  void theRenderedManifestsRunTheLanesImageAndJarAndCarryNoPassword() throws Exception {
    run(Map.of("RUN_ID", "run-a", "READY_AT", "2026-09-07T00:00:00Z"));
    for (String job : List.of("flink_intake_jdbc", "flink_curate_enrich", "flink_produce")) {
      String m = Files.readString(work.resolve(job + ".yaml"));
      assertThat(m).as(job)
          .contains("europe-west1-docker.pkg.dev/p/mig-flink/flink-jdbc-job:")
          .contains("local:///opt/flink/usrlib/flink-jdbc-job.jar")
          .doesNotContain("/mig-flink/flink-job:").doesNotContain("usrlib/job.jar")
          .doesNotContain("test-password").doesNotContain("reader-password");
      assertThat(m.lines().filter(l -> !l.strip().startsWith("#")).filter(l -> l.contains("${")))
          .as(job + ": unrendered placeholders").isEmpty();
    }
    assertThat(Files.readString(work.resolve("flink_intake_jdbc.yaml")))
        .contains("jdbc:postgresql://10.0.0.6:5432/source").contains("\"etlr_reader\"").contains("env:SOURCE_JDBC_PASSWORD");
    assertThat(commandsContaining("create secret generic etlr-source-db")).hasSize(1);
    assertThat(commandsContaining("create secret generic mig-db")).hasSize(1);
  }

  // ── as-of date ────────────────────────────────────────────────────────────

  @Test
  void asOfDateIsTheUtcDateOfTheControlRowsReadyAt() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "READY_AT", "2026-09-26T01:30:00+02:00"));
    assertThat(r.out).contains("as_of_date=2026-09-25");
    assertThat(Files.readString(work.resolve("flink_curate_enrich.yaml"))).contains("\"2026-09-25\"");
  }

  @Test
  void aPinnedRunLooksUpItsReadyAtThroughDiscover() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "STUB_DISCOVER", "run-z\\t2026-01-01T00:00:00Z\\nrun-a\\t2026-09-07T00:00:00Z\\n"));
    assertThat(r.out).contains("as_of_date=2026-09-07");
  }

  @Test
  void anUnreadableReadyAtFallsBackToTodayLoudlyAndAnExplicitAsOfDateWins() throws Exception {
    assertThat(run(Map.of("RUN_ID", "run-a")).out).contains("could not read the control row's ready_at");
    clean("done/run-a.done");
    assertThat(run(Map.of("RUN_ID", "run-a", "AS_OF_DATE", "2026-01-31")).out).contains("as_of_date=2026-01-31");
  }

  // ── gate exit codes ───────────────────────────────────────────────────────

  @Test
  void aRefusedGateIsNotReadyNoAttemptNoMarkerNothingSubmittedAndTheNextTickAsksAgain() throws Exception {
    Map<String, String> refused = Map.of("RUN_ID", "run-a", "STUB_GATE_EXIT", "3");
    for (int tick = 0; tick < 2; tick++) {
      Result r = run(refused);
      assertThat(r.exit).as(r.out).isZero();
      assertThat(r.out).contains("not ready");
    }
    assertThat(commandsMatching("java gate-jdbc ")).hasSize(2);
    assertThat(exists("attempts/run-a.count")).isFalse();
    assertThat(exists("failed/run-a.failed")).isFalse();
    assertThat(exists("locks/run-a.lock")).isFalse();
    assertThat(commands("kubectl")).isEmpty();
  }

  @Test
  void aFailedGateIsTerminalAndIsNeverRetried() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "STUB_GATE_EXIT", "4"));
    assertThat(r.exit).isNotZero();
    assertThat(r.out).contains("ALERT run run-a FAILED PERMANENTLY: gate-jdbc");
    assertThat(exists("failed/run-a.failed")).isTrue();
    assertThat(commands("kubectl")).isEmpty();

    Result next = run(Map.of("RUN_ID", "run-a"));
    assertThat(next.exit).isZero();
    assertThat(next.out).contains("is marked failed");
    assertThat(commandsMatching("java gate-jdbc ")).as("the gate is not asked again").hasSize(1);
  }

  @Test
  void aGateThatCannotReachTheSourceIsAnOrdinaryAttemptUpToTheCap() throws Exception {
    Map<String, String> unreachable = Map.of("RUN_ID", "run-a", "STUB_GATE_EXIT", "1", "MAX_ATTEMPTS", "2");
    assertThat(run(unreachable).exit).isNotZero();
    assertThat(read("attempts/run-a.count").strip()).isEqualTo("1");
    assertThat(run(unreachable).exit).isNotZero();
    assertThat(read("attempts/run-a.count").strip()).isEqualTo("2");
    Result third = run(unreachable);
    assertThat(third.exit).isZero();
    assertThat(third.out).contains("ALERT run run-a FAILED PERMANENTLY: after 2 attempts");
    assertThat(exists("failed/run-a.failed")).isTrue();
    assertThat(commands("kubectl")).isEmpty();
  }

  @Test
  void aReconJdbcFailureIsTerminalAndReconNeverRuns() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "READY_AT", "2026-09-07T00:00:00Z", "STUB_RECON_JDBC_EXIT", "4"));
    assertThat(r.exit).isNotZero();
    assertThat(r.out).contains("ALERT run run-a FAILED PERMANENTLY: recon-jdbc");
    assertThat(exists("failed/run-a.failed")).isTrue();
    assertThat(exists("done/run-a.done")).isFalse();
    assertThat(commandsMatching("java recon ")).isEmpty();
  }

  @Test
  void aFailedFlinkJobIsAnAttemptNotATerminalFailure() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "READY_AT", "2026-09-07T00:00:00Z", "STUB_FLINK_FAIL", "flink-curate-enrich-run-a"));
    assertThat(r.exit).isNotZero();
    assertThat(read("attempts/run-a.count").strip()).isEqualTo("1");
    assertThat(exists("failed/run-a.failed")).isFalse();
  }

  // ── markers and lock (kept from the Flink lane) ───────────────────────────

  @Test
  void aFinishedRunIsNotRunAgain() throws Exception {
    put("done/run-a.done", "completed");
    Result r = run(Map.of("RUN_ID", "run-a"));
    assertThat(r.out).contains("already completed");
    assertThat(commandsMatching("java gate-jdbc ")).isEmpty();
  }

  @Test
  void aFreshLockHeldByAnotherExecutionMakesThisOneExitCleanly() throws Exception {
    put("locks/run-a.lock", "other\n" + (System.currentTimeMillis() / 1000) + "\n");
    Result r = run(Map.of("RUN_ID", "run-a"));
    assertThat(r.exit).isZero();
    assertThat(r.out).contains("another execution holds");
    assertThat(exists("locks/run-a.lock")).isTrue();
    assertThat(commandsMatching("java gate-jdbc ")).isEmpty();
  }

  @Test
  void aLockWhoseAgeCannotBeReadIsNeverBrokenAsStale() throws Exception {
    put("locks/run-a.lock", "holder-without-timestamp\n");
    Result r = run(Map.of("RUN_ID", "run-a"));
    assertThat(r.out).contains("age is unreadable").doesNotContain("breaking stale lock");
    assertThat(exists("locks/run-a.lock")).isTrue();
  }

  // ── discovery ─────────────────────────────────────────────────────────────

  @Test
  void discoveryRunsPendingRunsSkipsFinishedOnesWithoutAGateAndWarnsAboutBadIds() throws Exception {
    put("done/run-b.done", "completed");
    Result r = run(Map.of("STUB_DISCOVER",
        "run-a\\t2026-09-07T00:00:00Z\\nrun-b\\t2026-09-07T00:00:00Z\\nBad_Id\\t2026-09-07T00:00:00Z\\n"));
    assertThat(exists("done/run-a.done")).isTrue();
    assertThat(r.out).contains("WARNING skipping 'Bad_Id'").contains("as_of_date=2026-09-07");
    assertThat(commandsMatching("java gate-jdbc ")).hasSize(1).allMatch(l -> l.contains(" run-a "));
    assertThat(r.exit).isNotZero();
  }

  @Test
  void discoveryThatCannotReachTheSourceFailsTheFiringAndProcessesNothing() throws Exception {
    Result r = run(Map.of("STUB_DISCOVER_EXIT", "1"));
    assertThat(r.exit).isNotZero();
    assertThat(r.out).contains("gate-jdbc --discover failed");
    assertThat(commands("kubectl")).isEmpty();
  }

  // ── run ids and credentials ───────────────────────────────────────────────

  @Test
  void runIdsAreCappedAt38SoTheLongestDeploymentsRestServiceFits() throws Exception {
    Result tooLong = run(Map.of("RUN_ID", "a".repeat(39)));
    assertThat(tooLong.exit).isNotZero();
    assertThat(tooLong.out).contains("at most 38 characters");
    String longest = "a".repeat(38); // "flink-curate-enrich-" (20) + 38 + "-rest" (5) = 63
    Result ok = run(Map.of("RUN_ID", longest, "READY_AT", "2026-09-07T00:00:00Z"));
    assertThat(ok.exit).as(ok.out).isZero();
  }

  @Test
  void passwordsComeFromSecretManagerAndReachJavaOnlyAsEnvReferences() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a", "READY_AT", "2026-09-07T00:00:00Z",
        "CLOUDSQL_APP_PASSWORD", "", "SOURCE_READER_PASSWORD", "",
        "CLOUDSQL_APP_PASSWORD_SECRET", "cloudsql-app-password", "SOURCE_READER_PASSWORD_SECRET", "etlr-source-db"));
    assertThat(r.exit).as(r.out).isZero();
    assertThat(commandsContaining("secrets versions access latest --secret=cloudsql-app-password")).hasSize(1);
    assertThat(commandsContaining("secrets versions access latest --secret=etlr-source-db")).hasSize(1);
    assertThat(commandsMatching("java ")).allMatch(l -> !l.contains("secret-value-from-secret-manager"));
    assertThat(commandsMatching("java gate-jdbc ")).allMatch(l -> l.contains("env:SOURCE_READER_PASSWORD"));
  }

  // ── harness ───────────────────────────────────────────────────────────────

  private Result run(Map<String, String> extraEnv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("bash", ROOT.resolve("gcp/orchestrator/run.sh").toString());
    Map<String, String> env = pb.environment();
    env.put("PATH", bin + ":" + System.getenv("PATH"));
    env.remove("RUN_ID");
    env.put("GCS_LANDING_BUCKET", BUCKET);
    env.put("CLOUDSQL_JDBC_URL", "jdbc:postgresql://10.0.0.5:5432/mig");
    env.put("CLOUDSQL_APP_USER", "mig");
    env.put("CLOUDSQL_APP_PASSWORD", "test-password");
    env.put("SOURCE_JDBC_URL", "jdbc:postgresql://10.0.0.6:5432/source");
    env.put("SOURCE_READER_USER", "etlr_reader");
    env.put("SOURCE_READER_PASSWORD", "reader-password");
    env.put("FLINK_GKE_CLUSTER", "c");
    env.put("GCP_REGION", "europe-west1");
    env.put("GCP_PROJECT", "p");
    env.put("MOUNT", sandbox.resolve("mount").toString());
    env.put("APP_DIR", sandbox.resolve("app").toString());
    env.put("MANIFEST_DIR", manifests.toString());
    env.put("WORK_TMP", work.toString());
    env.put("FLINK_POLL_SECONDS", "0");
    env.put("FLINK_WAIT_POLLS", "2");
    env.put("STUB_GCS", sandbox.resolve("gcs").toString());
    env.put("STUB_LOG", log.toString());
    env.putAll(extraEnv);
    env.entrySet().removeIf(e -> e.getValue().isEmpty() && !e.getKey().startsWith("STUB_"));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
    return new Result(p.exitValue(), out);
  }

  private void put(String key, String content) throws IOException {
    Path p = gcs.resolve(key);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content);
  }

  private boolean exists(String key) {
    return Files.exists(gcs.resolve(key));
  }

  private String read(String key) throws IOException {
    return Files.readString(gcs.resolve(key));
  }

  private void clean(String key) throws IOException {
    Files.deleteIfExists(gcs.resolve(key));
  }

  private List<String> commands(String tool) throws IOException {
    return Files.readAllLines(log).stream().filter(l -> l.startsWith(tool + " ")).toList();
  }

  private List<String> commandsMatching(String prefix) throws IOException {
    return Files.readAllLines(log).stream().filter(l -> l.startsWith(prefix)).toList();
  }

  private List<String> commandsContaining(String needle) throws IOException {
    return Files.readAllLines(log).stream().filter(l -> l.contains(needle)).toList();
  }

  private static int indexOf(List<String> lines, String prefix) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  private void writeStubs() throws IOException {
    // gsutil: identical to ../flink OrchestratorBehaviourTest's stub (gs://bucket/path -> $STUB_GCS/bucket/path,
    // x-goog-if-generation-match:0 as create-if-absent).
    stub("gsutil", """
        #!/bin/bash
        quiet=0; hdr=""
        while [ $# -gt 0 ]; do
          case "$1" in
            -q) quiet=1; shift;;
            -m) shift;;
            -h) hdr="$2"; shift 2;;
            *) break;;
          esac
        done
        cmd="$1"; shift
        map() { echo "$STUB_GCS/${1#gs://}"; }
        case "$cmd" in
          stat) f="$(map "$1")"; [ -e "$f" ] || exit 1; exit 0;;
          cat) f="$(map "$1")"; [ -e "$f" ] || exit 1; cat "$f";;
          rm) rm -f "$(map "$1")";;
          cp)
            src="$1"; dst="$(map "$2")"
            if [ "$src" = "-" ]; then
              if [[ "$hdr" == *"if-generation-match:0"* ]] && [ -e "$dst" ]; then exit 1; fi
              mkdir -p "$(dirname "$dst")"; cat > "$dst"
            else
              mkdir -p "$(dirname "$dst")"; cp "$(map "$src")" "$dst"
            fi;;
          *) echo "stub gsutil: unsupported: $cmd" >&2; exit 2;;
        esac
        """);
    stub("gcloud", """
        #!/bin/bash
        echo "gcloud $*" >> "$STUB_LOG"
        case "$*" in
          *"secrets versions access"*) echo "secret-value-from-secret-manager";;
        esac
        exit 0
        """);
    stub("kubectl", """
        #!/bin/bash
        echo "kubectl $*" >> "$STUB_LOG"
        case "$*" in
          *"create secret"*) echo "kind: Secret";;
          *"apply -f -"*) cat > /dev/null;;
          *"get flinkdeployment"*)
            if [ -n "${STUB_FLINK_FAIL:-}" ] && [[ "$*" == *"${STUB_FLINK_FAIL}"* ]]; then echo FAILED; else echo FINISHED; fi;;
        esac
        exit 0
        """);
    // Order matters: --discover and ReconJdbc both mention gate-jdbc.jar.
    stub("java", """
        #!/bin/bash
        case "$*" in
          *"--discover"*)
            echo "java discover $*" >> "$STUB_LOG"
            [ -n "${STUB_DISCOVER_EXIT:-}" ] && exit "$STUB_DISCOVER_EXIT"
            printf '%b' "${STUB_DISCOVER:-}"; exit 0;;
          *mig.gatejdbc.ReconJdbc*)
            echo "java recon-jdbc $*" >> "$STUB_LOG"; exit "${STUB_RECON_JDBC_EXIT:-0}";;
          *gate-jdbc.jar*)
            echo "java gate-jdbc $* " >> "$STUB_LOG"
            rc="${STUB_GATE_EXIT:-0}"
            if [ "$rc" = 0 ]; then echo "src_read=53"; else echo "gate-jdbc: stub verdict $rc" >&2; fi
            exit "$rc";;
          *recon-service.jar*)
            shift 2; echo "java recon $*" >> "$STUB_LOG"; exit "${STUB_RECON_EXIT:-0}";;
        esac
        exit 0
        """);
  }

  private void stub(String name, String body) throws IOException {
    Path p = bin.resolve(name);
    Files.writeString(p, body);
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
