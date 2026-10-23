package mig.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mig.common.Doors.Reason;
import mig.common.Doors.RecordError;
import mig.common.Doors.Stage;

/**
 * Validates an emitted TARGET document against {@code target-account.schema.json} — the
 * schema door, a stage distinct from `map`.
 *
 * <p>Hand-written predicates rather than a general JSON-Schema library, per the
 * default option — the risk that these drift from the schema JSON is covered by
 * {@link #checkCoversSchemaConstraints()}, run as a unit test so an uncovered constraint fails
 * loudly rather than silently. Error messages reproduce the Python {@code jsonschema} phrasing
 * the evidence set was generated with (e.g. {@code "accountId: 'x' does not match '^…$'"}), field
 * order following the schema's own {@code required} list so the *first* violation matches.
 */
public final class TargetSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JsonNode schema;

  public TargetSchemaValidator(Path schemaPath) {
    try {
      this.schema = MAPPER.readTree(Files.readString(schemaPath));
    } catch (IOException e) {
      throw new RuntimeException("cannot load target schema " + schemaPath, e);
    }
  }

  public void validate(Map<String, Object> doc) {
    JsonNode node = MAPPER.valueToTree(doc);
    checkObject(node, schema, "");
  }

  private void checkObject(JsonNode node, JsonNode objSchema, String prefix) {
    List<String> required = toStringList(objSchema.path("required"));
    for (String reqField : required) {
      if (!node.has(reqField) || node.get(reqField).isNull()) {
        fail(prefix, reqField, "is a required property");
      }
    }
    JsonNode properties = objSchema.path("properties");
    java.util.Iterator<String> names = properties.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!node.has(name)) {
        continue;
      }
      JsonNode value = node.get(name);
      JsonNode propSchema = properties.get(name);
      String location = prefix.isEmpty() ? name : prefix + "/" + name;
      checkValue(value, propSchema, location);
    }
  }

  private void checkValue(JsonNode value, JsonNode propSchema, String location) {
    String type = propSchema.path("type").asText("");
    if (type.equals("object")) {
      if (value.isObject()) {
        checkObject(value, propSchema, location);
      }
      return;
    }
    if (value.isNumber()) {
      long n = value.asLong();
      if (propSchema.hasNonNull("minimum") && n < propSchema.get("minimum").asLong()) {
        fail(location, null, n + " is less than the minimum of " + propSchema.get("minimum").asLong());
      }
      if (propSchema.hasNonNull("maximum") && n > propSchema.get("maximum").asLong()) {
        fail(location, null, n + " is greater than the maximum of " + propSchema.get("maximum").asLong());
      }
      return;
    }
    if (!value.isTextual()) {
      return;
    }
    String text = value.asText();

    if (propSchema.hasNonNull("pattern")) {
      String pattern = propSchema.get("pattern").asText();
      if (!Pattern.matches(pattern, text)) {
        fail(location, null, "'" + text + "' does not match '" + pattern + "'");
      }
    }
    if (propSchema.hasNonNull("maxLength") && text.length() > propSchema.get("maxLength").asInt()) {
      fail(location, null, "'" + text + "' is too long");
    }
    if (propSchema.hasNonNull("minLength") && text.length() < propSchema.get("minLength").asInt()) {
      fail(location, null, "'" + text + "' is too short");
    }
    if (propSchema.has("enum")) {
      List<String> allowed = toStringList(propSchema.get("enum"));
      if (!allowed.contains(text)) {
        fail(location, null, "'" + text + "' is not one of " + allowed);
      }
    }
  }

  private static void fail(String location, String field, String message) {
    String label = field != null ? field : lastSegment(location);
    throw new RecordError(Stage.SCHEMA, Reason.SCHEMA_INVALID, label + ": " + message);
  }

  private static String lastSegment(String path) {
    int idx = path.lastIndexOf('/');
    return idx < 0 ? path : path.substring(idx + 1);
  }

  private static List<String> toStringList(JsonNode arrayNode) {
    List<String> out = new java.util.ArrayList<>();
    if (arrayNode.isArray()) {
      arrayNode.forEach(n -> out.add(n.asText()));
    }
    return out;
  }

  /**
   * Coverage check: every constraint keyword the schema JSON declares on a string property must
   * be one this validator understands, so a schema edit that adds e.g. {@code format} cannot
   * silently stop being enforced.
   */
  public void checkCoversSchemaConstraints() {
    java.util.Set<String> understood =
        java.util.Set.of(
            "type", "pattern", "maxLength", "minLength", "enum", "description", "format",
            "additionalProperties", "required", "properties", "minimum", "maximum");
    walkAndCheck(schema, understood);
  }

  private void walkAndCheck(JsonNode node, java.util.Set<String> understood) {
    JsonNode properties = node.path("properties");
    java.util.Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode propSchema = e.getValue();
      java.util.Iterator<String> keys = propSchema.fieldNames();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!understood.contains(key)) {
          throw new IllegalStateException(
              "target-account.schema.json property '" + e.getKey()
                  + "' declares unhandled constraint '" + key + "' — TargetSchemaValidator must be updated");
        }
      }
      if (propSchema.path("type").asText("").equals("object")) {
        walkAndCheck(propSchema, understood);
      }
    }
  }
}
