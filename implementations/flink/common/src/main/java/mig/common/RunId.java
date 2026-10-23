package mig.common;

import java.util.regex.Pattern;

/**
 * The single definition of a valid run id, enforced wherever one enters the system.
 *
 * <p>A run_id is mandatory on every batch write. It used to be checked
 * only for "not blank", in one place (RecordRouter) — and it is used in four other ways that all
 * assume far more than that:
 *
 * <ul>
 *   <li>as a <b>file path</b> ({@code work/<run_id>/...} in gate): {@code ../../x} wrote the
 *       extracted file outside the work directory;
 *   <li>as a <b>SQL string literal</b> ({@code '${RUN_ID}'} in the job templates): an id containing a
 *       quote breaks the statement, and is an injection vector;
 *   <li>as a <b>file name</b> (recon's report): a {@code /} silently lost the report;
 *   <li>as a <b>Kubernetes resource name</b> ({@code flink-produce-<run_id>}): must be a DNS-1123 label.
 * </ul>
 *
 * <p>The strictest of those is the last, and it is a strict subset of everything safe in the other
 * three — so that is the rule: lowercase letters, digits and {@code -}, starting and ending
 * alphanumeric, at most {@value #MAX_LENGTH} characters ({@code flink-produce-} is 14, the label limit
 * 63). {@code gcp/orchestrator/run.sh} carries the same regular expression; {@code
 * OrchestratorPathContractTest} fails if the two ever differ.
 */
public final class RunId {

  /** Also written verbatim in gcp/orchestrator/run.sh — keep them identical (a test enforces it). */
  public static final String PATTERN_TEXT = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$";

  public static final int MAX_LENGTH = 49;

  private static final Pattern PATTERN = Pattern.compile(PATTERN_TEXT);

  private RunId() {}

  /** Returns {@code runId} if valid, otherwise throws with a message that says what is wrong. */
  public static String require(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("run_id is mandatory and must not be blank");
    }
    if (runId.length() > MAX_LENGTH || !PATTERN.matcher(runId).matches()) {
      throw new IllegalArgumentException(
          "run_id '" + shown(runId) + "' is not valid: use lowercase letters, digits and '-' only, starting and"
              + " ending with a letter or digit, at most " + MAX_LENGTH + " characters (it becomes a file path, a SQL"
              + " literal, a report name and a Kubernetes resource name)");
    }
    return runId;
  }

  public static boolean isValid(String runId) {
    try {
      require(runId);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Cap and neutralise what is echoed back, so an error message cannot itself carry the hostile input. */
  private static String shown(String runId) {
    String s = runId.length() > 60 ? runId.substring(0, 60) + "…" : runId;
    return s.replaceAll("[\\p{Cntrl}]", "?");
  }
}
