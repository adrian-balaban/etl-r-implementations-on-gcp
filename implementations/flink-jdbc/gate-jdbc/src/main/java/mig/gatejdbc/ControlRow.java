package mig.gatejdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** One row of etlr_source.extract_control: the source's promise for a run. */
public record ControlRow(String runId, String tableName, long rowCount, BigDecimal contentDigest) {

  public static Optional<ControlRow> read(Connection conn, String runId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT table_name, row_count, content_digest FROM etlr_source.extract_control WHERE run_id = ?")) {
      ps.setString(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(new ControlRow(runId, rs.getString(1), rs.getLong(2), rs.getBigDecimal(3)));
      }
    }
  }
}
