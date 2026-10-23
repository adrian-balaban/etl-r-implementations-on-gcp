package mig.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup check (spec, "Intake"): every TDS field must exist as a column of the source table. A missing
 * column fails the run before any row is read; it is never a per-record reject. Extra columns are fine.
 */
final class SourceColumns {

  private static final Pattern FIELD = Pattern.compile("^\\s*field\\s+(\\S+)");

  private SourceColumns() {}

  /** The {@code field NAME} entries of a TDS definition, lower-cased, in declaration order. */
  static List<String> tdsFields(Path tdsDef) throws IOException {
    List<String> fields = new ArrayList<>();
    for (String line : Files.readAllLines(tdsDef)) {
      Matcher m = FIELD.matcher(line);
      if (m.find()) {
        fields.add(m.group(1).toLowerCase(Locale.ROOT));
      }
    }
    return fields;
  }

  static List<String> missing(List<String> expected, Collection<String> actual) {
    List<String> out = new ArrayList<>();
    for (String column : expected) {
      if (!actual.contains(column)) {
        out.add(column);
      }
    }
    return out;
  }

  static Set<String> actual(Connection conn, String schema, String table) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
    }
    return columns;
  }

  /** Throws {@link IllegalStateException} naming every missing column. */
  static void require(String url, String user, String password, String schemaDotTable, Path tdsDef)
      throws IOException, SQLException {
    String[] parts = schemaDotTable.split("\\.", 2);
    try (Connection conn = DriverManager.getConnection(url, user, password)) {
      List<String> missing = missing(tdsFields(tdsDef), actual(conn, parts[0], parts[1]));
      if (!missing.isEmpty()) {
        throw new IllegalStateException(
            "source table " + schemaDotTable + " is missing TDS columns " + missing + " (" + tdsDef.getFileName() + ")");
      }
    }
  }
}
