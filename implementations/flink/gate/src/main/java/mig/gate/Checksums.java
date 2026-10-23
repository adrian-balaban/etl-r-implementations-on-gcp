package mig.gate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code .CHS} artefact — checksums for reconciliation. Ported from
 * {@code apps/common/src/main/java/ro/mig/common/Checksums.java} in
 * beam-dataflow; same line format, so harness/bundle.py's output
 * parses unchanged.
 */
public final class Checksums {

  private Checksums() {}

  public record Entry(String sha256, long records, String fileName) {}

  public static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static long countRecords(byte[] content) {
    if (content.length == 0) {
      return 0;
    }
    long lines = 0;
    for (byte b : content) {
      if (b == '\n') {
        lines++;
      }
    }
    if (content[content.length - 1] != '\n') {
      lines++;
    }
    return lines;
  }

  public static Map<String, Entry> parse(String text) {
    Map<String, Entry> out = new LinkedHashMap<>();
    for (String line : text.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      String[] parts = trimmed.split("\\s+");
      if (parts.length < 3) {
        throw new IllegalArgumentException("malformed .CHS line: " + line);
      }
      out.put(parts[2], new Entry(parts[0], Long.parseLong(parts[1]), parts[2]));
    }
    return out;
  }

  /** @return discrepancies; empty means the extract is intact. */
  public static List<String> verify(Map<String, Entry> manifest, Map<String, byte[]> payloads) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Entry> expected : manifest.entrySet()) {
      byte[] payload = payloads.get(expected.getKey());
      if (payload == null) {
        problems.add("missing file listed in .CHS: " + expected.getKey());
        continue;
      }
      String actual = sha256(payload);
      if (!actual.equals(expected.getValue().sha256())) {
        problems.add(
            "checksum mismatch for " + expected.getKey()
                + ": expected " + expected.getValue().sha256() + " got " + actual);
      }
      long records = countRecords(payload);
      if (records != expected.getValue().records()) {
        problems.add(
            "record count mismatch for " + expected.getKey()
                + ": expected " + expected.getValue().records() + " got " + records);
      }
    }
    for (String present : payloads.keySet()) {
      if (!manifest.containsKey(present)) {
        problems.add("file not listed in .CHS: " + present);
      }
    }
    return problems;
  }
}
