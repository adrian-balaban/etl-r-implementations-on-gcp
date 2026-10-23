package mig.common;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import mig.common.Doors.Reason;
import mig.common.Doors.RecordError;
import mig.common.Doors.Stage;
import mig.common.Tds.Definition;
import mig.common.Tds.Field;
import mig.common.Tds.Record;
import mig.common.Tds.RecordParser;

/**
 * The config-driven transformation engine — ported from {@code pipelines/common/mapping.py}.
 * The SRC-&gt;TARGET mapping is data, not code: a mapping YAML plus two TDS definitions.
 */
public final class Mapping {

  public record Rule(
      String target,
      String source,
      String transform,
      Map<String, String> values,
      String constValue,
      String sep,
      List<String> sources,
      int scale) {}

  public record Config(
      String project,
      Definition sourceTds,
      Definition targetTds,
      Path targetSchemaPath,
      String sourceRecord,
      String sourceLayout,
      String targetRecord,
      List<String> accountKeyFields,
      List<Rule> rules,
      int batchSize,
      String curatedTable,
      Map<String, String> curatedAliases) {

    public Record srcRecord() {
      return sourceTds.record(sourceRecord);
    }

    public Record tgtRecord() {
      return targetTds.record(targetRecord);
    }
  }

  private Mapping() {}

  @SuppressWarnings("unchecked")
  public static Config load(Path path, Path root) {
    try {
      Map<String, Object> cfg = new Yaml().load(Files.newInputStream(path));
      Definition targetTds = Tds.load(root.resolve((String) cfg.get("target_tds")));
      Map<String, Object> targetSection = (Map<String, Object>) cfg.getOrDefault("target", Map.of());
      String targetRecordName =
          targetSection.get("record") != null
              ? (String) targetSection.get("record")
              : targetTds.records().keySet().iterator().next();

      List<Rule> rules = new ArrayList<>();
      List<Map<String, Object>> rawMappings =
          (List<Map<String, Object>>) cfg.getOrDefault("mappings", List.of());
      for (Map<String, Object> raw : rawMappings) {
        String name = (String) raw.get("target");
        String transform = (String) raw.getOrDefault("transform", "trim");
        int scale;
        try {
          Field f = targetTds.record(targetRecordName).field(name);
          scale = f.scale() > 0 ? f.scale() : 2;
        } catch (RuntimeException e) {
          scale = 2;
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (raw.get("values") instanceof Map<?, ?> vm) {
          vm.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
        }
        rules.add(
            new Rule(
                name,
                (String) raw.get("from"),
                transform,
                values,
                raw.get("value") == null ? null : String.valueOf(raw.get("value")),
                (String) raw.getOrDefault("sep", ""),
                raw.get("from_fields") == null ? List.of() : (List<String>) raw.get("from_fields"),
                scale));
      }

      Map<String, Object> curated = (Map<String, Object>) cfg.getOrDefault("curated", Map.of());
      Map<String, String> aliases = new LinkedHashMap<>();
      if (curated.get("aliases") instanceof Map<?, ?> am) {
        am.forEach((k, v) -> aliases.put(String.valueOf(k), String.valueOf(v)));
      }

      Map<String, Object> source = (Map<String, Object>) cfg.get("source");
      Map<String, Object> batch = (Map<String, Object>) cfg.getOrDefault("batch", Map.of());

      return new Config(
          (String) cfg.get("project"),
          Tds.load(root.resolve((String) cfg.get("source_tds"))),
          targetTds,
          cfg.get("target_schema") != null ? root.resolve((String) cfg.get("target_schema")) : null,
          (String) source.get("record"),
          (String) source.getOrDefault("layout", "csv"),
          targetRecordName,
          (List<String>) cfg.getOrDefault("account_key", List.of()),
          List.copyOf(rules),
          batch.get("size") != null ? ((Number) batch.get("size")).intValue() : 200,
          (String) curated.getOrDefault("table", ""),
          Map.copyOf(aliases));
    } catch (IOException e) {
      throw new RuntimeException("cannot load mapping " + path, e);
    }
  }

  /** Write value into doc at a $.a.b path, creating intermediate objects. */
  @SuppressWarnings("unchecked")
  public static void setPath(Map<String, Object> doc, String path, Object value) {
    String[] parts = path.substring(1).split("\\.");
    List<String> nonEmpty = new ArrayList<>();
    for (String p : parts) {
      if (!p.isEmpty()) {
        nonEmpty.add(p);
      }
    }
    Map<String, Object> cur = doc;
    for (int i = 0; i < nonEmpty.size() - 1; i++) {
      Object nxt = cur.get(nonEmpty.get(i));
      if (!(nxt instanceof Map)) {
        nxt = new LinkedHashMap<String, Object>();
        cur.put(nonEmpty.get(i), nxt);
      }
      cur = (Map<String, Object>) nxt;
    }
    cur.put(nonEmpty.get(nonEmpty.size() - 1), value);
  }

  public static Object resolvePath(Map<String, Object> doc, String path) {
    Object cur = doc;
    for (String part : path.substring(1).split("\\.")) {
      if (part.isEmpty()) {
        continue;
      }
      if (!(cur instanceof Map<?, ?> m) || !m.containsKey(part)) {
        return null;
      }
      cur = ((Map<?, ?>) cur).get(part);
    }
    return cur;
  }

  /** Applies one Config to parsed SRC records: transform rules, then required-field check. */
  public static final class Engine {
    private final Config config;
    private final RecordParser parser;
    private final Map<String, String> targetPaths = new LinkedHashMap<>();
    private final List<String> requiredTargets = new ArrayList<>();

    public Engine(Config config) {
      this.config = config;
      this.parser = new RecordParser(config.srcRecord(), config.sourceLayout());
      for (Field f : config.tgtRecord().fields()) {
        if (f.path() != null) {
          targetPaths.put(f.name(), f.path());
        }
        if (f.required()) {
          requiredTargets.add(f.name());
        }
      }
    }

    public boolean isHeader(String raw) {
      return parser.isHeader(raw);
    }

    public Map<String, Object> parse(String raw) {
      return parser.parse(raw);
    }

    public String accountKey(Map<String, Object> fields) {
      List<String> parts = new ArrayList<>();
      for (String k : config.accountKeyFields()) {
        Object v = fields.get(k);
        parts.add(v == null ? "" : String.valueOf(v));
      }
      return Keys.accountKey(parts);
    }

    public String sourceKey(Map<String, Object> fields) {
      List<String> parts = new ArrayList<>();
      for (String k : config.accountKeyFields()) {
        Object v = fields.get(k);
        parts.add(v == null ? "" : String.valueOf(v));
      }
      return String.join("|", parts);
    }

    public Map<String, Object> mapRecord(Map<String, Object> fields, String runId) {
      Map<String, Object> doc = new LinkedHashMap<>();
      for (Rule rule : config.rules()) {
        Object rawValue;
        if (rule.transform().equals("concat") || rule.transform().equals("const")) {
          rawValue = null;
        } else {
          if (rule.source() == null) {
            throw new RecordError(Stage.MAP, Reason.MAP_TYPE_ERROR, rule.target() + ": no `from` field");
          }
          rawValue = fields.get(rule.source());
        }
        Object value = applyTransform(rule, rawValue, fields);
        String path = targetPaths.get(rule.target());
        if (path == null) {
          throw new RecordError(
              Stage.MAP, Reason.MAP_TYPE_ERROR, rule.target() + " is not defined in the TARGET TDS");
        }
        if (value == null && !requiredTargets.contains(rule.target())) {
          continue; // absent optional value: omitted, not emitted as null
        }
        setPath(doc, path, jsonSafe(value));
      }

      setPath(doc, targetPaths.get("runId"), runId);
      setPath(doc, targetPaths.get("sourceKey"), sourceKey(fields));
      setPath(doc, targetPaths.get("dedupKey"), accountKey(fields));

      checkRequired(doc);
      return doc;
    }

    private void checkRequired(Map<String, Object> doc) {
      for (String name : requiredTargets) {
        String path = targetPaths.get(name);
        if (path == null) {
          continue;
        }
        Object value = resolvePath(doc, path);
        if (value == null || "".equals(value)) {
          throw new RecordError(Stage.MAP, Reason.MAP_MISSING_REQUIRED, name);
        }
      }
    }

    private Object applyTransform(Rule rule, Object v, Map<String, Object> fields) {
      switch (rule.transform()) {
        case "passthrough":
          return v;
        case "trim":
          return v instanceof String s ? s.strip() : v;
        case "upper":
          return v instanceof String s2 ? s2.strip().toUpperCase() : v;
        case "lower":
          return v instanceof String s3 ? s3.strip().toLowerCase() : v;
        case "to_iso_date":
          if (v == null) {
            return null;
          }
          return v instanceof LocalDate d ? d.toString() : String.valueOf(v);
        case "to_decimal":
          if (v == null) {
            return null;
          }
          try {
            BigDecimal d = v instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(v));
            return d.setScale(rule.scale(), RoundingMode.UNNECESSARY).toPlainString();
          } catch (ArithmeticException | NumberFormatException e) {
            throw new RecordError(Stage.MAP, Reason.MAP_TYPE_ERROR, rule.target() + ": not numeric: " + v);
          }
        case "map_enum":
          if (v == null) {
            return null;
          }
          String key = String.valueOf(v).strip();
          if (!rule.values().containsKey(key)) {
            throw new RecordError(
                Stage.MAP,
                Reason.MAP_UNMAPPED_ENUM_VALUE,
                rule.target() + ": '" + key + "' not in " + sortedBracketList(rule.values().keySet()));
          }
          return rule.values().get(key);
        case "const":
          return rule.constValue();
        case "concat":
          List<String> parts = new ArrayList<>();
          for (String src : rule.sources()) {
            parts.add(String.valueOf(fields.get(src)));
          }
          return String.join(rule.sep(), parts);
        default:
          throw new RecordError(Stage.MAP, Reason.MAP_UNKNOWN_TRANSFORM, rule.transform());
      }
    }

    private static String sortedBracketList(java.util.Collection<String> values) {
      List<String> sorted = new ArrayList<>(values);
      java.util.Collections.sort(sorted);
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < sorted.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append('\'').append(sorted.get(i)).append('\'');
      }
      return sb.append(']').toString();
    }

    private static Object jsonSafe(Object v) {
      if (v instanceof BigDecimal bd) {
        return bd.toPlainString();
      }
      if (v instanceof LocalDate d) {
        return d.toString();
      }
      return v;
    }
  }
}
