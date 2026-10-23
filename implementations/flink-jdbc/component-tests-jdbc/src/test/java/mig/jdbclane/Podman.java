package mig.jdbclane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Throwaway Postgres containers for the flink-jdbc ITs: a Source System database and a lane database. */
public final class Podman {

  public static final Path ROOT = Path.of("..").toAbsolutePath().normalize(); // implementations/flink-jdbc
  public static final Path SPEC = ROOT.resolve("../../spec").normalize();
  public static final String READER = "etlr_reader";
  public static final String READER_PASSWORD = "reader";
  public static final String READY_AT = "2026-09-07T00:00:00Z";

  private Podman() {}

  public static boolean available() {
    try {
      return new ProcessBuilder("bash", "-c", "command -v podman").start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  public static int run(String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  public static String jdbcUrl(int port, String db) {
    return "jdbc:postgresql://localhost:" + port + "/" + db;
  }

  public static void startPostgres(String container, int port, String db) throws Exception {
    run("podman", "rm", "-f", container);
    run("podman", "run", "--rm", "-d", "--name", container,
        "-e", "POSTGRES_USER=mig", "-e", "POSTGRES_PASSWORD=mig", "-e", "POSTGRES_DB=" + db,
        "-p", port + ":5432", "docker.io/library/postgres:16");
    boolean ready = false;
    for (int i = 0; i < 30 && !ready; i++) {
      ready = run("podman", "exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "mig", "-d", db) == 0;
      if (!ready) {
        Thread.sleep(1000);
      }
    }
    assertThat(ready).as(container + " became ready").isTrue();
  }

  public static void psqlFile(String container, String db, Path file) throws Exception {
    run("podman", "cp", file.toString(), container + ":/tmp/in.sql");
    int rc = run("podman", "exec", container, "psql", "-U", "mig", "-d", db, "-v", "ON_ERROR_STOP=1", "-q", "-f", "/tmp/in.sql");
    assertThat(rc).as("psql -f " + file.getFileName() + " in " + container).isZero();
  }

  public static void psql(String container, String db, String sql) throws Exception {
    Path f = Files.createTempFile("flink-jdbc-it", ".sql");
    Files.writeString(f, sql);
    psqlFile(container, db, f);
  }

  /** The Source System: database {@code source}, schema + read-only role from sql/source-init.sql. */
  public static void startSource(String container, int port) throws Exception {
    startPostgres(container, port, "source");
    psqlFile(container, "source", ROOT.resolve("sql/source-init.sql"));
  }

  /** The lane: database {@code mig}, the Flink lane's schema plus sql/postgres-init-jdbc.sql. */
  public static void startLane(String container, int port) throws Exception {
    startPostgres(container, port, "mig");
    psqlFile(container, "mig", ROOT.resolve("../flink/sql/postgres-init.sql").normalize());
    psqlFile(container, "mig", ROOT.resolve("sql/postgres-init-jdbc.sql"));
  }

  /** Reloads etlr_source.account from spec/fixtures/ACCOUNT.src and vouches for it under {@code runId}. */
  public static void loadFixture(String container, String runId) throws Exception {
    Process p = new ProcessBuilder(
            "python3", "-I", SPEC.resolve("harness/load_db.py").toString(),
            SPEC.resolve("fixtures/ACCOUNT.src").toString(), "--run-id", runId, "--ready-at", READY_AT)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    String sql = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor()).as("load_db.py exit code").isZero();
    psql(container, "source", sql);
  }

  public static void remove(String... containers) {
    for (String c : containers) {
      try {
        run("podman", "rm", "-f", c);
      } catch (Exception ignored) {
        // best-effort teardown
      }
    }
  }
}
