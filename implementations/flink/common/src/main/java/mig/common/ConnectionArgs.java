package mig.common;

/**
 * Resolves a command-line argument that may name an environment variable instead of carrying a
 * secret.
 *
 * <p>The Flink jobs and recon take the database password as a positional argument. On GCP that
 * argument ended up in three places it should not have been: the FlinkDeployment spec, the
 * program arguments, and the JobManager's "Loading configuration property" log lines (the
 * password was read back out of that log during the first real run). An argument of the form
 * {@code env:NAME} defers to the environment, where a Kubernetes Secret can put it without it
 * ever appearing in a manifest. Anything else is returned unchanged, so every existing caller
 * — every local test, {@code make run-sql}, the compose stack — keeps working as it did.
 */
public final class ConnectionArgs {

  private static final String ENV_PREFIX = "env:";

  private ConnectionArgs() {}

  public static String resolve(String argument) {
    return resolve(argument, System::getenv);
  }

  /** As above, with the environment injected so the unset case is testable without mutating it. */
  static String resolve(String argument, java.util.function.Function<String, String> env) {
    if (argument == null || !argument.startsWith(ENV_PREFIX)) {
      return argument;
    }
    String name = argument.substring(ENV_PREFIX.length());
    String value = env.apply(name);
    if (value == null || value.isEmpty()) {
      // Fail loudly and name the variable — NOT the value, and not "null": a job that connects
      // with the literal string "env:JDBC_PASSWORD" fails later as an opaque authentication error
      // that points at the database, not at a missing Secret mount.
      throw new IllegalStateException(
          "argument '" + argument + "' refers to environment variable " + name
              + ", which is not set — is the pod's Secret mounted?");
    }
    return value;
  }
}
