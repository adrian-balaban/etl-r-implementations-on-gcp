package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for : a {@code .sql.template} with
 * two or more {@code INSERT}s and no {@code EXECUTE STATEMENT SET} block used to submit one
 * Flink job PER INSERT (each via its own {@code executeSql} call), which Flink's
 * application-mode entrypoint — the one the Flink Kubernetes Operator uses to run a
 * {@code FlinkDeployment} — rejects outright: "Cannot have more than one execute() or
 * executeAsync() call in a single environment". No local or in-process test caught this
 * because {@code CurateEnrichJobIT} (and every other job IT) calls a job's {@code main()}
 * in-process, which installs a {@code LocalStreamEnvironment} with no such guard — the bug
 * reproduces ONLY under a real application-mode deploy.
 *
 * <p>This test needs neither Flink environment nor cluster: {@link SqlJobSupport#planTemplate}
 * is pure string parsing, and "at most one job submission" is exactly the size of
 * {@link SqlJobSupport.Plan#batchedInserts()} being non-empty in at most one group — which,
 * post-fix, is unconditionally true by construction (every INSERT lands in the same list).
 * The synthetic-template cases below exist so a *future* regression in the parsing/grouping
 * logic itself — not just today's three templates — fails fast, in milliseconds, in
 * {@code mvn test}.
 */
class SqlJobSupportTest {

  @Test
  void templateWithNoStatementSetAndMultipleInsertsStillBatchesIntoOneJob() {
    // The exact shape 20-curate-enrich.sql.template had before the fix: no EXECUTE STATEMENT SET,
    // two INSERTs. Before the fix, executeTemplate() issued one executeSql() per INSERT here
    // — two job submissions, which is exactly what broke under application mode.
    String template =
        "CREATE TEMPORARY VIEW v1 AS SELECT * FROM src;\n"
            + "INSERT INTO t1 SELECT * FROM v1;\n"
            + "INSERT INTO t2 SELECT * FROM v1;\n";

    SqlJobSupport.Plan plan = SqlJobSupport.planTemplate(template, Map.of());

    assertThat(plan.sequential()).containsExactly("CREATE TEMPORARY VIEW v1 AS SELECT * FROM src");
    // The regression guard: however many INSERTs, they must all land in ONE group, never
    // split across separate sequential executeSql calls.
    assertThat(plan.batchedInserts())
        .containsExactly("INSERT INTO t1 SELECT * FROM v1", "INSERT INTO t2 SELECT * FROM v1");
  }

  @Test
  void templateWithNoStatementSetAndThreeInsertsStillBatchesIntoOneJob() {
    // A future template could add a third INSERT without ever adding a STATEMENT SET block
    // — must still be exactly one batch, not three sequential submissions.
    String template =
        "INSERT INTO t1 SELECT 1;\n" + "INSERT INTO t2 SELECT 2;\n" + "INSERT INTO t3 SELECT 3;\n";

    SqlJobSupport.Plan plan = SqlJobSupport.planTemplate(template, Map.of());

    assertThat(plan.sequential()).isEmpty();
    assertThat(plan.batchedInserts()).hasSize(3);
  }

  @Test
  void templateWithExplicitStatementSetBlockBatchesItsInserts() {
    String template =
        "CREATE TEMPORARY VIEW v1 AS SELECT * FROM src;\n"
            + "EXECUTE STATEMENT SET\n"
            + "BEGIN\n"
            + "INSERT INTO t1 SELECT * FROM v1;\n"
            + "INSERT INTO t2 SELECT * FROM v1;\n"
            + "END;\n";

    SqlJobSupport.Plan plan = SqlJobSupport.planTemplate(template, Map.of());

    assertThat(plan.sequential()).containsExactly("CREATE TEMPORARY VIEW v1 AS SELECT * FROM src");
    assertThat(plan.batchedInserts())
        .containsExactly("INSERT INTO t1 SELECT * FROM v1", "INSERT INTO t2 SELECT * FROM v1");
  }

  @Test
  void templateWithOnlyDdlHasNoBatchedInserts() {
    String template = "CREATE TEMPORARY VIEW v1 AS SELECT * FROM src;\n";

    SqlJobSupport.Plan plan = SqlJobSupport.planTemplate(template, Map.of());

    assertThat(plan.sequential()).containsExactly("CREATE TEMPORARY VIEW v1 AS SELECT * FROM src");
    assertThat(plan.batchedInserts()).isEmpty();
  }

  /**
   * The three real templates this repo ships — parsed exactly as {@code IntakeJob}/
   * {@code CurateEnrichJob}/{@code ProduceJob} parse them, asserting each submits at most one
   * Flink job (a non-empty {@code batchedInserts} counts as exactly one submission,
   * regardless of how many INSERTs it holds).
   */
  @Test
  void everyShippedTemplateSubmitsAtMostOneJob() {
    assertAtMostOneJobSubmission(
        "/sql/10-intake.sql.template",
        Map.of("RUN_ID", "run-x", "REPO_ROOT", "/repo", "SRC_FILE_PATH", "/tmp/x.src"));
    assertAtMostOneJobSubmission(
        "/sql/20-curate-enrich.sql.template", Map.of("RUN_ID", "run-x", "AS_OF_DATE", "2026-01-01"));
    assertAtMostOneJobSubmission("/sql/30-produce.sql.template", Map.of("RUN_ID", "run-x"));
  }

  private static void assertAtMostOneJobSubmission(String resourcePath, Map<String, String> params) {
    String template = SqlJobSupport.loadResource(SqlJobSupportTest.class, resourcePath);
    SqlJobSupport.Plan plan = SqlJobSupport.planTemplate(template, params);
    List<String> inserts = plan.batchedInserts();
    // A job is submitted iff there is at least one INSERT, and — by construction of
    // planTemplate/executeTemplate — always as exactly one StatementSet, never one per
    // INSERT. The count that matters for "how many job submissions" is 0 or 1, never the
    // number of INSERTs itself; asserting non-emptiness here is the meaningful check
    // (every one of these three templates does have INSERTs) alongside the synthetic
    // multi-insert cases above, which are what actually pin "always exactly one group".
    assertThat(inserts).as("INSERTs in %s", resourcePath).isNotEmpty();
  }

  /**
   * Batch mode, both directions — the test the spec asked for and the repo
   * did not have: a test that fails if the environment is streaming.
   *
   * <p>Before this, every job called {@code setRuntimeMode(BATCH)} and nothing verified it.
   * Deleting that call broke no test, because the fixture's bounded file source terminates in
   * either mode; what it would have broken is the meaning of a "run" — and silently.
   */
  @Test
  void batchEnvironmentIsInBatchMode() {
    assertThat(
            SqlJobSupport.batchEnvironment()
                .getConfiguration()
                .get(org.apache.flink.configuration.ExecutionOptions.RUNTIME_MODE))
        .isEqualTo(org.apache.flink.api.common.RuntimeExecutionMode.BATCH);
  }

  @Test
  void requireBatchRejectsAStreamingEnvironment() {
    var env =
        org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.getExecutionEnvironment();
    env.setRuntimeMode(org.apache.flink.api.common.RuntimeExecutionMode.STREAMING);
    assertThatThrownBy(() -> SqlJobSupport.requireBatch(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires batch execution");
  }

  @Test
  void kafkaSecurityOptionsAreEmptyForPlaintextLocalBrokers() {
    // KAFKA_SECURITY_PROTOCOL is unset in every local test — the SQL Kafka sink must then
    // carry no SASL options at all, or local redpanda refuses the connection.
    assertThat(SqlJobSupport.kafkaSecurityOptions()).isEmpty();
  }

  /**
   * The bug that only a real Managed Kafka deploy could find: the SASL JAAS
   * option value ends in a semicolon, and the splitter cut the string literal in half.
   */
  @Test
  void splitStatementsDoesNotCutASemicolonInsideAStringLiteral() {
    List<String> statements =
        SqlJobSupport.splitStatements(
            "CREATE TABLE t (a STRING) WITH ('k' = 'x required;'); INSERT INTO t SELECT 'a;b'; ");

    assertThat(statements).hasSize(2);
    assertThat(statements.get(0)).endsWith("'x required;')");
    assertThat(statements.get(1)).isEqualTo("INSERT INTO t SELECT 'a;b'");
  }

  @Test
  void splitStatementsHandlesAnEscapedQuoteInsideALiteral() {
    // SQL escapes a quote by doubling it; the two toggles must cancel, not open a string.
    List<String> statements = SqlJobSupport.splitStatements("SELECT 'it''s; fine'; SELECT 2;");
    assertThat(statements).containsExactly("SELECT 'it''s; fine'", "SELECT 2");
  }

  /**
   * The exact statement set the produce job builds on GCP: both templates, with the real
   * SASL_SSL fragment. Locally the fragment is empty (PLAINTEXT) so no other test ever
   * exercises this shape — which is precisely why it reached a cluster broken.
   */
  @Test
  void produceWithKafkaSplitsCleanlyWhenSaslOptionsAreOn() {
    java.util.Map<String, String> params =
        java.util.Map.of(
            "RUN_ID", "run-x",
            "KAFKA_BOOTSTRAP", "broker.example:9092",
            "KAFKA_TARGET_TOPIC", "target-system-target",
            "KAFKA_SECURITY_OPTIONS", SqlJobSupport.kafkaSecurityOptions("SASL_SSL"));

    SqlJobSupport.Plan plan =
        SqlJobSupport.planTemplate(
            SqlJobSupport.loadResource(SqlJobSupportTest.class, "/sql/35-produce-kafka.sql.template"),
            params);

    // Every statement must have balanced quotes — a cut literal leaves an odd count.
    for (String stmt : plan.sequential()) {
      assertThat(stmt.chars().filter(c -> c == '\'').count() % 2)
          .as("balanced quotes in: %s", stmt)
          .isZero();
    }
    String createTable =
        plan.sequential().stream().filter(x -> x.startsWith("CREATE TABLE target_kafka")).findFirst().orElseThrow();
    assertThat(createTable)
        .contains("OAuthBearerLoginModule required;'")
        .contains("'value.fields-include' = 'EXCEPT_KEY'")
        .contains("'properties.security.protocol' = 'SASL_SSL'");
    assertThat(plan.batchedInserts()).hasSize(1);
  }
}
