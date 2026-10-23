package mig.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The run report is what a finished run leaves behind for a human — until it was
 * written, recon printed to stdout and nothing else. It is pure and in-process, so unlike the settle
 * loop it can be unit-tested without a broker; and it must be, because "the file exists" is not the
 * same as "the file says the right thing".
 */
class ReportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final ReconService.BalanceResult BALANCED = new ReconService.BalanceResult(55, 50, 5, true);
  private static final ReconService.BatchResult ONE_BATCH = new ReconService.BatchResult(50, 1, 50, true);

  private static final List<ReconService.StageCheck> STAGES =
      List.of(
          new ReconService.StageCheck("intake", "account_src + parse rejects = src_read", 55, 55),
          new ReconService.StageCheck("curate", "account_curated = account_src", 51, 52));

  @Test
  void writesJsonAndHtmlThatAgreeWithTheVerdictAndTheBrokenStage() throws Exception {
    Path dir = Files.createTempDirectory("recon-report");
    Report.write(dir, "run-x", BALANCED, ONE_BATCH, STAGES, 50, 50, Set.of(), true, false);

    JsonNode json = MAPPER.readTree(dir.resolve("recon-run-x.json").toFile());
    assertThat(json.path("runId").asText()).isEqualTo("run-x");
    assertThat(json.path("ok").asBoolean()).as("a broken stage makes the report say so").isFalse();
    assertThat(json.path("balance").path("srcRead").asLong()).isEqualTo(55);
    assertThat(json.path("balance").path("balanced").asBoolean()).isTrue();
    assertThat(json.path("stages")).hasSize(2);
    assertThat(json.path("stages").get(0).path("holds").asBoolean()).isTrue();
    assertThat(json.path("stages").get(1).path("stage").asText()).isEqualTo("curate");
    assertThat(json.path("stages").get(1).path("holds").asBoolean()).as("51 != 52").isFalse();
    assertThat(json.path("settle").path("confirmed").asLong()).isEqualTo(50);

    String html = Files.readString(dir.resolve("recon-run-x.html"));
    assertThat(html).contains("FAILED", "curate", "51 vs 52", "BROKEN", "intake", "55 vs 55");
  }

  @Test
  void aPassingRunSaysPassedAndListsNoUnconfirmedKeys() throws Exception {
    Path dir = Files.createTempDirectory("recon-report");
    Report.write(dir, "run-ok", BALANCED, ONE_BATCH, List.of(), 50, 50, Set.of(), true, true);

    assertThat(Files.readString(dir.resolve("recon-run-ok.html"))).contains("PASSED").doesNotContain("BROKEN");
    JsonNode json = MAPPER.readTree(dir.resolve("recon-run-ok.json").toFile());
    assertThat(json.path("ok").asBoolean()).isTrue();
    assertThat(json.path("settle").path("unconfirmed")).isEmpty();
    assertThat(Files.readString(dir.resolve("recon-run-ok.html")))
        .as("no stage rows for a single-pass run, and it says why rather than showing zeros")
        .contains("single-pass variant");
  }

  @Test
  void unconfirmedKeysAreListedSortedSoTwoReportsOfTheSameFailureCompare() throws Exception {
    Path dir = Files.createTempDirectory("recon-report");
    Report.write(dir, "run-u", BALANCED, ONE_BATCH, STAGES, 50, 48, Set.of("zz-key", "aa-key"), true, false);

    JsonNode unconfirmed = MAPPER.readTree(dir.resolve("recon-run-u.json").toFile()).path("settle").path("unconfirmed");
    assertThat(unconfirmed.get(0).asText()).isEqualTo("aa-key");
    assertThat(unconfirmed.get(1).asText()).isEqualTo("zz-key");
  }

  @Test
  void whenNoBrokerWasConfiguredTheReportSaysSettlingWasNotChecked() throws Exception {
    Path dir = Files.createTempDirectory("recon-report");
    Report.write(dir, "run-n", BALANCED, ONE_BATCH, STAGES, 0, 0, Set.of(), false, true);

    assertThat(MAPPER.readTree(dir.resolve("recon-run-n.json").toFile()).path("settle").path("checked").asBoolean()).isFalse();
    assertThat(Files.readString(dir.resolve("recon-run-n.html"))).contains("not checked");
  }

  @Test
  void aRunIdContainingMarkupOrPathSeparatorsCannotInjectIntoTheHtmlOrEscapeTheDirectory() throws Exception {
    Path dir = Files.createTempDirectory("recon-report");
    String hostile = "<script>alert(1)</script>/../../etc";
    Report.write(dir, hostile, BALANCED, ONE_BATCH, List.of(), 0, 0, Set.of(), false, true);

    // the file lands INSIDE the directory, under a sanitised name — not in a subdirectory, not above it
    Path html = dir.resolve("recon-" + Report.fileSafe(hostile) + ".html");
    assertThat(html).exists();
    assertThat(Report.fileSafe(hostile)).doesNotContain("/", "<", ">");
    try (var files = Files.list(dir)) {
      assertThat(files.map(f -> f.getFileName().toString()).toList()).hasSize(2).allMatch(n -> n.startsWith("recon-"));
    }

    // the body shows the real id, escaped
    assertThat(Files.readString(html)).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
  }

  @Test
  void anUnwritableDirectoryNeverTurnsAVerdictIntoACrash() {
    // The report is a record of the verdict, not the verdict: failing to write it must not fail the run.
    assertThatCode(() -> Report.write(Path.of("/proc/definitely/not/writable"), "run-x", BALANCED, ONE_BATCH, STAGES, 0, 0, Set.of(), false, true))
        .doesNotThrowAnyException();
  }
}
