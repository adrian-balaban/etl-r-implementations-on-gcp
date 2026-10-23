package mig.sql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The SQL jobs substitute the run id straight into string literals ({@code '${RUN_ID}'}), so an id
 * containing a quote is an injection vector, not merely a bad name. Each job must refuse a hostile id
 * BEFORE doing anything else.
 *
 * <p>The JDBC URL here is unusable on purpose: if validation were not the first thing that happens,
 * these would fail with a connection error (a different exception) instead of the RunId one — which is
 * how the test proves the ORDER, not just the eventual refusal.
 */
class JobEntryPointsRunIdTest {

  private static final String UNUSABLE_JDBC = "jdbc:postgresql://unreachable.invalid:1/none";

  @Test
  void everySqlJobRefusesAHostileRunIdBeforeTouchingTheDatabaseOrTheFilesystem() {
    for (String hostile : List.of("x'; DROP TABLE etlr_recon.reject_log; --", "../../escape", "Run_1")) {
      assertThatThrownBy(() -> IntakeJob.main(new String[] {"/nonexistent.src", hostile, UNUSABLE_JDBC, "u", "p", "."}))
          .as("IntakeJob '%s'", hostile).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid");
      assertThatThrownBy(() -> CurateEnrichJob.main(new String[] {hostile, "2026-01-01", UNUSABLE_JDBC, "u", "p", "."}))
          .as("CurateEnrichJob '%s'", hostile).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid");
      assertThatThrownBy(() -> ProduceJob.main(new String[] {hostile, UNUSABLE_JDBC, "u", "p"}))
          .as("ProduceJob '%s'", hostile).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid");
    }
  }
}
