package mig.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Verifies the two derived keys against the values pinned in fixtures/manifest.json . */
class KeysTest {

  @Test
  void rawContentHashMatchesEvidence() {
    String truncatedRow = "ACC000900000|CUS0000000|RETL|CUR001";
    assertThat(Keys.sha256Hex16(truncatedRow)).isEqualTo("4d5d9d96692584fa");
    assertThat(Doors.contentName(truncatedRow)).isEqualTo("raw:4d5d9d96692584fa");
  }

  @Test
  void accountKeyMatchesEvidence() {
    assertThat(Keys.accountKey(List.of("ACC000900003")))
        .isEqualTo("f3895bd9ae16e8e2626b578dff6f434b");
  }
}
