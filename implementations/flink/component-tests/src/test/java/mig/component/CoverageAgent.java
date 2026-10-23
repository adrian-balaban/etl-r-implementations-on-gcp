package mig.component;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Forwards the JaCoCo agent into a subprocess the tests spawn.
 *
 * <p>{@code SettleLoopIT}/{@code SqlLaneSettleIT} run {@code recon} and the target-system mock as REAL
 * subprocesses (recon calls {@code System.exit}; the mock is a long-lived server), and a subprocess does
 * not inherit the test JVM's {@code -javaagent}. Those two modules therefore reported 19% and 34% line
 * coverage while being exercised end to end against a real broker on every run — a measurement gap, not a
 * test gap, and it left the repo-wide 65% floor met by 0.1 points. JaCoCo's agent appends to a shared
 * {@code destfile}, so passing the same agent argument through is all it takes.
 */
final class CoverageAgent {

  private CoverageAgent() {}

  /** The {@code -javaagent:...jacoco...} argument(s) of this JVM, or nothing when not running under JaCoCo. */
  static List<String> jvmArgs() {
    return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco"))
        .toList();
  }
}
