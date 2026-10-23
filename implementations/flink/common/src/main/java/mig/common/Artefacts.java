package mig.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The cross-language naming surface: object naming and column names, loaded from
 * {@code contracts/artefacts.json} at runtime. One source for both the
 * Python original and this Java port, so a rename is never invisible to one side.
 */
public final class Artefacts {

  private final JsonNode root;

  public Artefacts(Path artefactsJsonPath) {
    try {
      this.root = new ObjectMapper().readTree(Files.readString(artefactsJsonPath));
    } catch (IOException e) {
      throw new RuntimeException("cannot load " + artefactsJsonPath, e);
    }
  }

  /** Ingest-metadata column names for the `src` side (e.g. `_run_id`, `_source_key`). */
  public String srcColumn(String logicalName) {
    return root.path("columns").path("src").path(logicalName).asText();
  }

  public String targetColumn(String logicalName) {
    return root.path("columns").path("target").path(logicalName).asText();
  }

  public Map<String, String> srcColumns() {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    root.path("columns").path("src").fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
    return out;
  }
}
