package mig.sql;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.apache.flink.table.functions.ScalarFunction;

/**
 * The content digest of spec/PIPELINE.md "Source System as a database": {@code part} = the first 15 hex
 * digits (60 bits) of sha256(canonical line); the digest of a run is the exact sum of the parts. A sum,
 * not XOR, so duplicates count; order-independent, so partitioned reads need no sort and no collation.
 * The same formula runs in Python (spec/harness/load_db.py) and in Postgres (gate-jdbc's CanonicalSql).
 */
public final class ContentDigest {

  private ContentDigest() {}

  public static long part(String canonicalLine) {
    return Long.parseLong(sha256Hex(canonicalLine).substring(0, 15), 16);
  }

  /** {@code raw:} + sha256(canonical line)[:16] — record_lineage.source_key for a parse reject. */
  public static String contentName(String canonicalLine) {
    return "raw:" + sha256Hex(canonicalLine).substring(0, 16);
  }

  public static BigInteger digest(Iterable<String> canonicalLines) {
    BigInteger sum = BigInteger.ZERO;
    for (String line : canonicalLines) {
      sum = sum.add(BigInteger.valueOf(part(line)));
    }
    return sum;
  }

  static String sha256Hex(String text) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JRE", e);
    }
  }

  /** Flink SQL scalar function {@code CONTENT_DIGEST_PART(canonical_line)}, registered by JdbcIntakeJob. */
  public static final class Part extends ScalarFunction {
    public Long eval(String canonicalLine) {
      return canonicalLine == null ? null : part(canonicalLine);
    }
  }
}
