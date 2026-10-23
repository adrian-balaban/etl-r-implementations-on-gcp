package mig.gatejdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import mig.common.ConnectionArgs;
import mig.common.RunId;

/**
 * The gate (spec "Gate"): refuse without a control row; in one REPEATABLE READ, read-only transaction,
 * fail when the table's count or content digest differs from the control row, or a non-blank trimmed
 * key repeats. Writes nothing. Exit codes: 0 passed (prints {@code src_read=N}), 2 usage, 3 refused, 4 failed.
 * {@code --discover} prints {@code run_id<TAB>ready_at} per line.
 */
public final class GateJdbc {

  public enum Outcome { PASSED, REFUSED, FAILED }

  public record Result(Outcome outcome, long srcRead, List<String> problems) {}

  private GateJdbc() {}

  public static Result check(Connection src, String runId) throws SQLException {
    src.setAutoCommit(false);
    src.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    src.setReadOnly(true);
    try {
      Optional<ControlRow> control = ControlRow.read(src, runId);
      if (control.isEmpty()) {
        return new Result(Outcome.REFUSED, 0, List.of("no extract_control row for run " + runId));
      }
      ControlRow promised = control.get();
      String table = CanonicalSql.requireTableName(promised.tableName());
      long count;
      BigDecimal digest;
      try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery(CanonicalSql.countAndDigest(table))) {
        rs.next();
        count = rs.getLong(1);
        digest = rs.getBigDecimal(2);
      }
      List<String> problems = new ArrayList<>();
      if (count != promised.rowCount()) {
        problems.add("row count: control row says " + promised.rowCount() + ", " + table + " has " + count);
      }
      if (digest.compareTo(promised.contentDigest()) != 0) {
        problems.add("content digest: control row says " + promised.contentDigest() + ", " + table + " has " + digest);
      }
      List<String> duplicates = new ArrayList<>();
      try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery(CanonicalSql.duplicateKeys(table))) {
        while (rs.next()) {
          duplicates.add(rs.getString(1));
        }
      }
      if (!duplicates.isEmpty()) {
        problems.add("duplicate keys after TRIM: " + duplicates);
      }
      return new Result(problems.isEmpty() ? Outcome.PASSED : Outcome.FAILED, count, problems);
    } finally {
      src.rollback();
    }
  }

  /** A run the source has vouched for, and when it declared it ready. */
  public record Ready(String runId, java.time.Instant readyAt) {}

  /** Runs with a control row, oldest first. The GCP orchestrator removes those already done. */
  public static List<Ready> discover(Connection src) throws SQLException {
    List<Ready> runs = new ArrayList<>();
    try (Statement st = src.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT run_id, ready_at FROM etlr_source.extract_control ORDER BY ready_at, run_id")) {
      while (rs.next()) {
        runs.add(new Ready(rs.getString(1), rs.getObject(2, java.time.OffsetDateTime.class).toInstant()));
      }
    }
    return runs;
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 4 && "--discover".equals(args[0])) {
      try (Connection c = DriverManager.getConnection(args[1], args[2], ConnectionArgs.resolve(args[3]))) {
        // run_id<TAB>ready_at (UTC, ISO-8601): the orchestrator's as-of date is the date of ready_at.
        discover(c).forEach(r -> System.out.println(r.runId() + "\t" + r.readyAt()));
      }
      return;
    }
    if (args.length != 4) {
      System.err.println("usage: GateJdbc <runId> <srcJdbcUrl> <srcUser> <srcPassword>\n"
          + "       GateJdbc --discover <srcJdbcUrl> <srcUser> <srcPassword>");
      System.exit(2);
    }
    String runId = RunId.require(args[0]);
    Result result;
    try (Connection c = DriverManager.getConnection(args[1], args[2], ConnectionArgs.resolve(args[3]))) {
      result = check(c, runId);
    }
    switch (result.outcome()) {
      case PASSED -> System.out.println("src_read=" + result.srcRead());
      case REFUSED -> {
        System.err.println("gate-jdbc: extraction refused for run " + runId + ": " + String.join("; ", result.problems()));
        System.exit(3);
      }
      case FAILED -> {
        System.err.println("gate-jdbc: extraction failed for run " + runId + ": " + String.join("; ", result.problems()));
        System.exit(4);
      }
    }
  }
}
