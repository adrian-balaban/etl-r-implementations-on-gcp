package mig.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The bundle layout is a contract between three files that reference each other only
 * through string paths, and nothing used to check that they agree:
 *
 * <ul>
 *   <li>{@code harness/bundle.py} WRITES the bundle — {@code <run_id>/incoming/ACCOUNT.tar.gz}
 *       with {@code ACCOUNT.FLG} beside it and {@code ACCOUNT.CHS} <em>inside</em> it;
 *   <li>{@code Gate} READS that directory and writes only {@code ACCOUNT.src};
 *   <li>{@code gcp/orchestrator/run.sh} polls for the {@code .FLG} and reads the {@code .CHS}
 *       for recon's {@code srcRead}.
 * </ul>
 *
 * <p>The orchestrator got both wrong, and neither was catchable by any existing test: it
 * polled for the {@code .FLG} one directory above where it is written (so the lane would
 * have waited forever, with nothing to log), and it fetched the {@code .CHS} as a GCS object
 * that does not exist (so {@code srcRead} would have been empty and recon would have
 * "reconciled" against nothing). Both were found only by deploying to a fresh project.
 *
 * <p>This test builds a real bundle and asserts that the paths hard-coded in {@code run.sh}
 * point at things that exist in it. It is unglamorous and it would have saved an hour.
 */
class OrchestratorPathContractTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @Test
  void orchestratorPathsMatchWhatTheBundleActuallyContains() throws Exception {
    assumeTrue(commandExists("python3"), "python3 not on PATH");

    Path out = Files.createTempDirectory("bundle-contract");
    String runId = "run-contract-check";
    int rc =
        new ProcessBuilder(
                "python3", "-m", "harness.bundle",
                "--src", ROOT.resolve("fixtures/ACCOUNT.src").toString(),
                "--run-id", runId,
                "--out", out.toString())
            .directory(ROOT.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor();
    assertThat(rc).as("harness/bundle.py built a bundle").isZero();

    String runSh = Files.readString(ROOT.resolve("gcp/orchestrator/run.sh"));

    // 1. The .FLG the sensor polls for is where bundle.py writes it. Two path conventions
    //    meet here and only one of them is in either file: bundle.py lays out
    //    `<run_id>/incoming/...` relative to its --out directory, and the landing BUCKET
    //    puts that under an `extraction/` prefix. So the contract is
    //    run.sh's suffix == "extraction/" + bundle.py's layout — stated here because it is
    //    stated nowhere else, and getting it wrong is bug #6 (a lane that waits forever).
    String flgSuffix = suffixAfterBucket(runSh, "FLG=\"${BUCKET}/");
    assertThat(flgSuffix)
        .as("the landing bucket's extraction/ prefix")
        .startsWith("extraction/");
    Path flgOnDisk =
        out.resolve(flgSuffix.substring("extraction/".length()).replace("${RUN_ID}", runId));
    assertThat(flgOnDisk)
        .as("run.sh polls for a .FLG at a path harness/bundle.py actually writes")
        .exists();

    // 2. The .CHS is inside the archive, not beside it.
    Path archive = out.resolve(runId).resolve("incoming").resolve("ACCOUNT.tar.gz");
    assertThat(archive).exists();
    assertThat(out.resolve(runId).resolve("incoming").resolve("ACCOUNT.CHS"))
        .as(".CHS must NOT be a standalone object — run.sh reads it out of the archive")
        .doesNotExist();
    // The .CHS is verified by the gate; recon's srcRead comes from the gate's data-record count,
    // NOT from the .CHS column (a physical line count, header included — 56, not 55).
    assertThat(runSh)
        .as("run.sh takes recon's srcRead from gate's data_records line")
        .contains("data_records=")
        .doesNotContain("tar -xzOf");

    // 3. Gate leaves only ACCOUNT.src behind, so nothing downstream may expect a
    //    .CHS in the work directory either.
    Path work = out.resolve("work");
    Gate.run(out.resolve(runId).resolve("incoming"), "ACCOUNT", runId, work);
    assertThat(work.resolve(runId).resolve("ACCOUNT.src")).exists();
    assertThat(work.resolve(runId).resolve("ACCOUNT.CHS"))
        .as("gate does not materialise the .CHS — recon must not look for it here")
        .doesNotExist();
  }

  /** Pulls the path suffix out of a `VAR="gs://${BUCKET}/<suffix>"` assignment in run.sh. */
  private static String suffixAfterBucket(String script, String prefix) {
    int start = script.indexOf(prefix);
    assertThat(start).as("run.sh still assigns the .FLG path the expected way").isNotNegative();
    int from = start + prefix.length();
    int end = script.indexOf('"', from);
    return script.substring(from, end);
  }

  private static boolean commandExists(String cmd) {
    try {
      return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  /**
   * The orchestrator validates a run id in shell, the Java entry points validate it in
   * {@code mig.common.RunId}. Two copies of a rule drift; so the shell one is pinned to the Java one.
   */
  @Test
  void theOrchestratorsRunIdRuleIsExactlyTheJavaOne() throws Exception {
    String runSh = Files.readString(ROOT.resolve("gcp/orchestrator/run.sh"));

    assertThat(runSh)
        .as("run.sh must use RunId.PATTERN_TEXT verbatim")
        .contains("=~ " + mig.common.RunId.PATTERN_TEXT);
    assertThat(runSh)
        .as("and the same length limit")
        .contains("-gt " + mig.common.RunId.MAX_LENGTH);
  }
}
