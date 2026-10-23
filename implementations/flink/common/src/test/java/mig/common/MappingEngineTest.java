package mig.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mig.common.Doors.Reason;
import mig.common.Doors.RecordError;
import mig.common.Doors.Stage;

/**
 * Routes the five malformed rows from fixtures/ACCOUNT.src through parse -> map -> schema and
 * checks each lands on the exact stage/reason/detail from fixtures/expected/reject_log.tsv
 *.
 */
class MappingEngineTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static Mapping.Engine engine;
  private static TargetSchemaValidator validator;

  @BeforeAll
  static void setup() {
    Mapping.Config cfg =
        Mapping.load(ROOT.resolve("contracts/mappings/mapping-project1.yaml"), ROOT);
    engine = new Mapping.Engine(cfg);
    validator = new TargetSchemaValidator(cfg.targetSchemaPath());
  }

  private RecordError routeToRejection(String raw) {
    Map<String, Object> fields;
    try {
      fields = engine.parse(raw);
    } catch (RecordError e) {
      return e;
    }
    Map<String, Object> doc;
    try {
      doc = engine.mapRecord(fields, "run-test");
    } catch (RecordError e) {
      return e;
    }
    try {
      validator.validate(doc);
    } catch (RecordError e) {
      return e;
    }
    throw new AssertionError("expected a rejection for: " + raw);
  }

  @Test
  void truncatedRowFailsOnCurrencyColumn() {
    RecordError e = routeToRejection("ACC000900000|CUS0000000|RETL|CUR001");
    assertThat(e.stage).isEqualTo(Stage.PARSE);
    assertThat(e.reason).isEqualTo(Reason.PARSE_BAD_COLUMN_COUNT);
    assertThat(e.detail).isEqualTo("CURRENCY needs column 4, row has 4");
  }

  @Test
  void badDateRejected() {
    RecordError e =
        routeToRejection("ACC000900002|CUS0000002|RETL|LON003|RON|20191345|D|+00000250092.20");
    assertThat(e.stage).isEqualTo(Stage.PARSE);
    assertThat(e.reason).isEqualTo(Reason.PARSE_BAD_DATE);
    assertThat(e.detail).isEqualTo("OPEN_DATE='20191345'");
  }

  @Test
  void badNumericRejected() {
    RecordError e =
        routeToRejection("ACC000900001|CUS0000001|RETL|LON003|EUR|20050725|A|+0000000ABCD.56");
    assertThat(e.stage).isEqualTo(Stage.PARSE);
    assertThat(e.reason).isEqualTo(Reason.PARSE_BAD_NUMERIC);
    assertThat(e.detail).isEqualTo("BALANCE='+0000000ABCD.56'");
  }

  @Test
  void unmappedEnumRejected() {
    RecordError e =
        routeToRejection("ACC000900003|CUS0000003|RETL|CUR001|RON|20001218|X|+00000393458.24");
    assertThat(e.stage).isEqualTo(Stage.MAP);
    assertThat(e.reason).isEqualTo(Reason.MAP_UNMAPPED_ENUM_VALUE);
    assertThat(e.detail).isEqualTo("status: 'X' not in ['A', 'C', 'D']");
  }

  @Test
  void schemaInvalidAccountIdRejected() {
    RecordError e =
        routeToRejection("acc-bad!0001|CUS0000004|RETL|SAV001|EUR|20141108|D|+00000404951.74");
    assertThat(e.stage).isEqualTo(Stage.SCHEMA);
    assertThat(e.reason).isEqualTo(Reason.SCHEMA_INVALID);
    assertThat(e.detail).isEqualTo("accountId: 'acc-bad!0001' does not match '^[A-Z0-9]+$'");
  }

  @Test
  void wellFormedRowIsWritten() {
    Map<String, Object> fields =
        engine.parse("ACC000000011|CUS0000011|RETL|DEP002|USD|20080920|D|+00000300299.47");
    Map<String, Object> doc = engine.mapRecord(fields, "run-test");
    validator.validate(doc); // must not throw
    assertThat(engine.accountKey(fields)).hasSize(32);
  }
}
