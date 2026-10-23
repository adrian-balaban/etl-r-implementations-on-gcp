package mig.common;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Coverage guard : fails if target-account.schema.json grows an unhandled constraint. */
class TargetSchemaValidatorTest {

  @Test
  void coversEveryConstraintDeclaredInTheSchema() {
    Path root = Path.of("..").toAbsolutePath().normalize();
    new TargetSchemaValidator(root.resolve("contracts/schemas/target-account.schema.json"))
        .checkCoversSchemaConstraints();
  }
}
