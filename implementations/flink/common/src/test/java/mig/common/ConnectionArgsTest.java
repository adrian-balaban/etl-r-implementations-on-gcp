package mig.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ConnectionArgsTest {

  private static final Map<String, String> ENV = Map.of("JDBC_PASSWORD", "s3cret", "EMPTY", "");

  @Test
  void aLiteralPasswordIsReturnedUnchangedSoEveryExistingCallerKeepsWorking() {
    assertThat(ConnectionArgs.resolve("mig", ENV::get)).isEqualTo("mig");
    assertThat(ConnectionArgs.resolve("p@ss:word", ENV::get)).as("a colon alone is not the prefix").isEqualTo("p@ss:word");
    assertThat(ConnectionArgs.resolve(null, ENV::get)).isNull();
  }

  @Test
  void anEnvArgumentIsReplacedByTheEnvironmentVariablesValue() {
    assertThat(ConnectionArgs.resolve("env:JDBC_PASSWORD", ENV::get)).isEqualTo("s3cret");
  }

  @Test
  void aMissingOrEmptyVariableFailsAndNamesTheVariableButNeverALiteralEnvString() {
    assertThatThrownBy(() -> ConnectionArgs.resolve("env:NOPE", ENV::get))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOPE")
        .hasMessageContaining("Secret");
    assertThatThrownBy(() -> ConnectionArgs.resolve("env:EMPTY", ENV::get)).isInstanceOf(IllegalStateException.class);
  }
}
