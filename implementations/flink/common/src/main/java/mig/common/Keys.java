package mig.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The two derived keys — both sha256, both truncated, neither
 * documented correctly in the original SQL sketch:
 *
 * <ul>
 *   <li>{@code raw:<hash>} — sha256(line)[:16], the identity of a record too malformed to
 *       yield a source key. Verified: sha256("ACC000900000|CUS0000000|RETL|CUR001")[:16] =
 *       4d5d9d96692584fa.
 *   <li>{@code account_key} — sha256(joined account-key fields, \x1f-separated)[:32].
 *       Verified: sha256("ACC000900003")[:32] = f3895bd9ae16e8e2626b578dff6f434b.
 * </ul>
 */
public final class Keys {

  private Keys() {}

  public static final char FIELD_SEPARATOR = '';

  public static String sha256Hex16(String raw) {
    return sha256Hex(raw).substring(0, 16);
  }

  public static String sha256Hex32(String raw) {
    return sha256Hex(raw).substring(0, 32);
  }

  /** Deterministic account key: sha256 of the \x1f-joined key fields, truncated to 32 hex. */
  public static String accountKey(Iterable<String> keyFieldValues) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String v : keyFieldValues) {
      if (!first) {
        sb.append(FIELD_SEPARATOR);
      }
      sb.append(v == null ? "" : v);
      first = false;
    }
    return sha256Hex32(sb.toString());
  }

  private static String sha256Hex(String raw) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
