package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SourceColumnsTest {

  private static final Path TDS = Path.of("..").toAbsolutePath().normalize()
      .resolve("contracts/tds/tds-src-project1.def");

  @Test
  void theTdsFieldsAreTheEightAccountColumnsInOrder() throws Exception {
    assertThat(SourceColumns.tdsFields(TDS)).containsExactly(
        "acct_id", "cust_id", "client_type", "product_code", "currency", "open_date", "status", "balance");
  }

  @Test
  void aMissingColumnIsReported() throws Exception {
    List<String> expected = SourceColumns.tdsFields(TDS);
    Set<String> actual = Set.of("acct_id", "cust_id", "client_type", "product_code", "currency", "open_date", "status", "bal");
    assertThat(SourceColumns.missing(expected, actual)).containsExactly("balance");
  }

  @Test
  void extraSourceColumnsAreAllowed() throws Exception {
    List<String> expected = SourceColumns.tdsFields(TDS);
    Set<String> actual = new java.util.HashSet<>(expected);
    actual.add("audit_ts");
    assertThat(SourceColumns.missing(expected, actual)).isEmpty();
  }
}
