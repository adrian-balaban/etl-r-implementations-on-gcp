package mig.recon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The run report — JSON and HTML — that  lists as part of what {@code recon}
 * owns ("raportul JSON + HTML (Html.java)") and that the port left behind: until now recon
 * printed to stdout and nothing else, so a finished run left no artefact anyone could open.
 *
 * <p>Deliberately smaller than the vendored {@code Html.java} it stands in for. That one
 * rendered a whole operations console; this renders the four things a migration run is
 * actually judged on — did the equation close, did every document settle, what was rejected
 * and why, and which keys (if any) are unaccounted for. Anything beyond that would be
 * inventing requirements rather than porting them.
 *
 * <p>No template engine and no CSS framework: one string, inline styles, opens from a file://
 * URL with nothing installed. A report that needs a build step to read is not a report you
 * reach for at 2am.
 */
final class Report {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Report() {}

  /**
   * Writes {@code recon-<runId>.json} and {@code recon-<runId>.html} into {@code outDir}.
   * Failure to write is logged, never fatal: the report is a record of the verdict, not the
   * verdict itself, and losing it must not turn a passing run into a failing one.
   */
  static void write(
      Path outDir,
      String runId,
      ReconService.BalanceResult balance,
      ReconService.BatchResult batches,
      java.util.List<ReconService.StageCheck> stages,
      long targetRows,
      long confirmedRows,
      Set<String> unconfirmedKeys,
      boolean settleChecked,
      boolean ok) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("runId", runId);
    json.put("ok", ok);
    json.put(
        "balance",
        Map.of(
            "srcRead", balance.srcRead(),
            "written", balance.written(),
            "rejected", balance.rejected(),
            "balanced", balance.balanced()));
    json.put(
        "batches",
        Map.of(
            "count", batches.batches(),
            "size", 200,
            "lastBatchSize", batches.lastBatchSize(),
            "consistent", batches.consistent()));
    json.put(
        "stages",
        stages.stream()
            .map(c -> Map.of("stage", c.stage(), "equation", c.equation(), "lhs", c.lhs(), "rhs", c.rhs(), "holds", c.holds()))
            .toList());
    json.put(
        "settle",
        settleChecked
            ? Map.of(
                "checked", true,
                "targetRows", targetRows,
                "confirmed", confirmedRows,
                "unconfirmed", new TreeSet<>(unconfirmedKeys))
            : Map.of("checked", false));

    try {
      Files.createDirectories(outDir);
      // The run id reaches a FILE NAME here, and it comes from a bucket path or an environment
      // variable: one containing "/" or ".." would be written somewhere else entirely (found when a
      // test used an id with a slash and the report was silently not written at all). Sanitise the
      // name only; the report body still shows the real id, HTML-escaped.
      String fileStem = fileSafe(runId);
      Files.writeString(
          outDir.resolve("recon-" + fileStem + ".json"),
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json));
      Files.writeString(outDir.resolve("recon-" + fileStem + ".html"), html(runId, json, ok));
      System.out.println("recon: report written to " + outDir.toAbsolutePath());
    } catch (Exception e) {
      System.err.println("recon: could not write report (" + e.getMessage() + ") — verdict unaffected");
    }
  }

  private static String html(String runId, Map<String, Object> json, boolean ok) {
    @SuppressWarnings("unchecked")
    Map<String, Object> balance = (Map<String, Object>) json.get("balance");
    @SuppressWarnings("unchecked")
    Map<String, Object> batches = (Map<String, Object>) json.get("batches");
    @SuppressWarnings("unchecked")
    Map<String, Object> settle = (Map<String, Object>) json.get("settle");

    String verdict = ok ? "PASSED" : "FAILED";
    String colour = ok ? "#1a7f37" : "#b3261e";

    StringBuilder sb = new StringBuilder();
    sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        .append("<title>recon ").append(esc(runId)).append("</title>")
        .append("<style>body{font:14px/1.5 system-ui,sans-serif;margin:2rem;max-width:52rem}")
        .append("h1{font-size:1.4rem;margin:0 0 .25rem}.v{color:").append(colour)
        .append(";font-weight:600}table{border-collapse:collapse;margin:1rem 0;width:100%}")
        .append("th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd}")
        .append("th{font-weight:600;background:#f6f6f6}code{background:#f0f0f0;padding:0 .2rem}")
        .append("</style></head><body>")
        .append("<h1>Reconciliation — <code>").append(esc(runId)).append("</code></h1>")
        .append("<p class=\"v\">").append(verdict).append("</p>");

    sb.append("<table><tr><th>Balancing equation</th><th></th></tr>")
        .append(row("src_read", balance.get("srcRead")))
        .append(row("written", balance.get("written")))
        .append(row("rejected", balance.get("rejected")))
        .append(row("balanced", balance.get("balanced")))
        .append("</table>");

    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> stageRows = (java.util.List<Map<String, Object>>) json.get("stages");
    sb.append("<table><tr><th>Per-stage equations </th><th></th></tr>");
    if (stageRows.isEmpty()) {
      sb.append("<tr><td colspan=\"2\">not applicable — single-pass variant, no intermediate tables</td></tr>");
    }
    for (Map<String, Object> row : stageRows) {
      sb.append(row(row.get("stage") + ": " + row.get("equation"), row.get("lhs") + " vs " + row.get("rhs") + (Boolean.TRUE.equals(row.get("holds")) ? "  ok" : "  BROKEN")));
    }
    sb.append("</table>");

    sb.append("<table><tr><th>Batches of 200 </th><th></th></tr>")
        .append(row("count", batches.get("count")))
        .append(row("last batch size", batches.get("lastBatchSize")))
        .append(row("consistent", batches.get("consistent")))
        .append("</table>");

    sb.append("<table><tr><th>Settle loop </th><th></th></tr>");
    if (Boolean.TRUE.equals(settle.get("checked"))) {
      sb.append(row("target rows", settle.get("targetRows")))
          .append(row("confirmed", settle.get("confirmed")))
          .append(row("unconfirmed", settle.get("unconfirmed")));
    } else {
      sb.append("<tr><td colspan=\"2\">not checked — no broker configured</td></tr>");
    }
    sb.append("</table>");

    return sb.append("</body></html>").toString();
  }

  /** Keep letters, digits, dot, underscore and hyphen; everything else (notably '/') becomes '_'. */
  static String fileSafe(String runId) {
    return runId.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String row(String label, Object value) {
    return "<tr><td>" + esc(label) + "</td><td><code>" + esc(String.valueOf(value)) + "</code></td></tr>";
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
