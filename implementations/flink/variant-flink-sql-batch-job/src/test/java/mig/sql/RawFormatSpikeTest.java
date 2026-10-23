package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.Test;

/**
 * The raw-format spike, finally run.
 *
 * <p>This was blocking and never attempted — the documented fallback
 * ({@code FileSource} + {@code TextLineInputFormat}, which {@code IntakeJob} uses) was taken
 * directly, and the question stayed "genuinely unknown, not
 * tried and failed". It matters beyond bookkeeping: the original SQL sketch
 * opens with {@code 'connector'='filesystem'} + {@code 'format'='raw'}, so whether that
 * gives one row per LINE or one row per FILE decides whether the sketch works as written.
 *
 * <p>Needs no container and no cluster — a three-line temp file and the mini cluster the
 * Table API brings up in-process. It stayed unanswered for the price of about thirty seconds.
 *
 * <p>Answer: one row per LINE. See the comment on the assertion for what follows from it.
 */
class RawFormatSpikeTest {

  @Test
  void rawFormatOnTheFilesystemConnectorGivesOneRowPerLine() throws Exception {
    Path dir = Files.createTempDirectory("raw-spike");
    Files.writeString(dir.resolve("data.txt"), "line-one\nline-two\nline-three\n");

    StreamTableEnvironment tEnv = StreamTableEnvironment.create(SqlJobSupport.batchEnvironment());
    tEnv.executeSql(
        "CREATE TABLE src_raw (line STRING) WITH ("
            + " 'connector'='filesystem',"
            + " 'path'='" + dir.toAbsolutePath() + "',"
            + " 'format'='raw'"
            + ")");

    List<String> rows = new ArrayList<>();
    try (var it = tEnv.executeSql("SELECT line FROM src_raw").collect()) {
      while (it.hasNext()) {
        rows.add(String.valueOf(it.next().getField(0)));
      }
    }

    // THE ANSWER: one row per LINE. `format = 'raw'` on the filesystem connector splits on
    // newlines, so the original sketch works as written on this point — the
    // `CREATE TABLE src_raw (line STRING)` it opens with really does yield one row per
    // record, and the concern that it might hand over the whole file as a single value is
    // unfounded.
    //
    // What this does NOT mean is that IntakeJob should be rewritten to use it. The
    // fallback (FileSource + TextLineInputFormat) is already proven end to end, including on
    // GCS and on a real GKE cluster, and it is what lets IntakeJob filter the header row
    // against the TDS contract before the Table API ever sees the data. The value
    // of finally running this spike is that "we chose the fallback" is now a decision with a
    // known alternative behind it, not an open question the repo had been carrying since
    // the start, and this closes it.
    assertThat(rows).containsExactlyInAnyOrder("line-one", "line-two", "line-three");
  }
}
