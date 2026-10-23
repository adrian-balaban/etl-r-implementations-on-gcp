package mig.gatejdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import mig.common.ConnectionArgs;
import mig.common.RunId;

/**
 * recon-jdbc (spec "Snapshot consistency"): what intake measured (etlr_recon.source_read, lane DB) must
 * equal what the source promised (extract_control). Runs before recon. Exit codes: 0 ok, 2 usage, 4 failed.
 */
public final class ReconJdbc {

  public record Result(boolean ok, List<String> problems) {}

  private ReconJdbc() {}

  public static Result compare(Connection src, Connection lane, String runId) throws SQLException {
    Optional<ControlRow> control = ControlRow.read(src, runId);
    if (control.isEmpty()) {
      return new Result(false, List.of("no extract_control row for run " + runId));
    }
    List<long[]> counts = new ArrayList<>();
    List<BigDecimal> digests = new ArrayList<>();
    try (PreparedStatement ps = lane.prepareStatement(
        "SELECT row_count, content_digest FROM etlr_recon.source_read WHERE run_id = ?")) {
      ps.setString(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          counts.add(new long[] {rs.getLong(1)});
          digests.add(rs.getBigDecimal(2));
        }
      }
    }
    if (counts.size() != 1) {
      return new Result(false, List.of("expected one source_read row for run " + runId + ", found " + counts.size()));
    }
    ControlRow promised = control.get();
    List<String> problems = new ArrayList<>();
    if (counts.get(0)[0] != promised.rowCount()) {
      problems.add("row count: control row says " + promised.rowCount() + ", intake read " + counts.get(0)[0]);
    }
    if (digests.get(0).compareTo(promised.contentDigest()) != 0) {
      problems.add("content digest: control row says " + promised.contentDigest() + ", intake read " + digests.get(0));
    }
    return new Result(problems.isEmpty(), problems);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 7) {
      System.err.println("usage: ReconJdbc <runId> <srcJdbcUrl> <srcUser> <srcPassword> <laneJdbcUrl> <laneUser> <lanePassword>");
      System.exit(2);
    }
    String runId = RunId.require(args[0]);
    Result result;
    try (Connection src = DriverManager.getConnection(args[1], args[2], ConnectionArgs.resolve(args[3]));
        Connection lane = DriverManager.getConnection(args[4], args[5], ConnectionArgs.resolve(args[6]))) {
      result = compare(src, lane, runId);
    }
    if (result.ok()) {
      System.out.println("recon-jdbc: run " + runId + " read exactly what the source vouched for");
      return;
    }
    System.err.println("RECON-JDBC FAILED for run " + runId + ": " + String.join("; ", result.problems()));
    System.exit(4);
  }
}
