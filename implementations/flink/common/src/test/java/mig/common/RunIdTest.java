package mig.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class RunIdTest {

  @Test
  void everyRunIdTheRepoAndItsEvidenceHaveUsedIsValid() {
    for (String ok : List.of("run-a", "run-gcp-001", "run-deepdive-clean-20260907", "run-local-20260926004112",
        "r", "0", "a-b-c", "a".repeat(RunId.MAX_LENGTH))) {
      assertThat(RunId.isValid(ok)).as(ok).isTrue();
      assertThat(RunId.require(ok)).isEqualTo(ok);
    }
  }

  /** Each of these reaches a different sink: a path, a SQL literal, a file name, a Kubernetes name. */
  @Test
  void hostileOrUnusableIdsAreRefused() {
    for (String bad : List.of(
        "../../etc/x",            // path traversal (gate: workRoot/<run_id>)
        "a/b", "a\\b",            // path separators
        "x'; DROP TABLE t; --",   // SQL literal breakout ('${RUN_ID}' in the job templates)
        "<script>",               // markup (recon's report)
        "Run_1", "RUN", "run.gcp", "run gcp", // not DNS-1123
        "-leading", "trailing-", "",           // shape
        "a".repeat(RunId.MAX_LENGTH + 1),      // flink-produce-<id> would exceed 63
        "run\n1")) {                           // control character
      assertThat(RunId.isValid(bad)).as("'%s'", bad).isFalse();
    }
    assertThatThrownBy(() -> RunId.require(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theErrorMessageNamesTheRuleAndCannotItselfCarryTheHostileInput() {
    assertThatThrownBy(() -> RunId.require("x\u0007y'; --"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase letters, digits")
        .hasMessageNotContaining("\u0007");

    String huge = "A".repeat(500);
    assertThatThrownBy(() -> RunId.require(huge)).satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
  }
}
