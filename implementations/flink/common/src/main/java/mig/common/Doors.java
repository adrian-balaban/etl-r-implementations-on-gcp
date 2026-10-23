package mig.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two doors, the reject taxonomy and the balancing equation.
 *
 * <p>Ported 1:1 from {@code pipelines/common/doors.py} in
 * beam-dataflow. Every source
 * record leaves through exactly one of two doors:
 *
 * <pre>SRC_read = migrated + not_migrated = TARGET_written + rejected</pre>
 */
public final class Doors {

  private Doors() {}

  /** The two doors. Every record leaves through exactly one. */
  public enum Exit {
    MIGRATED("migrated"),
    NOT_MIGRATED("not_migrated");

    public final String value;

    Exit(String value) {
      this.value = value;
    }
  }

  /** Which of the two doors a record left through. */
  public enum Door {
    WRITTEN("written"),
    REJECTED("rejected");

    public final String value;

    Door(String value) {
      this.value = value;
    }

    public Exit exit() {
      return this == WRITTEN ? Exit.MIGRATED : Exit.NOT_MIGRATED;
    }
  }

  /** Where in the engine a record was stopped. Order is load-bearing: parse -> map -> schema. */
  public enum Stage {
    PARSE("parse"),
    MAP("map"),
    SCHEMA("schema");

    public final String value;

    Stage(String value) {
      this.value = value;
    }
  }

  /** Enumerated reason codes — never free text, so record_lineage stays queryable by reason. */
  public enum Reason {
    // parse stage
    PARSE_SHORT_RECORD,
    PARSE_MISSING_FIELD,
    PARSE_BAD_NUMERIC,
    PARSE_BAD_DATE,
    PARSE_INVALID_JSON,
    PARSE_MISSING_JSON_PATH,
    PARSE_BAD_COLUMN_COUNT,
    // map stage
    MAP_MISSING_REQUIRED,
    MAP_UNKNOWN_TRANSFORM,
    MAP_UNMAPPED_ENUM_VALUE,
    MAP_TYPE_ERROR,
    // schema stage
    SCHEMA_INVALID
  }

  /** Raised by parse/map/validate to route a record through the REJECTED door. */
  public static final class RecordError extends RuntimeException {
    public final Stage stage;
    public final Reason reason;
    public final String detail;

    public RecordError(Stage stage, Reason reason, String detail) {
      super(stage.value + "/" + reason + ": " + detail);
      this.stage = stage;
      this.reason = reason;
      this.detail = detail == null ? "" : detail;
    }
  }

  /** One row of the reject_log table. */
  public record Reject(
      String runId,
      long batchId,
      String sourceKey,
      String stage,
      String reason,
      String detail,
      String rawRecord) {}

  /** One row of the record_lineage table — only not-migrated records are written here. */
  public record Lineage(
      String runId,
      String sourceKey,
      String accountKey,
      String door,
      String stage,
      String reason,
      String detail,
      String sourceFile) {}

  /** A stable identity for a record too malformed to yield a source key: sha256(raw)[:16]. */
  public static String contentName(String raw) {
    return "raw:" + Keys.sha256Hex16(raw);
  }

  /** Per-run tallies; the balancing equation is checked off these. */
  public static final class Counters {
    public long srcRead;
    public long written;
    public long rejected;
    public final Map<String, Long> byReason = new LinkedHashMap<>();

    public void count(Door door, Reason reason) {
      if (door == Door.WRITTEN) {
        written++;
      } else {
        rejected++;
        if (reason != null) {
          byReason.merge(reason.name(), 1L, Long::sum);
        }
      }
    }

    public long accounted() {
      return written + rejected;
    }

    public boolean balances() {
      return srcRead == accounted();
    }
  }
}
