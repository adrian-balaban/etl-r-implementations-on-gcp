package mig.gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Garantia 1: extraction gated by {@code .FLG} / {@code .CHS}. The job
 * refuses to start without a {@code .FLG}; a mismatched {@code .CHS} exits non-zero with zero
 * rows written. Runs before Flink, not as a Flink stage — this is an assertion about a
 * completed extraction, not a transformation.
 *
 * <p>Given an {@code incoming/} directory (produced by harness/bundle.py: {@code ACCOUNT.FLG}
 * outside the bundle, {@code ACCOUNT.tar.gz} containing the {@code .DAT} + {@code .CHS}), this
 * verifies the flag, untars, verifies checksums, then materialises the payload at
 * {@code work/<run_id>/ACCOUNT.src} — the path the Flink jobs' filesystem source reads.
 */
public final class Gate {

  public static final class GateFailure extends RuntimeException {
    public GateFailure(String message) {
      super(message);
    }
  }

  private Gate() {}

  /** @return the path to the materialised, gate-passed extract file. */
  public static Path run(Path incomingDir, String recordName, String runId, Path workRoot) {
    return run(incomingDir, recordName, runId, workRoot, Pgp.Key.fromEnvironment());
  }

  /**
   * As above, with the PGP key given explicitly (or {@code null} for none). A plain
   * {@code <record>.tar.gz} needs no key; {@code <record>.tar.gz.pgp} needs one and is refused
   * without it — before anything is decrypted, checksummed or written.
   */
  public static Path run(Path incomingDir, String recordName, String runId, Path workRoot, Pgp.Key pgpKey) {
    // FIRST, before any path is built: the run id becomes workRoot/<run_id>/, and "../../x" would
    // write the extracted file outside the work directory. Refuse like every other gate failure.
    try {
      mig.common.RunId.require(runId);
    } catch (IllegalArgumentException e) {
      throw new GateFailure("extraction refused: " + e.getMessage());
    }
    Path flg = incomingDir.resolve(recordName + ".FLG");
    if (!Files.exists(flg)) {
      throw new GateFailure(
          "extraction refused: " + flg + " is missing — the extract is not marked complete");
    }

    Path tarPath = incomingDir.resolve(recordName + ".tar.gz");
    Path pgpPath = incomingDir.resolve(recordName + ".tar.gz.pgp");
    boolean plain = Files.exists(tarPath);
    boolean encrypted = Files.exists(pgpPath);
    if (plain && encrypted) {
      // Which one is authoritative? Guessing is how a stale unencrypted copy gets ingested in
      // place of the real delivery, so the gate does not guess.
      throw new GateFailure(
          "extraction refused: both " + tarPath.getFileName() + " and " + pgpPath.getFileName()
              + " are present — refusing to choose between them");
    }
    if (!plain && !encrypted) {
      throw new GateFailure("extraction refused: bundle " + tarPath + " (or " + pgpPath.getFileName() + ") is missing");
    }

    byte[] bundle;
    try {
      bundle = Files.readAllBytes(plain ? tarPath : pgpPath);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (encrypted) {
      if (pgpKey == null) {
        throw new GateFailure(
            "extraction refused: " + pgpPath.getFileName()
                + " is encrypted but no key is configured (PGP_PRIVATE_KEY_FILE / PGP_PASSPHRASE)");
      }
      bundle = Pgp.decrypt(bundle, pgpKey);
    }

    Map<String, byte[]> files = Archives.unTarGz(bundle);
    byte[] chsBytes = files.get(recordName + ".CHS");
    if (chsBytes == null) {
      throw new GateFailure("extraction refused: bundle has no " + recordName + ".CHS manifest");
    }
    Map<String, Checksums.Entry> manifest =
        Checksums.parse(new String(chsBytes, StandardCharsets.UTF_8));
    Map<String, byte[]> dataFiles =
        files.entrySet().stream()
            .filter(e -> !e.getKey().equals(recordName + ".CHS"))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    List<String> problems = Checksums.verify(manifest, dataFiles);
    if (!problems.isEmpty()) {
      throw new GateFailure(
          "extraction refused: .CHS mismatch for run " + runId + " — " + String.join("; ", problems));
    }

    // Exactly one data file expected per this contract (ACCOUNT.001.DAT).
    if (dataFiles.size() != 1) {
      throw new GateFailure(
          "extraction refused: expected exactly 1 data file in bundle, got " + dataFiles.keySet());
    }
    byte[] payload = dataFiles.values().iterator().next();

    Path outDir = workRoot.resolve(runId);
    Path outFile = outDir.resolve(recordName + ".src");
    try {
      Files.createDirectories(outDir);
      Files.write(outFile, payload);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return outFile;
  }

  /**
   * The number of DATA records in a gate-passed extract: physical lines, minus the header row
   * when the source contract says the layout has one.
   *
   * <p>This is what recon's {@code srcRead} must be, and it is NOT the {@code .CHS} "records"
   * column. That column is {@link Checksums#countRecords}, a physical line count — the
   * integrity check is about the file as delivered, header included. The fixture is 56 lines
   * (1 header + 55 records), the oracle's {@code run_ledger} says {@code src_read=55}, and
   *  "srcRead from the .CHS record count" was therefore only correct for
   * a headerless file. Found when the first real GCP run reported {@code 56 != 50 + 5}: every
   * other stage had balanced, and the settle loop had already confirmed all 50 documents.
   *
   * <p>Whether there is a header comes from the mapping/TDS contract, the same source
   * {@code IntakeJob} uses to render its header filter  — not a literal here, so the
   * two cannot disagree about what a header is.
   */
  public static long dataRecords(byte[] payload, Path repoRoot) {
    mig.common.Mapping.Config cfg =
        mig.common.Mapping.load(repoRoot.resolve("contracts/mappings/mapping-project1.yaml"), repoRoot);
    boolean hasHeader = new mig.common.Tds.RecordParser(cfg.srcRecord(), cfg.sourceLayout()).hasHeader();
    long lines = Checksums.countRecords(payload);
    return hasHeader ? Math.max(0, lines - 1) : lines;
  }

  /**
   * {@code java -jar gate.jar <incomingDir> <recordName> <runId> <workRoot> [repoRoot]}
   *
   * <p>With {@code repoRoot} (a directory containing {@code contracts/}), also prints
   * {@code gate: data_records=N} — the machine-readable line the orchestrator reads
   * to give recon its {@code srcRead}.
   */
  public static void main(String[] args) {
    if (args.length != 4 && args.length != 5) {
      System.err.println("usage: Gate <incomingDir> <recordName> <runId> <workRoot> [repoRoot]");
      System.exit(2);
    }
    try {
      Path out = run(Path.of(args[0]), args[1], args[2], Path.of(args[3]));
      System.out.println("gate: OK -> " + out);
      if (args.length == 5) {
        System.out.println(
            "gate: data_records=" + dataRecords(Files.readAllBytes(out), Path.of(args[4])));
      }
    } catch (GateFailure e) {
      System.err.println("gate: " + e.getMessage());
      System.exit(1);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
