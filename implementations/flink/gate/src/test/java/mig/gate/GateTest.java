package mig.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the gate against bundles built by harness/bundle.py.
 */
class GateTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @TempDir Path tmp;

  private void bundle(String runId, Path outDir, String... extraFlags) throws IOException, InterruptedException {
    List<String> cmd =
        new java.util.ArrayList<>(
            List.of(
                "python3",
                "-m",
                "harness.bundle",
                "--src",
                ROOT.resolve("fixtures/ACCOUNT.src").toString(),
                "--run-id",
                runId,
                "--out",
                outDir.toString()));
    cmd.addAll(List.of(extraFlags));
    Process p = new ProcessBuilder(cmd).directory(ROOT.toFile()).inheritIO().start();
    int exit = p.waitFor();
    assertThat(exit).as("harness.bundle exit code").isEqualTo(0);
  }

  @Test
  void goodBundlePassesAndMaterialisesTheExtract() throws Exception {
    String runId = "run-gate-good";
    bundle(runId, tmp);
    Path incoming = tmp.resolve(runId).resolve("incoming");
    Path work = tmp.resolve("materialised");

    Path out = Gate.run(incoming, "ACCOUNT", runId, work);

    assertThat(out).exists();
    byte[] written = Files.readAllBytes(out);
    byte[] original = Files.readAllBytes(ROOT.resolve("fixtures/ACCOUNT.src"));
    assertThat(sha256(written)).isEqualTo(sha256(original));
  }

  @Test
  void missingFlgRefusesToStart() throws Exception {
    String runId = "run-gate-no-flg";
    bundle(runId, tmp, "--no-flg");
    Path incoming = tmp.resolve(runId).resolve("incoming");

    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", runId, tmp.resolve("work")))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining(".FLG");
  }

  @Test
  void badChsExitsNonZeroWithoutWriting() throws Exception {
    String runId = "run-gate-bad-chs";
    bundle(runId, tmp, "--bad-chs");
    Path incoming = tmp.resolve(runId).resolve("incoming");
    Path work = tmp.resolve("work");

    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", runId, work))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining("checksum mismatch");
    assertThat(Files.exists(work)).isFalse();
  }

  private static String sha256(byte[] content) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] hash = md.digest(content);
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * recon's srcRead is DATA records, not the .CHS "records" column (which counts physical
   * lines, header included). The fixture is the case that broke: 56 lines, 55 records — the
   * first real GCP run reported {@code 56 != 50 + 5} because the orchestrator used the .CHS
   * number directly.
   */
  @Test
  void dataRecordsExcludesTheHeaderRowThatTheChsCountIncludes() throws Exception {
    java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
    byte[] fixture = java.nio.file.Files.readAllBytes(root.resolve("fixtures/ACCOUNT.src"));

    assertThat(Checksums.countRecords(fixture))
        .as("the .CHS 'records' column is a physical line count, header included")
        .isEqualTo(56);
    assertThat(Gate.dataRecords(fixture, root))
        .as("the number recon must reconcile against, per the oracle's run_ledger.src_read")
        .isEqualTo(55);
  }

  /**
   * The run id becomes {@code workRoot/<run_id>/}. Before RunId was enforced here, "../../x" wrote the
   * extracted file outside the work directory. The gate must refuse — and, like every other refusal,
   * write nothing anywhere.
   */
  @Test
  void aTraversalRunIdIsRefusedAndNothingIsWrittenOutsideTheWorkRoot() throws Exception {
    java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
    java.nio.file.Path bundles = java.nio.file.Files.createTempDirectory("gate-traversal-bundle");
    new ProcessBuilder("python3", "-m", "harness.bundle", "--src", root.resolve("fixtures/ACCOUNT.src").toString(),
        "--run-id", "run-ok", "--out", bundles.toString()).directory(root.toFile()).inheritIO().start().waitFor();

    java.nio.file.Path parent = java.nio.file.Files.createTempDirectory("gate-traversal");
    java.nio.file.Path workRoot = java.nio.file.Files.createDirectories(parent.resolve("work"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> Gate.run(bundles.resolve("run-ok").resolve("incoming"), "ACCOUNT", "../escaped", workRoot, null))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining("not valid");

    try (var walk = java.nio.file.Files.walk(parent)) {
      assertThat(walk.filter(java.nio.file.Files::isRegularFile).toList())
          .as("nothing written under the work root or its parent")
          .isEmpty();
    }
  }
}
