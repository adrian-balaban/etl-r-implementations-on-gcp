package mig.gatejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CanonicalSqlTest {

  @Test
  void aPlainSchemaQualifiedTableIsAccepted() {
    assertThat(CanonicalSql.requireTableName("etlr_source.account")).isEqualTo("etlr_source.account");
  }

  @Test
  void anythingElseFromTheControlRowIsRefused() {
    assertThatThrownBy(() -> CanonicalSql.requireTableName("etlr_source.account; DROP TABLE x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalSql.requireTableName("account")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theDigestQueryPinsTheDateFormatInsteadOfTrustingDateStyle() {
    assertThat(CanonicalSql.countAndDigest("etlr_source.account"))
        .contains("to_char(open_date,'YYYY-MM-DD')")
        .contains("balance::text")
        .contains("::bit(60)::bigint");
  }
}
