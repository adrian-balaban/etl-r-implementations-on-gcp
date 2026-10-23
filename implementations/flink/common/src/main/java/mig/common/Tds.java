package mig.common;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mig.common.Doors.Reason;
import mig.common.Doors.RecordError;
import mig.common.Doors.Stage;

/**
 * TDS — the layout definition format. Ported from {@code pipelines/common/tds.py}.
 *
 * <p>Every contract in this repo declares a {@code csv} layout — pipe-delimited, with an
 * optional header row that is recognised by content (not position) and skipped rather than
 * parsed. A {@code json} layout addresses every field by {@code path}; the two kinds are
 * never mixed within one record definition.
 */
public final class Tds {

  public record Field(
      String name,
      Integer offset,
      Integer length,
      Integer col,
      String path,
      String type,
      int scale,
      boolean required) {}

  public record Layout(String kind, Integer width, String delimiter, boolean header) {}

  public record Record(String name, Map<String, Layout> layouts, List<Field> fields) {
    public Field field(String name) {
      for (Field f : fields) {
        if (f.name().equals(name)) {
          return f;
        }
      }
      throw new NoSuchElementException("field " + name + " not defined in record " + this.name);
    }

    public List<String> fieldNames() {
      List<String> names = new ArrayList<>(fields.size());
      for (Field f : fields) {
        names.add(f.name());
      }
      return names;
    }
  }

  public static final class NoSuchElementException extends RuntimeException {
    public NoSuchElementException(String message) {
      super(message);
    }
  }

  public record Definition(Map<String, Record> records) {
    public Record record(String name) {
      Record r = records.get(name);
      if (r == null) {
        throw new NoSuchElementException(
            "record " + name + " not defined; known records: " + records.keySet());
      }
      return r;
    }
  }

  private Tds() {}

  // ─────────────────────────────────────────────────────────── definition parsing

  public static Definition load(Path path) {
    try {
      return parse(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("cannot read TDS definition " + path, e);
    }
  }

  public static Definition parse(String text) {
    Map<String, Record> records = new LinkedHashMap<>();
    String name = null;
    Map<String, Layout> layouts = new LinkedHashMap<>();
    List<Field> fields = new ArrayList<>();

    String[] lines = text.split("\n", -1);
    for (int lineno = 0; lineno < lines.length; lineno++) {
      String raw = lines[lineno];
      int hash = raw.indexOf('#');
      String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
      if (line.isEmpty()) {
        continue;
      }
      String[] tokens = line.split("\\s+");
      String head = tokens[0];
      String where = "line " + (lineno + 1);

      if (head.equals("record")) {
        if (name != null) {
          requireHomogeneous(name, layouts, fields);
          records.put(name, new Record(name, Map.copyOf(layouts), List.copyOf(fields)));
        }
        name = tokens[1];
        layouts = new LinkedHashMap<>();
        fields = new ArrayList<>();
      } else if (head.equals("layout")) {
        if (name == null) {
          throw new IllegalArgumentException(where + ": `layout` outside a record");
        }
        String kind = tokens[1];
        Map<String, String> attrs = kv(tokens, 2, where);
        layouts.put(
            kind,
            new Layout(
                kind,
                attrs.containsKey("width") ? Integer.valueOf(attrs.get("width")) : null,
                attrs.get("delimiter"),
                isTrue(attrs.getOrDefault("header", "false"))));
      } else if (head.equals("field")) {
        if (name == null) {
          throw new IllegalArgumentException(where + ": `field` outside a record");
        }
        String fname = tokens[1];
        Map<String, String> attrs = kv(tokens, 2, where);
        fields.add(
            new Field(
                fname,
                attrs.containsKey("offset") ? Integer.valueOf(attrs.get("offset")) : null,
                attrs.containsKey("len") ? Integer.valueOf(attrs.get("len")) : null,
                attrs.containsKey("col") ? Integer.valueOf(attrs.get("col")) : null,
                attrs.get("path"),
                attrs.getOrDefault("type", "string"),
                Integer.parseInt(attrs.getOrDefault("scale", "0")),
                isTrue(attrs.getOrDefault("required", "false"))));
      } else {
        throw new IllegalArgumentException(where + ": unknown directive " + head);
      }
    }

    if (name != null) {
      requireHomogeneous(name, layouts, fields);
      records.put(name, new Record(name, Map.copyOf(layouts), List.copyOf(fields)));
    }
    if (records.isEmpty()) {
      throw new IllegalArgumentException("TDS definition contains no records");
    }
    return new Definition(Map.copyOf(records));
  }

  private static boolean isTrue(String v) {
    return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
  }

  private static Map<String, String> kv(String[] tokens, int from, String where) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = from; i < tokens.length; i++) {
      String tok = tokens[i];
      int eq = tok.indexOf('=');
      if (eq < 0) {
        throw new IllegalArgumentException(where + ": expected key=value, got " + tok);
      }
      out.put(tok.substring(0, eq), tok.substring(eq + 1));
    }
    return out;
  }

  private static void requireHomogeneous(String name, Map<String, Layout> layouts, List<Field> fields) {
    if (fields.isEmpty()) {
      return;
    }
    boolean isJson = layouts.containsKey("json");
    if (isJson && layouts.size() > 1) {
      throw new IllegalArgumentException(
          "record " + name + " mixes a json layout with others; one definition is one format");
    }
    for (Field f : fields) {
      boolean hasDat = f.offset() != null || f.col() != null;
      boolean hasJson = f.path() != null;
      if (isJson && (hasDat || !hasJson)) {
        throw new IllegalArgumentException(
            "record " + name + " has a json layout, so field " + f.name()
                + " must be addressed by `path`");
      }
      if (!isJson && (hasJson || !hasDat)) {
        throw new IllegalArgumentException(
            "record " + name + " has a " + layouts.keySet() + " layout, so field " + f.name()
                + " must be addressed by offset/len or col");
      }
    }
  }

  // ───────────────────────────────────────────────────────────────── record parsing

  /** Parses one raw source line into a field map, per a record and a layout kind. */
  public static final class RecordParser {
    private final Record record;
    private final Layout layout;

    public RecordParser(Record record, String layoutKind) {
      if (!record.layouts().containsKey(layoutKind)) {
        throw new NoSuchElementException(
            "record " + record.name() + " has no " + layoutKind + " layout; available: "
                + record.layouts().keySet());
      }
      this.record = record;
      this.layout = record.layouts().get(layoutKind);
    }

    public boolean hasHeader() {
      return layout.header();
    }

    /**
     * The literal header line, rendered from the TDS field names and the layout's own
     * delimiter — for a caller (e.g. a SQL job's header filter) that needs the header as a
     * string to compare against, rather than {@link #isHeader}'s boolean test. Derived from
     * the contract, not duplicated as a literal.
     */
    public String headerLine() {
      if (!layout.header() || !layout.kind().equals("csv")) {
        throw new IllegalStateException(
            "record " + record.name() + " has no csv header to render");
      }
      String delim = layout.delimiter() != null ? layout.delimiter() : "|";
      return String.join(delim, record.fieldNames());
    }

    /** True when {@code raw} is the extract's header line, recognised by content. */
    public boolean isHeader(String raw) {
      if (!layout.header() || !layout.kind().equals("csv")) {
        return false;
      }
      String delim = layout.delimiter() != null ? layout.delimiter() : "|";
      String[] cols = stripTrailingNewline(raw).split(java.util.regex.Pattern.quote(delim), -1);
      List<String> names = record.fieldNames();
      if (cols.length < names.size()) {
        return false;
      }
      for (int i = 0; i < names.size(); i++) {
        if (!cols[i].strip().equals(names.get(i))) {
          return false;
        }
      }
      return true;
    }

    public Map<String, Object> parse(String rawIn) {
      String raw = stripTrailingNewline(rawIn);
      if (layout.kind().equals("json")) {
        return parseJson(raw);
      }
      return parseDat(raw);
    }

    private Map<String, Object> parseJson(String raw) {
      throw new UnsupportedOperationException("json layout not needed on the parse path yet");
    }

    private Map<String, Object> parseDat(String raw) {
      String[] columns = null;
      if (layout.kind().equals("fixed")) {
        int width = layout.width() != null ? layout.width() : 0;
        if (raw.length() < width) {
          throw new RecordError(
              Stage.PARSE,
              Reason.PARSE_SHORT_RECORD,
              "expected >= " + width + " bytes, got " + raw.length());
        }
      } else {
        String delim = layout.delimiter() != null ? layout.delimiter() : "|";
        columns = raw.split(java.util.regex.Pattern.quote(delim), -1);
      }

      Map<String, Object> out = new LinkedHashMap<>();
      for (Field f : record.fields()) {
        Object cell;
        if (layout.kind().equals("fixed")) {
          int off = f.offset();
          int len = f.length();
          cell = raw.substring(off, Math.min(off + len, raw.length()));
        } else {
          int col = f.col();
          if (columns == null || col >= columns.length) {
            throw new RecordError(
                Stage.PARSE,
                Reason.PARSE_BAD_COLUMN_COUNT,
                f.name() + " needs column " + col + ", row has " + (columns == null ? 0 : columns.length));
          }
          cell = columns[col];
        }
        Object value = coerce(f, cell);
        if (f.required() && (value == null || "".equals(value))) {
          throw new RecordError(Stage.PARSE, Reason.PARSE_MISSING_FIELD, f.name());
        }
        out.put(f.name(), value);
      }
      return out;
    }
  }

  private static String stripTrailingNewline(String s) {
    return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
  }

  private static Object coerce(Field field, Object raw) {
    if (field.type().equals("json")) {
      return raw;
    }
    if (raw instanceof String s) {
      raw = s.strip();
      if ("".equals(raw)) {
        return null;
      }
    } else if (raw == null) {
      return null;
    }

    switch (field.type()) {
      case "string":
        return String.valueOf(raw);
      case "int":
        try {
          String digits = String.valueOf(raw);
          String stripped = digits.replaceFirst("^0+(?=\\d)", "");
          return Integer.valueOf(stripped.isEmpty() ? "0" : stripped);
        } catch (NumberFormatException e) {
          throw new RecordError(Stage.PARSE, Reason.PARSE_BAD_NUMERIC, field.name() + "='" + raw + "'");
        }
      case "decimal":
        try {
          BigDecimal value = new BigDecimal(String.valueOf(raw));
          return field.scale() > 0 ? value.setScale(field.scale(), java.math.RoundingMode.UNNECESSARY) : value;
        } catch (ArithmeticException | NumberFormatException e) {
          throw new RecordError(Stage.PARSE, Reason.PARSE_BAD_NUMERIC, field.name() + "='" + raw + "'");
        }
      case "date":
        String text = String.valueOf(raw);
        try {
          if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
            return LocalDate.of(
                Integer.parseInt(text.substring(0, 4)),
                Integer.parseInt(text.substring(4, 6)),
                Integer.parseInt(text.substring(6, 8)));
          }
          return LocalDate.parse(text);
        } catch (java.time.DateTimeException e) {
          throw new RecordError(Stage.PARSE, Reason.PARSE_BAD_DATE, field.name() + "='" + raw + "'");
        }
      default:
        throw new IllegalArgumentException("unsupported TDS type " + field.type() + " on field " + field.name());
    }
  }
}
