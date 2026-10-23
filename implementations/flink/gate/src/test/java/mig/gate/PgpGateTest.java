package mig.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *  — the gate can read the source system's {@code .tar.gz.pgp}.
 *
 * <p>Interop, not self-consistency: keys are generated and the bundle is encrypted by the real
 * {@code gpg} CLI (through {@code harness/bundle.py --pgp-public-key}), and only DECRYPTION is done
 * by the code under test (Bouncy Castle). A test where the same library encrypts and decrypts can
 * pass while being unable to read a single file the real producer sends.
 *
 * <p>Every negative asserts the same property the {@code .CHS} negatives already had:
 * the gate refuses, and writes NOTHING.
 *
 * <p>Skipped (not failed) without {@code gpg} or {@code python3} on PATH.
 */
class PgpGateTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String PASS = "correct horse battery staple";

  private static Path gpgHome;
  private static Path keyAPub;
  private static Path keyASecret;
  private static Path keyBSecret;

  private Path incoming;
  private Path work;

  @BeforeAll
  static void generateKeysWithRealGpg() throws Exception {
    assumeTrue(commandExists("gpg") && commandExists("python3"), "gpg and python3 required");
    // gpg-agent sockets live under the homedir and have a ~100 character path limit.
    gpgHome = Files.createTempDirectory("pgp-gate");
    Files.setPosixFilePermissions(gpgHome, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));

    String fprA = generateKey("Producer A <a@example.test>");
    String fprB = generateKey("Somebody Else <b@example.test>");

    keyAPub = gpgHome.resolve("a.pub.asc");
    keyASecret = gpgHome.resolve("a.sec.asc");
    keyBSecret = gpgHome.resolve("b.sec.asc");
    gpgToFile(keyAPub, "--armor", "--export", fprA);
    gpgToFile(keyASecret, "--pinentry-mode", "loopback", "--passphrase", PASS, "--armor", "--export-secret-keys", fprA);
    gpgToFile(keyBSecret, "--pinentry-mode", "loopback", "--passphrase", PASS, "--armor", "--export-secret-keys", fprB);
  }

  @AfterAll
  static void stopAgent() throws Exception {
    if (gpgHome != null) {
      new ProcessBuilder("gpgconf", "--homedir", gpgHome.toString(), "--kill", "gpg-agent")
          .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
    }
  }

  @BeforeEach
  void encryptAFreshBundle() throws Exception {
    Path out = Files.createTempDirectory("pgp-bundle");
    work = Files.createTempDirectory("pgp-work");
    exec(
        ROOT,
        List.of(
            "python3", "-m", "harness.bundle",
            "--src", ROOT.resolve("fixtures/ACCOUNT.src").toString(),
            "--run-id", "run-pgp",
            "--out", out.toString(),
            "--pgp-public-key", keyAPub.toString()));
    incoming = out.resolve("run-pgp").resolve("incoming");
  }

  @Test
  void aBundleEncryptedByRealGpgDecryptsAndPassesTheSameChecksAsAPlainOne() throws Exception {
    assertThat(incoming.resolve("ACCOUNT.tar.gz.pgp")).exists();
    assertThat(incoming.resolve("ACCOUNT.tar.gz")).as("encrypted INSTEAD of plain, never both").doesNotExist();

    Path out = Gate.run(incoming, "ACCOUNT", "run-pgp", work, new Pgp.Key(keyASecret, PASS.toCharArray()));

    assertThat(Files.readAllBytes(out))
        .as("the decrypted payload is byte-identical to the source fixture")
        .isEqualTo(Files.readAllBytes(ROOT.resolve("fixtures/ACCOUNT.src")));
  }

  @Test
  void aBundleEncryptedToADifferentKeyIsRefusedAndNothingIsWritten() {
    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", "run-pgp", work, new Pgp.Key(keyBSecret, PASS.toCharArray())))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining("not encrypted to any key");
    assertThat(filesUnder(work)).as("zero output on refusal").isEmpty();
  }

  @Test
  void aWrongPassphraseIsRefusedAndNothingIsWritten() {
    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", "run-pgp", work, new Pgp.Key(keyASecret, "not the passphrase".toCharArray())))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining("PGP decryption failed");
    assertThat(filesUnder(work)).isEmpty();
  }

  @Test
  void anEncryptedBundleWithNoKeyConfiguredIsRefusedBeforeAnythingIsAttempted() {
    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", "run-pgp", work, null))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining("no key is configured");
    assertThat(filesUnder(work)).isEmpty();
  }

  @Test
  void aTamperedCiphertextIsRefusedNotIngestedAsGarbage() throws Exception {
    Path pgp = incoming.resolve("ACCOUNT.tar.gz.pgp");
    byte[] bytes = Files.readAllBytes(pgp);
    bytes[bytes.length - 20] ^= 0x55; // inside the encrypted body, past the header
    Files.write(pgp, bytes);

    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", "run-pgp", work, new Pgp.Key(keyASecret, PASS.toCharArray())))
        .isInstanceOf(Gate.GateFailure.class);
    assertThat(filesUnder(work)).isEmpty();
  }

  @Test
  void aPlainAndAnEncryptedBundleTogetherAreRefusedNotGuessedBetween() throws Exception {
    // A stale unencrypted copy next to the real delivery is exactly how the wrong file gets ingested.
    Files.write(incoming.resolve("ACCOUNT.tar.gz"), new byte[] {1, 2, 3});

    assertThatThrownBy(() -> Gate.run(incoming, "ACCOUNT", "run-pgp", work, new Pgp.Key(keyASecret, PASS.toCharArray())))
        .isInstanceOf(Gate.GateFailure.class)
        .hasMessageContaining("refusing to choose");
    assertThat(filesUnder(work)).isEmpty();
  }

  @Test
  void aPlainBundleStillNeedsNoKeyAtAll() throws Exception {
    Path out = Files.createTempDirectory("plain-bundle");
    exec(ROOT, List.of("python3", "-m", "harness.bundle", "--src", ROOT.resolve("fixtures/ACCOUNT.src").toString(),
        "--run-id", "run-plain", "--out", out.toString()));

    Path extracted = Gate.run(out.resolve("run-plain").resolve("incoming"), "ACCOUNT", "run-plain", work, null);
    assertThat(extracted).exists();
  }

  // ── harness ───────────────────────────────────────────────────────────────

  private static String generateKey(String userId) throws Exception {
    exec(gpgHome, gpg("--pinentry-mode", "loopback", "--passphrase", PASS, "--quick-generate-key", userId, "rsa2048", "default", "never"));
    String colons = exec(gpgHome, gpg("--list-keys", "--with-colons", userId));
    String fpr = colons.lines().filter(l -> l.startsWith("fpr:")).map(l -> l.split(":")[9]).findFirst().orElseThrow();
    // With an explicit algorithm gpg makes a sign/certify-ONLY primary key; without an encryption
    // subkey `gpg --encrypt` refuses it ("Unusable public key"). Real producers' keys have one.
    exec(gpgHome, gpg("--pinentry-mode", "loopback", "--passphrase", PASS, "--quick-add-key", fpr, "rsa2048", "encr", "never"));
    return fpr;
  }

  private static void gpgToFile(Path target, String... args) throws Exception {
    Files.writeString(target, exec(gpgHome, gpg(args)));
  }

  private static List<String> gpg(String... args) {
    List<String> cmd = new ArrayList<>(List.of("gpg", "--homedir", gpgHome.toString(), "--batch", "--yes", "--quiet"));
    cmd.addAll(List.of(args));
    return cmd;
  }

  private static String exec(Path dir, List<String> cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start();
    String out = new String(p.getInputStream().readAllBytes());
    String err = new String(p.getErrorStream().readAllBytes());
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("timed out: %s", cmd).isTrue();
    assertThat(p.exitValue()).as("%s failed: %s", cmd, err).isZero();
    return out;
  }

  private static List<Path> filesUnder(Path dir) {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).toList();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean commandExists(String cmd) {
    try {
      return new ProcessBuilder("bash", "-c", "command -v " + cmd).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }
}
