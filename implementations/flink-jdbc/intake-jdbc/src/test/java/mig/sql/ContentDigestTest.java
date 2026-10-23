package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Pinned values from spec/PIPELINE.md "Source System as a database" (also checked by load_db.py and Postgres). */
class ContentDigestTest {

  private static final String TRUNCATED = "ACC000900000|CUS0000000|RETL|CUR001||||";

  @Test
  void thePartOfTheTruncatedRowIsThePinnedOne() {
    assertThat(ContentDigest.part(TRUNCATED)).isEqualTo(861444277401316030L);
  }

  @Test
  void theContentNameOfTheTruncatedRowIsThePinnedOne() {
    assertThat(ContentDigest.contentName(TRUNCATED)).isEqualTo("raw:bf47701944c6abec");
  }

  @Test
  void theDigestIgnoresOrder() {
    assertThat(ContentDigest.digest(List.of("x|1", "y|2"))).isEqualTo(ContentDigest.digest(List.of("y|2", "x|1")));
  }

  @Test
  void aDuplicatedRowCountsTwiceInsteadOfCancelling() {
    BigInteger once = ContentDigest.digest(List.of("x|1"));
    assertThat(ContentDigest.digest(List.of("x|1", "x|1"))).isEqualTo(once.shiftLeft(1)).isNotEqualTo(BigInteger.ZERO);
  }

  @Test
  void theUdfReturnsThePartAndPassesNullThrough() {
    ContentDigest.Part udf = new ContentDigest.Part();
    assertThat(udf.eval(TRUNCATED)).isEqualTo(861444277401316030L);
    assertThat(udf.eval(null)).isNull();
  }
}
