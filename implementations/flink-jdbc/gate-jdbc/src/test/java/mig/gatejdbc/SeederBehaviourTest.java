package mig.gatejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeederBehaviourTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Path SPEC = ROOT.resolve("../../spec").normalize();

  private Path sandbox;
  private Path log;
  private Path stdin;
  private Path dump;

  record Result(int exit, String out) {}

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(new ProcessBuilder("bash", "-c", "command -v python3").start().waitFor() == 0, "python3 not on PATH");
    sandbox = Files.createTempDirectory("seeder");
    // the image's /seed layout (Dockerfile.seeder)
    Path seed = sandbox.resolve("seed");
    Files.createDirectories(seed.resolve("harness"));
    Files.createDirectories(seed.resolve("fixtures"));
    Files.createDirectories(seed.resolve("sql"));
    Files.copy(SPEC.resolve("harness/load_db.py"), seed.resolve("harness/load_db.py"));
    Files.copy(SPEC.resolve("harness/generate.py"), seed.resolve("harness/generate.py"));
    Files.copy(SPEC.resolve("fixtures/ACCOUNT.src"), seed.resolve("fixtures/ACCOUNT.src"));
    Files.copy(ROOT.resolve("../flink/sql/postgres-init.sql").normalize(), seed.resolve("sql/postgres-init.sql"));
    Files.copy(ROOT.resolve("sql/postgres-init-jdbc.sql"), seed.resolve("sql/postgres-init-jdbc.sql"));
    log = sandbox.resolve("psql.log");
    stdin = sandbox.resolve("psql.stdin");
    dump = sandbox.resolve("dump");
    Files.writeString(log, "");
    Files.writeString(stdin, "");
    Path bin = Files.createDirectories(sandbox.resolve("bin"));
    Path psql = bin.resolve("psql");
    Files.writeString(psql, """
        #!/bin/bash
        echo "psql $* PGPASSWORD=${PGPASSWORD:-}" >> "$STUB_LOG"
        cat >> "$STUB_STDIN"
        case "$*" in
          *"-At"*) echo "${STUB_AT_OUT:-1}";;
          *COPY*) printf 'col\\tval\\n';;
        esac
        exit 0
        """);
    Files.setPosixFilePermissions(psql, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private Result seed(Map<String, String> extra, String... args) throws Exception {
    List<String> cmd = new java.util.ArrayList<>(List.of("bash", ROOT.resolve("gcp/seeder/seed.sh").toString()));
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    Map<String, String> env = pb.environment();
    env.put("PATH", sandbox.resolve("bin") + ":" + System.getenv("PATH"));
    env.put("SOURCE_HOST", "src-host");
    env.put("LANE_HOST", "lane-host");
    env.put("SOURCE_ADMIN_PASSWORD", "admin-pw");
    env.put("READER_PASSWORD", "reader-pw");
    env.put("LANE_PASSWORD", "lane-pw");
    env.put("SEED_ROOT", sandbox.resolve("seed").toString());
    env.put("DUMP_DIR", dump.toString());
    env.put("WORK_TMP", sandbox.toString());
    env.put("STUB_LOG", log.toString());
    env.put("STUB_STDIN", stdin.toString());
    env.putAll(extra);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(p.waitFor(), out);
  }

  @Test
  void fixtureLoadsTheTypedFixtureIntoTheSourceAsItsOwnerVouchedForAtTheOraclesDate() throws Exception {
    Result r = seed(Map.of(), "fixture", "run-gcp-fixture-001");
    assertThat(r.exit).as(r.out).isZero();
    assertThat(Files.readAllLines(log)).singleElement().asString()
        .contains("-h src-host", "-U mig", "-d source", "ON_ERROR_STOP=1", "PGPASSWORD=admin-pw");
    assertThat(Files.readString(stdin))
        .contains("INSERT INTO etlr_source.extract_control").contains("'run-gcp-fixture-001'")
        .contains("'2026-09-07T00:00:00Z'").contains(", 53, ");
  }

  @Test
  void generateLoadsNGeneratedAccounts() throws Exception {
    Result r = seed(Map.of(), "generate", "run-gcp-gen-001", "5");
    assertThat(r.exit).as(r.out).isZero();
    assertThat(Files.readString(stdin)).contains("INSERT INTO etlr_source.extract_control").contains("'run-gcp-gen-001'");
  }

  @Test
  void initCreatesTheReaderIdempotentlyWithoutPuttingItsPasswordInArgvAndAppliesBothLaneSchemas() throws Exception {
    Result r = seed(Map.of(), "init");
    assertThat(r.exit).as(r.out).isZero();
    String source = Files.readString(stdin);
    assertThat(source).contains("\\getenv reader_password READER_PASSWORD")
        .contains("WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'etlr_reader')")
        .contains("GRANT SELECT ON ALL TABLES IN SCHEMA etlr_source TO etlr_reader");
    List<String> calls = Files.readAllLines(log);
    assertThat(calls).allMatch(l -> !l.replace("PGPASSWORD=", "").contains("reader-pw"));
    assertThat(calls).anyMatch(l -> l.contains("-h lane-host") && l.endsWith("postgres-init.sql PGPASSWORD=lane-pw"));
    assertThat(calls).anyMatch(l -> l.contains("-h lane-host") && l.contains("postgres-init-jdbc.sql"));
  }

  @Test
  void tamperChangesExactlyOneBalanceOrFailsLoudly() throws Exception {
    assertThat(seed(Map.of(), "tamper").exit).isZero();
    assertThat(Files.readString(log)).contains("ACC000000011");
    Result none = seed(Map.of("STUB_AT_OUT", "0"), "tamper");
    assertThat(none.exit).isNotZero();
    assertThat(none.out).contains("ACC000000011 not found");
  }

  @Test
  void dumpWritesTheSevenLaneTablesAndSourceReadApart() throws Exception {
    Result r = seed(Map.of(), "dump", "run-gcp-fixture-001");
    assertThat(r.exit).as(r.out).isZero();
    for (String t : List.of("account_src", "account_curated", "account_enriched", "account_target", "reject_log",
        "record_lineage", "run_ledger")) {
      assertThat(dump.resolve("run-gcp-fixture-001/tables/" + t + ".tsv")).exists();
    }
    assertThat(dump.resolve("run-gcp-fixture-001/source_read.tsv")).exists();
    assertThat(Files.list(dump.resolve("run-gcp-fixture-001/tables")).count()).isEqualTo(7);
  }

  @Test
  void countPrintsOneLinePerLaneTable() throws Exception {
    Result r = seed(Map.of("STUB_AT_OUT", "0"), "count", "run-gcp-norow-001");
    assertThat(r.exit).isZero();
    assertThat(r.out.lines().filter(l -> l.endsWith("=0")).count()).isEqualTo(8);
  }

  @Test
  void aBadRunIdOrModeIsRefusedWithUsage() throws Exception {
    assertThat(seed(Map.of(), "dump", "Run_1; DROP").exit).isEqualTo(2);
    Result r = seed(Map.of(), "nonsense");
    assertThat(r.exit).isEqualTo(2);
    assertThat(r.out).contains("seed.sh fixture");
  }
}
