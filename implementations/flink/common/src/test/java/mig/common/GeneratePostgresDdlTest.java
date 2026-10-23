package mig.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 *  debt #3: the checked-in {@code sql/postgres-init.sql} must agree
 * with what the contracts say, for the one table that is mechanically derivable from them.
 *
 * <p>This is the whole point of "nu se scrie de mana" — not that generation is
 * tidier, but that a hand-written schema and a contract are two descriptions of one thing
 * and they drift apart in silence. Rename a field in {@code tds-src-project1.def} without
 * touching the SQL and this test fails; before it, the lane would have kept running and
 * written the old column name until something downstream noticed.
 */
class GeneratePostgresDdlTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @Test
  void checkedInDdlForAccountSrcMatchesTheContracts() throws Exception {
    String generated = GeneratePostgresDdl.accountSrcDdl(ROOT.resolve("contracts"));
    String checkedIn = extractAccountSrcStatement(Files.readString(ROOT.resolve("sql/postgres-init.sql")));

    assertThat(normalise(checkedIn))
        .as(
            "sql/postgres-init.sql's etlr_extraction.account_src has drifted from"
                + " contracts/tds/tds-src-project1.def + contracts/artefacts.json.%n"
                + "generated:%n%s%n%nchecked in:%n%s",
            generated, checkedIn)
        .isEqualTo(normalise(generated));
  }

  /** Pulls the account_src CREATE TABLE out of the init script, comments stripped. */
  private static String extractAccountSrcStatement(String sql) {
    int start = sql.indexOf("CREATE TABLE etlr_extraction.account_src");
    assertThat(start).as("account_src statement present in postgres-init.sql").isNotNegative();
    int end = sql.indexOf(");", start);
    return sql.substring(start, end + 2);
  }

  /**
   * Compares meaning, not whitespace or trailing comments: the checked-in file aligns
   * columns for readability and annotates {@code balance} with why a decimal is TEXT, and
   * neither should be a reason for this test to fail.
   */
  private static String normalise(String ddl) {
    return ddl.lines()
        .map(line -> {
          int comment = line.indexOf("--");
          return (comment >= 0 ? line.substring(0, comment) : line).replaceAll("\\s+", " ").strip();
        })
        .filter(line -> !line.isEmpty())
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }
}
