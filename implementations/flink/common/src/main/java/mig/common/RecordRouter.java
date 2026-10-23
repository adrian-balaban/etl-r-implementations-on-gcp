package mig.common;

import java.util.Map;

import mig.common.Doors.Door;
import mig.common.Doors.RecordError;
import mig.common.Doors.Reject;

/**
 * Routes one raw source line through parse -&gt; map -&gt; schema, in order. Deliberately
 * Flink-free: this is the correctness contract both the SQL variant and the DataStream
 * variant have to reproduce, testable without a runner (component-tests exercises this class
 * directly against fixtures/expected).
 */
public final class RecordRouter {

  /** Which door a single record left through, and what it produced. */
  public record Outcome(
      Door door,
      String raw,
      String accountKey,
      String sourceKey,
      Map<String, Object> doc,
      Reject reject) {}

  private final Mapping.Engine engine;
  private final TargetSchemaValidator validator;
  private final String runId;

  public RecordRouter(Mapping.Engine engine, TargetSchemaValidator validator, String runId) {
    this.engine = engine;
    this.validator = validator;
    this.runId = RunId.require(runId);
  }

  public Outcome route(String raw, long batchId) {
    Map<String, Object> fields;
    try {
      fields = engine.parse(raw);
    } catch (RecordError e) {
      // Parse-stage reject: source_key is EMPTY STRING in reject_log —
      // never `raw:<hash>` here, that rule belongs to record_lineage only.
      return new Outcome(
          Door.REJECTED,
          raw,
          null,
          "",
          null,
          new Reject(runId, batchId, "", e.stage.value, e.reason.name(), e.detail, raw));
    }

    String sourceKey = engine.sourceKey(fields);
    String accountKey = engine.accountKey(fields);

    Map<String, Object> doc;
    try {
      doc = engine.mapRecord(fields, runId);
      validator.validate(doc);
    } catch (RecordError e) {
      return new Outcome(
          Door.REJECTED,
          raw,
          accountKey,
          sourceKey,
          null,
          new Reject(runId, batchId, sourceKey, e.stage.value, e.reason.name(), e.detail, raw));
    }

    return new Outcome(Door.WRITTEN, raw, accountKey, sourceKey, doc, null);
  }

  /**
   * The not-migrated lineage row for a rejected outcome.
   */
  public static Doors.Lineage lineageOf(Outcome outcome, String sourceFile) {
    if (outcome.door() != Door.REJECTED) {
      throw new IllegalArgumentException("lineage is only recorded for the not-migrated door");
    }
    Reject r = outcome.reject();
    String lineageSourceKey =
        (outcome.sourceKey() == null || outcome.sourceKey().isEmpty())
            ? Doors.contentName(outcome.raw())
            : outcome.sourceKey();
    return new Doors.Lineage(
        r.runId(),
        lineageSourceKey,
        outcome.accountKey(),
        Door.REJECTED.value,
        r.stage(),
        r.reason(),
        r.detail(),
        sourceFile);
  }
}
