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
 * Behavioural tests for {@code gcp/ttl/sweep.sh} — the kill switch that
 * replaces "someone remembers to run terraform destroy".
 *
 * <p>The failure that matters most is the FALSE POSITIVE: a kill switch that fires when it should
 * not deletes a working environment. So most of these are about the script refusing to act — no
 * expiry, a garbage expiry, an expiry in the future, a dry run, and resources that are not ours.
 * The one positive case checks it deletes only what it should, in a sensible order.
 */
class TtlSweeperBehaviourTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String BUCKET = "ttl-bucket";

  private Path sandbox;
  private Path gcs;
  private Path log;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(new ProcessBuilder("bash", "-c", "true").start().waitFor() == 0);
    sandbox = Files.createTempDirectory("ttl-sweeper");
    Path bin = Files.createDirectories(sandbox.resolve("bin"));
    gcs = Files.createDirectories(sandbox.resolve("gcs").resolve(BUCKET));
    log = sandbox.resolve("commands.log");
    Files.writeString(log, "");

    stub(bin, "gsutil", """
        #!/bin/bash
        while [ $# -gt 0 ]; do case "$1" in -q) shift;; *) break;; esac; done
        cmd="$1"; shift
        f="$STUB_GCS/${1#gs://}"
        case "$cmd" in
          cat) [ -e "$f" ] && cat "$f" || exit 1;;
          cp) dst="$STUB_GCS/${2#gs://}"; mkdir -p "$(dirname "$dst")"; cat > "$dst";;
        esac
        """);
    stub(bin, "gcloud", """
        #!/bin/bash
        case "$*" in
          "scheduler jobs list"*)   printf '%s' "${STUB_SCHEDULER:-}";;
          "container clusters list"*) printf '%s' "${STUB_GKE:-}";;
          "sql instances list"*)    printf '%s' "${STUB_SQL:-}";;
          "managed-kafka clusters list"*) printf '%s' "${STUB_KAFKA:-}";;
          "run services list"*)     printf '%s' "${STUB_RUN:-}";;
          "compute networks vpc-access connectors list"*) printf '%s' "${STUB_VPC:-}";;
          *)
            echo "gcloud $*" >> "$STUB_LOG"
            [ -n "${STUB_FAIL_ON:-}" ] && [[ "$*" == *"${STUB_FAIL_ON}"* ]] && exit 1
            exit 0;;
        esac
        """);
  }

  @Test
  void anEnvironmentThatHasNotExpiredIsLeftAlone() throws Exception {
    expiresIn(7200);
    Result r = run(Map.of("DRY_RUN", "0", "STUB_GKE", "mig-flink-gke\n", "STUB_SQL", "mig-flink-pg\n"));

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("expires in", "nothing to do");
    assertThat(deletions()).isEmpty();
  }

  @Test
  void anExpiredEnvironmentIsOnlyReportedByDefaultBecauseDryRunIsTheDefault() throws Exception {
    expiresIn(-3600);
    Result r = run(Map.of("STUB_GKE", "mig-flink-gke\n")); // DRY_RUN not set

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("EXPIRED", "[dry-run] would delete GKE cluster mig-flink-gke");
    assertThat(deletions()).as("a dry run deletes nothing").isEmpty();
    assertThat(Files.exists(gcs.resolve("ttl/swept-at"))).isFalse();
  }

  @Test
  void anExpiredEnvironmentDeletesOnlyWhatBillsAndOnlyWhatIsOurs() throws Exception {
    expiresIn(-60);
    Result r =
        run(
            Map.of(
                "DRY_RUN", "0",
                "STUB_SCHEDULER", "mig-flink-orchestrator\nsomeone-elses-job\n",
                "STUB_GKE", "mig-flink-gke\nunrelated-cluster\n",
                "STUB_SQL", "mig-flink-pg\nother-db\n",
                "STUB_KAFKA", "mig-flink-kafka\n",
                "STUB_RUN", "mig-flink-target-system-mock\nsome-other-service\n",
                "STUB_VPC", "mig-flink-vpc-connector\n"));

    assertThat(r.exit).as(r.out).isZero();
    List<String> d = deletions();
    assertThat(d).anyMatch(l -> l.contains("container clusters delete mig-flink-gke"));
    assertThat(d).anyMatch(l -> l.contains("sql instances delete mig-flink-pg"));
    assertThat(d).anyMatch(l -> l.contains("managed-kafka clusters delete mig-flink-kafka"));
    assertThat(d).anyMatch(l -> l.contains("run services delete mig-flink-target-system-mock"));
    assertThat(d).anyMatch(l -> l.contains("vpc-access connectors delete mig-flink-vpc-connector"));
    // Found on the first real run: blocking deletes (Kafka alone ~15 min) outlasted Cloud Run's 900 s task
    // timeout and killed the sweeper before it reached the mock and the connector.
    assertThat(d.stream().filter(l -> l.contains(" delete ")).toList())
        .as("every delete is asynchronous, so the job cannot outlive its timeout waiting for one")
        .hasSize(5)
        .allMatch(l -> l.contains("--async"));

    // never anything that is not ours
    assertThat(String.join("\n", d)).doesNotContain("unrelated-cluster", "other-db", "some-other-service", "someone-elses-job");

    // the trigger is PAUSED (not deleted) and paused FIRST, so nothing starts new work mid-sweep
    assertThat(d.get(0)).contains("scheduler jobs pause mig-flink-orchestrator");
    assertThat(String.join("\n", d)).doesNotContain("scheduler jobs delete");

    assertThat(Files.readString(gcs.resolve("ttl/swept-at"))).contains("failures=0");
    assertThat(r.out).contains("Terraform state is now STALE");
  }

  @Test
  void aMissingExpiryFailsClosedNothingIsDeleted() throws Exception {
    // no ttl/expires-at object at all
    Result r = run(Map.of("DRY_RUN", "0", "STUB_GKE", "mig-flink-gke\n"));

    assertThat(r.exit).isZero();
    assertThat(r.out).contains("no valid expiry", "fail closed");
    assertThat(deletions()).isEmpty();
  }

  @Test
  void aGarbageExpiryFailsClosedNothingIsDeleted() throws Exception {
    for (String garbage : List.of("soon", "", "2026-09-25", "-5", "12abc")) {
      Files.createDirectories(gcs.resolve("ttl"));
      Files.writeString(gcs.resolve("ttl/expires-at"), garbage + "\n");
      Result r = run(Map.of("DRY_RUN", "0", "STUB_GKE", "mig-flink-gke\n"));
      assertThat(r.out).as("expiry '%s'", garbage).contains("no valid expiry");
    }
    assertThat(deletions()).as("no garbage value can trigger a teardown").isEmpty();
  }

  @Test
  void sweepingAnAlreadySweptProjectIsANoOp() throws Exception {
    expiresIn(-60);
    Result r = run(Map.of("DRY_RUN", "0")); // nothing left to list

    assertThat(r.exit).isZero();
    assertThat(deletions()).isEmpty();
  }

  @Test
  void oneFailedDeletionDoesNotStopTheOthersButIsReportedInTheExitCode() throws Exception {
    expiresIn(-60);
    Result r = run(Map.of("DRY_RUN", "0", "STUB_GKE", "mig-flink-gke\n", "STUB_SQL", "mig-flink-pg\n", "STUB_FAIL_ON", "container clusters delete"));

    assertThat(r.exit).as("a failure is not swallowed").isNotZero();
    assertThat(deletions()).as("Cloud SQL was still attempted after the GKE delete failed")
        .anyMatch(l -> l.contains("sql instances delete mig-flink-pg"));
    assertThat(Files.readString(gcs.resolve("ttl/swept-at"))).contains("failures=1");
  }

  // ── harness ───────────────────────────────────────────────────────────────

  private record Result(int exit, String out) {}

  private Result run(Map<String, String> extra) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("bash", ROOT.resolve("gcp/ttl/sweep.sh").toString());
    Map<String, String> env = new HashMap<>(pb.environment());
    pb.environment().put("PATH", sandbox.resolve("bin") + ":" + env.get("PATH"));
    pb.environment().put("PROJECT", "p");
    pb.environment().put("REGION", "europe-west1");
    pb.environment().put("TTL_BUCKET", BUCKET);
    pb.environment().put("STUB_GCS", sandbox.resolve("gcs").toString());
    pb.environment().put("STUB_LOG", log.toString());
    pb.environment().putAll(extra);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    return new Result(p.exitValue(), out);
  }

  private void expiresIn(long seconds) throws IOException {
    Files.createDirectories(gcs.resolve("ttl"));
    Files.writeString(gcs.resolve("ttl/expires-at"), (System.currentTimeMillis() / 1000 + seconds) + "\n");
  }

  /** Every mutating gcloud call the script made, in order. */
  private List<String> deletions() throws IOException {
    return Files.readAllLines(log).stream().filter(l -> l.startsWith("gcloud ")).toList();
  }

  private static void stub(Path bin, String name, String body) throws IOException {
    Path p = bin.resolve(name);
    Files.writeString(p, body);
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
