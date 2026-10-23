package mig.sql;

import java.util.ArrayList;
import java.util.List;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * Shared plumbing for the three Flink SQL jobs : loading a `.sql.template` resource, splitting it into the CREATE VIEW
 * statements before a {@code STATEMENT SET} and the INSERTs inside one, and declaring a JDBC
 * table against Postgres. Factored out once these three jobs shared it verbatim, rather than
 * duplicated per job.
 */
final class SqlJobSupport {

  private SqlJobSupport() {}

  // Flink's job classloader (classloader.resolve-order: child-first) can load
  // org.postgresql.Driver under a different classloader instance than the one active when
  // java.sql.DriverManager's static initializer first ran (e.g. from Flink's own bootstrap
  // code) — the ServiceLoader-based auto-registration then never sees this jar's driver,
  // and DriverManager.getConnection fails with "No suitable driver found" even though the
  // driver is on the classpath. Forcing the class to load explicitly re-registers it under
  // the calling classloader, independent of that timing race. Not needed for the
  // Table API's own JDBC connector elsewhere in these jobs — only for this class's raw
  // DriverManager.getConnection call in deleteRunId.
  static {
    try {
      Class.forName("org.postgresql.Driver");
    } catch (ClassNotFoundException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * The execution environment every job in this variant runs in: batch, and verified to be
   * batch before a single table is declared.
   *
   * <p>The rule is "Flink in batch, with a run_id", and the spec asks for a test that fails
   * if the environment is streaming. Until now the jobs called {@code setRuntimeMode(BATCH)} and nothing checked
   * it: deleting that line would have left every test passing, because a bounded file source
   * finishes either way. What actually breaks in streaming mode is the *semantics* the whole
   * lane rests on (a run as a snapshot, the balancing equation, "these 50 and no others"),
   * and that breaks quietly.
   *
   * <p>So the mode is asserted here, at the one place every job goes through, and
   * {@code SqlJobSupportTest} covers both directions — batch passes, streaming throws.
   */
  static StreamExecutionEnvironment batchEnvironment() {
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setRuntimeMode(RuntimeExecutionMode.BATCH);
    requireBatch(env);
    return env;
  }

  /** Throws unless {@code env} is configured for batch execution. See {@link #batchEnvironment}. */
  static void requireBatch(StreamExecutionEnvironment env) {
    RuntimeExecutionMode mode = env.getConfiguration().get(ExecutionOptions.RUNTIME_MODE);
    if (mode != RuntimeExecutionMode.BATCH) {
      throw new IllegalStateException(
          "the run requires batch execution; this environment is "
              + mode
              + ". A streaming run has no 'this run' to fail, so run_id, the balancing equation"
              + " and the settle loop all lose their meaning.");
    }
  }

  static String loadResource(Class<?> anchor, String resourcePath) {
    try (var in = anchor.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("resource not found on classpath: " + resourcePath);
      }
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  static void jdbcTable(
      StreamTableEnvironment tEnv, String tableName, String columnsDdl, String pgTable,
      String url, String user, String password) {
    tEnv.executeSql(
        "CREATE TABLE " + tableName + " (" + columnsDdl + ") WITH ("
            + " 'connector'='jdbc',"
            + " 'url'='" + url + "',"
            + " 'table-name'='" + pgTable + "',"
            + " 'username'='" + user + "',"
            + " 'password'='" + password + "'"
            + ")");
  }

  /**
   * The result of parsing a {@code .sql.template}: {@code sequential} statements (DDL,
   * {@code CREATE VIEW}) run one at a time, each its own {@code executeSql} call that does
   * NOT submit a Flink job; {@code batchedInserts} are every {@code INSERT INTO ...}
   * statement in the template, always run together as ONE {@link StatementSet} — which
   * submits exactly one Flink job, however many INSERTs it contains, whether or not the
   * template itself wrapped them in an explicit {@code EXECUTE STATEMENT SET BEGIN …
   * END;} block.
   *
   * <p>Package-private so {@code SqlJobSupportTest} can exercise the parsing/grouping logic
   * directly — no {@code StreamTableEnvironment}, no cluster — as the regression guard for the bug where
   * a template with 2+ INSERTs and no {@code STATEMENT SET} block used to
   * submit one Flink job PER INSERT via plain sequential {@code executeSql} calls, which
   * Flink's application-mode entrypoint (@code KubernetesApplicationClusterEntrypoint}, the
   * one the Flink Kubernetes Operator uses to run a {@code FlinkDeployment}) rejects
   * outright: {@code "Cannot have more than one execute() or executeAsync() call in a single
   * environment"}. Invisible to every local/IT test because they all call a job's {@code
   * main()} in-process (a {@code LocalStreamEnvironment}, which has no such guard) rather
   * than deploying it — so this bug reproduces ONLY under a real application-mode deploy, not
   * under any amount of in-process testing. Grouping every INSERT into a single {@link
   * StatementSet}, unconditionally, makes the bug class impossible to reintroduce: a future
   * template with any number of INSERTs and no {@code STATEMENT SET} block still submits
   * exactly one job.
   */
  record Plan(List<String> sequential, List<String> batchedInserts) {}

  /**
   * Substitutes {@code ${KEY}} placeholders, strips {@code --} comments, and splits into a
   * {@link Plan}. Pure string processing — no Flink types, no I/O — so it doubles as the
   * regression guard's entry point (see {@link Plan}'s javadoc).
   */
  static Plan planTemplate(String template, java.util.Map<String, String> params) {
    String substituted = template;
    for (var e : params.entrySet()) {
      substituted = substituted.replace("${" + e.getKey() + "}", e.getValue());
    }
    // Strips a `--` comment wherever it starts on a line (not only when the whole line is
    // one), so an inline trailing comment can't hide a `;` from splitStatements() — none of
    // this project's SQL puts "--" inside a string literal, so a plain indexOf is safe here.
    String noComments =
        substituted
            .lines()
            .map(l -> {
              int idx = l.indexOf("--");
              return idx >= 0 ? l.substring(0, idx) : l;
            })
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");

    int stmtSetIdx = noComments.indexOf("EXECUTE STATEMENT SET");
    // An explicit STATEMENT SET block only changes WHERE the INSERTs are found in the
    // text (inside BEGIN/END, after any CREATE VIEWs) — not how they are grouped for
    // execution, which is unconditional below. Absent the block, every statement in the
    // (single) region is scanned the same way the BEGIN/END contents would be.
    String sequentialRegion = stmtSetIdx < 0 ? noComments : noComments.substring(0, stmtSetIdx);
    String insertRegion =
        stmtSetIdx < 0
            ? noComments
            : noComments.substring(
                noComments.indexOf("BEGIN", stmtSetIdx) + "BEGIN".length(),
                noComments.lastIndexOf("END"));

    List<String> sequential = new ArrayList<>();
    List<String> batchedInserts = new ArrayList<>();
    for (String stmt : splitStatements(sequentialRegion)) {
      (isInsertStatement(stmt) ? batchedInserts : sequential).add(stmt);
    }
    if (stmtSetIdx >= 0) {
      // Everything inside BEGIN/END is an INSERT by construction of this project's
      // templates  — no CREATE VIEW belongs inside a STATEMENT SET block.
      batchedInserts.addAll(splitStatements(insertRegion));
    }
    return new Plan(sequential, batchedInserts);
  }

  /**
   * Runs a {@link Plan}: sequential statements one at a time, then every batched INSERT
   * together as one {@link StatementSet} — at most one Flink job submission, regardless of
   * how many INSERTs the template has.
   */
  static void executeTemplate(
      StreamTableEnvironment tEnv, String template, java.util.Map<String, String> params) {
    executeTemplates(tEnv, List.of(template), params);
  }

  /**
   * As above for several templates that together make up one stage — their sequential
   * statements run in the order given, and their INSERTs are pooled into ONE
   * {@link StatementSet}, so N templates still submit exactly one Flink job. ProduceJob uses
   * this to add the Kafka sink (35-produce-kafka.sql.template) beside the Postgres writes
   * (30-produce.sql.template) without turning the produce stage into two job submissions —
   * which application mode would reject outright.
   */
  static void executeTemplates(
      StreamTableEnvironment tEnv, List<String> templates, java.util.Map<String, String> params) {
    List<String> sequential = new ArrayList<>();
    List<String> inserts = new ArrayList<>();
    for (String template : templates) {
      Plan plan = planTemplate(template, params);
      sequential.addAll(plan.sequential());
      inserts.addAll(plan.batchedInserts());
    }
    for (String stmt : sequential) {
      awaitCompletion(tEnv.executeSql(stmt));
    }
    if (!inserts.isEmpty()) {
      StatementSet statementSet = tEnv.createStatementSet();
      for (String stmt : inserts) {
        statementSet.addInsertSql(stmt);
      }
      awaitCompletion(statementSet.execute());
    }
  }

  /**
   * The {@code 'properties.*'} options a Flink Kafka table needs beyond the bootstrap
   * servers, rendered as a SQL fragment (leading comma, or empty). Mirrors
   * {@link mig.common.KafkaClients#applySecurity} exactly — same protocol, same mechanism,
   * same callback handler — so the Table API sink and this repo's plain clients authenticate
   * to GCP Managed Kafka the same way. Empty for local
   * redpanda (PLAINTEXT), which is what keeps every local test unaffected.
   */
  static String kafkaSecurityOptions() {
    return kafkaSecurityOptions(mig.common.KafkaClients.securityProtocol());
  }

  /** As above for an explicit protocol, so the non-PLAINTEXT branch is testable without env. */
  static String kafkaSecurityOptions(String protocol) {
    if ("PLAINTEXT".equals(protocol)) {
      return "";
    }
    return ","
        + " 'properties.security.protocol' = 'SASL_SSL',"
        + " 'properties.sasl.mechanism' = 'OAUTHBEARER',"
        + " 'properties.sasl.login.callback.handler.class' = 'mig.common.GcpTokenOauthCallbackHandler',"
        + " 'properties.sasl.jaas.config' ="
        + " 'org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;'";
  }

  /** Blocks until the submitted job (if any) finishes — see the comment above its only caller. */
  private static void awaitCompletion(TableResult result) {
    try {
      result.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
    }
  }

  /**
   * True for an {@code INSERT INTO ...} statement — the only kind that submits a Flink
   * job when run via plain {@code executeSql}. {@code CREATE VIEW}/
   * {@code CREATE TABLE} do not. Case-insensitive and tolerant of leading whitespace,
   * matching how {@link #splitStatements} hands statements over (stripped, but not
   * otherwise normalised).
   */
  private static boolean isInsertStatement(String stmt) {
    return stmt.stripLeading().regionMatches(true, 0, "INSERT", 0, "INSERT".length());
  }

  /**
   * Splits SQL on statement-terminating semicolons — but NOT on a semicolon inside a
   * single-quoted string literal.
   *
   * <p>This used to be {@code sql.split(";")}, and that was a real bug with a real cause: the
   * Kafka sink's SASL options include
   * {@code 'properties.sasl.jaas.config' = '...OAuthBearerLoginModule required;'}, whose
   * value ENDS in a semicolon (it is JAAS syntax). A plain split cut the literal in half and
   * Flink reported {@code SQL parse failed. Encountered "\'" at line 11, column 256} — a
   * message that says nothing about splitting. It was invisible to every local test because
   * {@code KAFKA_SECURITY_PROTOCOL} is unset locally, so the SASL fragment is empty and no
   * template ever contained a quoted semicolon. It surfaced on the first real Managed Kafka
   * deploy.
   *
   * <p>A doubled quote ({@code ''}) is SQL's escape for a literal quote; toggling on each one
   * handles it correctly without special-casing, since two toggles cancel out.
   */
  static List<String> splitStatements(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inString = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'') {
        inString = !inString;
      }
      if (c == ';' && !inString) {
        addIfNotBlank(out, current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    addIfNotBlank(out, current.toString());
    return out;
  }

  private static void addIfNotBlank(List<String> out, String statement) {
    String trimmed = statement.strip();
    if (!trimmed.isEmpty()) {
      out.add(trimmed);
    }
  }

  /**
   * Deletes any existing rows for {@code runId} from {@code table} before the job writes —
   * plain JDBC, not a Flink statement, run before the environment/tables are even set up.
   *
   * <p>: none of the jobs cleaned a run_id's prior rows before writing,
   * which is invisible as long as every test runs a job exactly once per run_id, but turns
   * into silent duplication the moment a job is retried — which on Cloud Composer a
   * {@code KubernetesPodOperator} does routinely ({@code retries} on task failure). The
   * vendored Python original does exactly this ({@code DELETE FROM ... WHERE run_id = ?})
   * before every stage writes; ported here rather than rediscovered once retries surface it.
   */
  static void deleteRunId(String url, String user, String password, String table, String runIdColumn, String runId) {
    deleteRunId(url, user, password, table, runIdColumn, runId, "");
  }

  /**
   * As above, with an extra raw SQL condition ANDed onto the WHERE clause — used by
   * ProduceJob, which appends map/schema-stage rows to {@code reject_log}/{@code
   * record_lineage} rather than owning the whole table for the run (IntakeJob's
   * parse-stage rows for the same run_id must survive). Mirrors the vendored Python
   * original's {@code DELETE ... WHERE run_id = ? AND stage IN ('map', 'schema')}.
   */
  static void deleteRunId(
      String url, String user, String password, String table, String runIdColumn, String runId,
      String extraCondition) {
    String sql =
        "DELETE FROM " + table + " WHERE " + runIdColumn + " = ?"
            + (extraCondition.isEmpty() ? "" : " AND " + extraCondition);
    try (var conn = java.sql.DriverManager.getConnection(url, user, password);
        var ps = conn.prepareStatement(sql)) {
      ps.setString(1, runId);
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new RuntimeException("could not clear prior rows for run_id=" + runId + " from " + table, e);
    }
  }
}
