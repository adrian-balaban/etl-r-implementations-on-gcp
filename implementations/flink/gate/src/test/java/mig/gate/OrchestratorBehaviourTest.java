package mig.gate;

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
 * Behavioural tests for {@code gcp/orchestrator/run.sh} — the real script, run against stub
 * {@code gsutil}/{@code gcloud}/{@code kubectl}/{@code java} and the REAL FlinkDeployment
 * templates, not against a copy of its logic.
 *
 * <p>Why this exists: the orchestrator was written, deployed, and only then found to have a wrong
 * sensor path, a missing VPC egress, no completion marker, no attempt cap and a retry that could
 * never move. Every one of those was a behaviour, and the only test was a text check that the
 * file contained certain strings ({@link OrchestratorPathContractTest}). A text check cannot
 * tell you what happens on the fifth failure, or that discovery skips a finished run, or that a
 * password never reaches a manifest.
 *
 * <p>The stub {@code gsutil} maps {@code gs://bucket/path} onto a temp directory and implements
 * exactly what the script uses — including {@code x-goog-if-generation-match:0} as
 * create-if-absent, which is the property the lock depends on.
 */
class OrchestratorBehaviourTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String BUCKET = "test-landing";

  private Path sandbox;
  private Path bin;
  private Path gcs; // the stub bucket: <sandbox>/gcs/<bucket>/...
  private Path mount;
  private Path work;
  private Path log; // every stubbed command, one line each

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(commandExists("bash"), "bash not on PATH");
    sandbox = Files.createTempDirectory("orchestrator-behaviour");
    bin = Files.createDirectories(sandbox.resolve("bin"));
    gcs = Files.createDirectories(sandbox.resolve("gcs").resolve(BUCKET));
    mount = Files.createDirectories(sandbox.resolve("mount"));
    work = Files.createDirectories(sandbox.resolve("work"));
    Files.createDirectories(sandbox.resolve("app"));
    log = sandbox.resolve("commands.log");
    Files.writeString(log, "");
    writeStubs();
  }

  // ── the happy path ────────────────────────────────────────────────────────

  @Test
  void aCleanRunSubmitsThreeJobsReconcilesAndWritesTheDoneMarker() throws Exception {
    plantFlg("run-a");
    Result r = run(Map.of("RUN_ID", "run-a"));

    assertThat(r.exit).as(r.out).isZero();
    assertThat(r.out).contains("src_read=55", "orchestrator: run run-a complete");
    assertThat(exists("done/run-a.done")).isTrue();
    assertThat(exists("attempts/run-a.count")).as("attempt counter cleared on success").isFalse();
    assertThat(exists("locks/run-a.lock")).as("lock released on exit").isFalse();

    List<String> applied = commandsMatching("kubectl apply -f " + work);
    assertThat(applied).hasSize(3);
    assertThat(applied.get(0)).contains("flink_intake");
    assertThat(applied.get(1)).contains("flink_curate_enrich");
    assertThat(applied.get(2)).contains("flink_produce");
  }

  @Test
  void asOfDateIsTheFlgCreationDateNotTheDayARetryFires() throws Exception {
    plantFlg("run-a");
    Result r = run(Map.of("RUN_ID", "run-a", "STUB_FLG_TIME", "Thu, 25 Sep 2026 23:59:59 GMT"));

    assertThat(r.out).contains("as_of_date=2026-09-25");
    assertThat(Files.readString(work.resolve("flink_curate_enrich.yaml"))).contains("2026-09-25");
  }

  @Test
  void anExplicitAsOfDateWinsAndAnUnreadableFlgTimeFallsBackLoudly() throws Exception {
    plantFlg("run-a");
    assertThat(run(Map.of("RUN_ID", "run-a", "AS_OF_DATE", "2026-01-31")).out).contains("as_of_date=2026-01-31");

    // no Creation time in `gsutil stat` output -> falls back to today, and SAYS so
    clean("done/run-a.done");
    Result r = run(Map.of("RUN_ID", "run-a", "STUB_FLG_TIME", ""));
    assertThat(r.out).contains("could not read the .FLG creation time");
  }

  // ── markers and the lock ──────────────────────────────────────────────────

  @Test
  void aFinishedRunIsNotRunAgain() throws Exception {
    plantFlg("run-a");
    put("done/run-a.done", "completed");
    Result r = run(Map.of("RUN_ID", "run-a"));

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("already completed");
    assertThat(commands("kubectl")).as("nothing is submitted for a finished run").isEmpty();
  }

  @Test
  void aRunWithNoFlgYetDoesNothingAndIsNotCountedAsAnAttempt() throws Exception {
    Result r = run(Map.of("RUN_ID", "run-a"));

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("not found yet");
    assertThat(exists("attempts/run-a.count")).isFalse();
    assertThat(exists("locks/run-a.lock")).as("lock released even on the no-op path").isFalse();
  }

  @Test
  void aFreshLockHeldByAnotherExecutionMakesThisOneExitCleanly() throws Exception {
    plantFlg("run-a");
    put("locks/run-a.lock", "other-execution\n" + (System.currentTimeMillis() / 1000) + "\n");
    Result r = run(Map.of("RUN_ID", "run-a"));

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("another execution holds");
    assertThat(commands("kubectl")).isEmpty();
    assertThat(exists("locks/run-a.lock")).as("the OTHER execution's lock is not ours to remove").isTrue();
  }

  /**
   * Found on real GCP at 200k accounts: one execution was releasing its lock while another read it,
   * got no timestamp, computed age = now - 0 = 1,790,457,671 s and "broke" the lock, starting a
   * second full attempt. An unreadable lock must be left alone, and must not be removed.
   */
  @Test
  void aLockWhoseAgeCannotBeReadIsNeverBrokenAsStale() throws Exception {
    plantFlg("run-a");
    put("locks/run-a.lock", "holder-that-wrote-no-timestamp\n");
    Result r = run(Map.of("RUN_ID", "run-a"));

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("age is unreadable").doesNotContain("breaking stale lock");
    assertThat(commands("kubectl")).as("nothing was submitted").isEmpty();
    assertThat(exists("locks/run-a.lock")).as("and the lock was not removed").isTrue();
  }

  @Test
  void aStaleLockIsBrokenSoAKilledExecutionCannotWedgeTheLane() throws Exception {
    plantFlg("run-a");
    put("locks/run-a.lock", "dead-execution\n" + (System.currentTimeMillis() / 1000 - 10_000) + "\n");
    Result r = run(Map.of("RUN_ID", "run-a"));

    assertThat(r.out).contains("breaking stale lock");
    assertThat(exists("done/run-a.done")).isTrue();
  }

  // ── A3: a persistent failure must STOP ────────────────────────────────────

  @Test
  void aPersistentFailureStopsAfterTheAttemptCapInsteadOfRetryingForever() throws Exception {
    plantFlg("run-a");
    Map<String, String> failing = Map.of("RUN_ID", "run-a", "MAX_ATTEMPTS", "2", "STUB_RECON_EXIT", "1");

    Result first = run(failing);
    assertThat(first.exit).as("attempt 1 fails").isNotZero();
    assertThat(read("attempts/run-a.count").strip()).isEqualTo("1");
    assertThat(exists("locks/run-a.lock")).as("a failed attempt still releases its lock").isFalse();

    Result second = run(failing);
    assertThat(second.exit).as("attempt 2 fails").isNotZero();
    assertThat(read("attempts/run-a.count").strip()).isEqualTo("2");

    int submissionsBefore = commandsMatching("kubectl apply -f " + work).size();
    Result third = run(failing);
    assertThat(third.exit).as("the cap is reached: stop, do not fail again").isZero();
    assertThat(third.out).contains("ALERT run run-a FAILED PERMANENTLY after 2 attempts");
    assertThat(exists("failed/run-a.failed")).isTrue();
    assertThat(commandsMatching("kubectl apply -f " + work))
        .as("no further Flink submissions once the cap is hit")
        .hasSize(submissionsBefore);

    Result fourth = run(failing);
    assertThat(fourth.out).contains("is marked failed");
    assertThat(commandsMatching("kubectl apply -f " + work)).hasSize(submissionsBefore);
  }

  @Test
  void deletingTheFailedMarkerAndCounterIsTheDeliberateWayToRetry() throws Exception {
    plantFlg("run-a");
    put("failed/run-a.failed", "failed");
    put("attempts/run-a.count", "9\n");
    assertThat(run(Map.of("RUN_ID", "run-a")).out).contains("is marked failed");

    clean("failed/run-a.failed");
    clean("attempts/run-a.count");
    Result r = run(Map.of("RUN_ID", "run-a"));
    assertThat(r.exit).isZero();
    assertThat(exists("done/run-a.done")).isTrue();
  }

  @Test
  void aFlinkJobThatFailsFailsTheAttemptAndLeavesNoDoneMarker() throws Exception {
    plantFlg("run-a");
    Result r = run(Map.of("RUN_ID", "run-a", "STUB_FLINK_FAIL", "produce"));

    assertThat(r.exit).isNotZero();
    assertThat(r.out).contains("FAILED");
    assertThat(exists("done/run-a.done")).isFalse();
    assertThat(commandsMatching("kubectl delete -f " + work)).as("a stale deployment is cleared before each submit").isNotEmpty();
  }

  // ── C4: discovery ─────────────────────────────────────────────────────────

  @Test
  void discoveryProcessesEveryPendingRunAndSkipsFinishedAndFailedOnes() throws Exception {
    for (String id : List.of("run-done", "run-failed", "run-c", "run-d")) {
      plantFlg(id);
    }
    put("done/run-done.done", "completed");
    put("failed/run-failed.failed", "failed");

    Result r = run(Map.of()); // no RUN_ID

    assertThat(r.exit).as(r.out).isZero();
    assertThat(r.out).contains("discovered pending run run-c", "discovered pending run run-d", "2 pending run(s)");
    assertThat(r.out).doesNotContain("discovered pending run run-done").doesNotContain("discovered pending run run-failed");
    assertThat(exists("done/run-c.done")).isTrue();
    assertThat(exists("done/run-d.done")).isTrue();
    assertThat(exists("done/run-failed.done")).as("a failed run is not silently completed").isFalse();
  }

  @Test
  void aFailureInOneDiscoveredRunDoesNotStopTheOthersButIsReported() throws Exception {
    plantFlg("run-bad");
    plantFlg("run-good");
    // recon fails only for run-bad
    Result r = run(Map.of("STUB_RECON_FAIL_FOR", "run-bad"));

    assertThat(r.exit).as("the firing reports that a run did not complete").isNotZero();
    assertThat(exists("done/run-good.done")).as("the healthy run still completes").isTrue();
    assertThat(exists("done/run-bad.done")).isFalse();
    assertThat(exists("locks/run-bad.lock")).as("the failed run's lock was released, not leaked").isFalse();
  }

  @Test
  void discoveryOverAnEmptyBucketIsNotAnError() throws Exception {
    Result r = run(Map.of());
    assertThat(r.exit).isZero();
    assertThat(r.out).contains("0 pending run(s)");
  }

  // ── C5: the database password ─────────────────────────────────────────────

  @Test
  void byDefaultThePasswordStaysOutOfEveryManifestAndProgramArgument() throws Exception {
    plantFlg("run-a");
    Result r = run(Map.of("RUN_ID", "run-a", "CLOUDSQL_APP_PASSWORD", "hunter2-not-a-real-password"));

    assertThat(r.exit).as(r.out).isZero();
    for (String job : List.of("flink_intake", "flink_curate_enrich", "flink_produce")) {
      String manifest = Files.readString(work.resolve(job + ".yaml"));
      assertThat(manifest).as(job).doesNotContain("hunter2").contains("env:JDBC_PASSWORD");
    }
    assertThat(commandsMatching("kubectl -n flink-operator create secret generic mig-db"))
        .as("the password reaches the cluster only as a Kubernetes Secret")
        .isNotEmpty();
    // Where the password may legitimately appear on a command line: creating the Secret itself, and
    // recon (whose CLI takes it positionally). Never on anything that submits or describes a job.
    assertThat(commandsContaining("hunter2-not-a-real-password"))
        .allSatisfy(l -> assertThat(l).matches("^(kubectl -n flink-operator create secret generic mig-db|java recon).*"));
  }

  @Test
  void theOldArgumentModeStillWorksWhenExplicitlyRequested() throws Exception {
    plantFlg("run-a");
    run(Map.of("RUN_ID", "run-a", "DB_PASSWORD_MODE", "arg", "CLOUDSQL_APP_PASSWORD", "pw-in-arg"));
    assertThat(Files.readString(work.resolve("flink_intake.yaml"))).contains("pw-in-arg");
  }

  @Test
  void thePasswordIsReadFromSecretManagerWhenASecretNameIsGiven() throws Exception {
    plantFlg("run-a");
    Map<String, String> env = new HashMap<>();
    env.put("RUN_ID", "run-a");
    env.put("CLOUDSQL_APP_PASSWORD", ""); // unset: must come from the secret
    env.put("CLOUDSQL_APP_PASSWORD_SECRET", "cloudsql-app-password");
    Result r = run(env);

    assertThat(r.exit).as(r.out).isZero();
    assertThat(commandsMatching("gcloud secrets versions access latest --secret=cloudsql-app-password")).hasSize(1);
  }

  // ── B3: PGP key material ──────────────────────────────────────────────────

  @Test
  void anEncryptedBundleGetsItsKeyMaterialFromSecretManagerAndAPlainOneDoesNot() throws Exception {
    plantFlg("run-plain");
    run(Map.of("RUN_ID", "run-plain"));
    assertThat(commandsMatching("gcloud secrets versions access latest --secret=pgp-")).isEmpty();

    plantFlg("run-enc");
    put("extraction/run-enc/incoming/ACCOUNT.tar.gz.pgp", "ciphertext");
    Result r = run(Map.of("RUN_ID", "run-enc"));

    assertThat(r.exit).as(r.out).isZero();
    assertThat(commandsMatching("gcloud secrets versions access latest --secret=pgp-private-key")).hasSize(1);
    assertThat(commandsMatching("gcloud secrets versions access latest --secret=pgp-passphrase")).hasSize(1);
    assertThat(commandsContaining("java gate PGP_PRIVATE_KEY_FILE=" + work + "/pgp-private.asc"))
        .as("gate is started with the key file in its environment")
        .hasSize(1);
  }

  // ── harness ───────────────────────────────────────────────────────────────

  private record Result(int exit, String out) {}

  private Result run(Map<String, String> extraEnv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("bash", ROOT.resolve("gcp/orchestrator/run.sh").toString());
    Map<String, String> env = pb.environment();
    env.put("PATH", bin + ":" + env.get("PATH"));
    env.put("GCS_LANDING_BUCKET", BUCKET);
    env.put("CLOUDSQL_JDBC_URL", "jdbc:postgresql://10.0.0.5:5432/mig");
    env.put("CLOUDSQL_APP_USER", "mig");
    env.put("CLOUDSQL_APP_PASSWORD", "test-password");
    env.put("FLINK_GKE_CLUSTER", "c");
    env.put("GCP_REGION", "europe-west1");
    env.put("GCP_PROJECT", "p");
    env.put("MOUNT", mount.toString());
    env.put("APP_DIR", sandbox.resolve("app").toString());
    env.put("MANIFEST_DIR", ROOT.resolve("gcp/flink-deployments").toString());
    env.put("MIG_ROOT", ROOT.toString());
    env.put("WORK_TMP", work.toString());
    env.put("FLINK_POLL_SECONDS", "0");
    env.put("FLINK_WAIT_POLLS", "2");
    env.put("STUB_GCS", sandbox.resolve("gcs").toString());
    env.put("STUB_LOG", log.toString());
    env.put("STUB_FLG_TIME", "Thu, 25 Sep 2026 19:40:12 GMT");
    env.putAll(extraEnv);
    // an empty string means "unset" for the variables the script tests with ${VAR:-}
    env.entrySet().removeIf(e -> e.getValue().isEmpty() && !e.getKey().startsWith("STUB_"));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("run.sh finished").isTrue();
    return new Result(p.exitValue(), out);
  }

  private void plantFlg(String runId) throws IOException {
    put("extraction/" + runId + "/incoming/ACCOUNT.FLG", "flag");
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

  private static boolean commandExists(String cmd) {
    try {
      return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private void writeStubs() throws IOException {
    stub("gsutil", """
        #!/bin/bash
        # gs://bucket/path -> $STUB_GCS/bucket/path
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
          stat)
            f="$(map "$1")"; [ -e "$f" ] || exit 1
            if [ "$quiet" = 0 ]; then
              echo "$1:"
              [ -n "${STUB_FLG_TIME:-}" ] && echo "    Creation time:          ${STUB_FLG_TIME}"
              echo "    Content-Length:         1"
            fi
            exit 0;;
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
          ls)
            pat="$(map "$1")"
            for f in $pat; do [ -e "$f" ] && echo "gs://${f#$STUB_GCS/}"; done
            exit 0;;
          *) echo "stub gsutil: unsupported: $cmd" >&2; exit 2;;
        esac
        """);
    stub("gcloud", """
        #!/bin/bash
        echo "gcloud $*" >> "$STUB_LOG"
        case "$*" in
          *"secrets versions access"*)
            [ -n "${STUB_SECRET_MISSING:-}" ] && [[ "$*" == *"${STUB_SECRET_MISSING}"* ]] && { echo "NOT_FOUND: no versions" >&2; exit 1; }
            echo "secret-value-from-secret-manager";;
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
    stub("java", """
        #!/bin/bash
        jar=""; for a in "$@"; do case "$a" in *.jar) jar="$a";; esac; done
        case "$jar" in
          *gate*)
            echo "java gate PGP_PRIVATE_KEY_FILE=${PGP_PRIVATE_KEY_FILE:-} PGP_PASSPHRASE_SET=${PGP_PASSPHRASE:+yes}" >> "$STUB_LOG"
            [ -n "${STUB_GATE_FAIL:-}" ] && { echo "gate: refused" >&2; exit 1; }
            echo "gate: OK -> /work/x/ACCOUNT.src"
            echo "gate: data_records=55";;
          *recon*)
            echo "java recon $*" >> "$STUB_LOG"
            run="$3"
            [ -n "${STUB_RECON_FAIL_FOR:-}" ] && [ "$run" = "$STUB_RECON_FAIL_FOR" ] && exit 1
            exit "${STUB_RECON_EXIT:-0}";;
        esac
        exit 0
        """);
    // `sleep` is real; FLINK_POLL_SECONDS=0 keeps the polling loop instant.
  }

  private void stub(String name, String body) throws IOException {
    Path p = bin.resolve(name);
    Files.writeString(p, body);
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  // ── run-id validation ─────────────────────────────────────────────────────

  @Test
  void aRunIdThatCannotBeAKubernetesNameIsRefusedBeforeAnythingIsSubmitted() throws Exception {
    for (String bad : List.of("Run_1", "run.gcp", "-leading", "trailing-", "UPPER", "a".repeat(50))) {
      plantFlg(bad);
      Result r = run(Map.of("RUN_ID", bad));
      assertThat(r.exit).as("run id '%s'", bad).isNotZero();
      assertThat(r.out).contains("not usable as a Kubernetes name");
    }
    assertThat(commands("kubectl")).as("nothing reached the cluster").isEmpty();
    assertThat(exists("locks/Run_1.lock")).as("and no lock was taken for it").isFalse();
  }

  @Test
  void theLongestValidRunIdStillFitsAKubernetesNameWithTheLongestPrefix() throws Exception {
    String longest = "a".repeat(49); // "flink-produce-" (14) + 49 = 63, the DNS-1123 label limit
    plantFlg(longest);
    Result r = run(Map.of("RUN_ID", longest));
    assertThat(r.exit).as(r.out).isZero();
    assertThat(exists("done/" + longest + ".done")).isTrue();
  }

  @Test
  void discoverySkipsAnInvalidRunIdLoudlyAndStillProcessesTheValidOnes() throws Exception {
    plantFlg("Bad_Id");
    plantFlg("good-run");
    Result r = run(Map.of());

    assertThat(r.out).contains("WARNING skipping 'Bad_Id'");
    assertThat(exists("done/good-run.done")).as("the valid run still completes").isTrue();
    assertThat(exists("done/Bad_Id.done")).isFalse();
    assertThat(r.exit).as("but the firing reports that something was wrong").isNotZero();
  }

  @Test
  void anEncryptedBundleWithAnEmptyPgpSecretFailsWithTheOperatorsNextStepNotARawGcloudError() throws Exception {
    plantFlg("run-enc");
    put("extraction/run-enc/incoming/ACCOUNT.tar.gz.pgp", "ciphertext");
    Result r = run(Map.of("RUN_ID", "run-enc", "STUB_SECRET_MISSING", "pgp-private-key"));

    assertThat(r.exit).isNotZero();
    assertThat(r.out)
        .contains("no readable version", "gcloud secrets versions add pgp-private-key", "Terraform intentionally does not");
    assertThat(commands("kubectl")).as("it stopped before submitting any job").isEmpty();
    assertThat(exists("done/run-enc.done")).isFalse();
  }
}
