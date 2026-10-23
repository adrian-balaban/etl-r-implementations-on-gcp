package mig.common;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates the DDL for {@code etlr_extraction.account_src} from the contracts, closing
 * the debt that the schema and the contracts must not drift apart in silence.
 *
 * <p>The debt was left open with an honest either/or: generate the generable part and say
 * the rest is not. This takes the first branch, and it is worth being precise about how far
 * it reaches.
 *
 * <p><b>Generable:</b> {@code account_src}. Its columns are the SRC record's TDS fields in
 * declaration order, plus the six ingest-metadata columns named by
 * {@code contracts/artefacts.json}'s {@code columns.src}. Both are contracts; nothing here
 * is a judgement call. Rename a field in the TDS and this output changes with it — which is
 * exactly the drift the generation guard is against, and which {@code PostgresDdlTest} now
 * fails on rather than discovering in production.
 *
 * <p><b>Not generable, and left hand-written:</b> the {@code etlr_transformation} and
 * {@code etlr_recon} tables. Their columns come from three different places at once — TDS
 * fields, mapping-yaml aliases, and enrichment columns that exist in no contract at all
 * ({@code balance_band}, {@code account_age_years}, and the {@code branch_*} columns joined
 * from {@code contracts/reference/branches.json}). A "generator" for those would be a Java
 * method with the column list typed into it, i.e. the same hand-written DDL with an extra
 * layer — worse than what it replaced, because it would look generated.
 *
 * <p>So: one table generated and verified against the checked-in file, five documented as
 * hand-written and why. That is the honest reading, and it is now enforced instead of
 * asserted.
 */
public final class GeneratePostgresDdl {

  private GeneratePostgresDdl() {}

  /** TDS type -> Postgres type. The one mapping decision, and it mirrors the parser's. */
  private static String postgresType(Tds.Field field) {
    return switch (field.type()) {
      case "date" -> "DATE";
      // Decimals travel as TEXT, not NUMERIC: the vendored schema.py maps decimals to
      // STRING so the exact source representation survives (see account_src.balance), and
      // fixtures/expected/account_src.tsv is written that way.
      case "decimal", "string", "int" -> "TEXT";
      default -> "TEXT";
    };
  }

  /**
   * The {@code CREATE TABLE etlr_extraction.account_src (...)} statement for the given
   * contracts directory, byte-for-byte as it appears in {@code sql/postgres-init.sql}.
   */
  public static String accountSrcDdl(Path contractsDir) {
    Tds.Definition definition = Tds.load(contractsDir.resolve("tds/tds-src-project1.def"));
    Tds.Record record = definition.record("ACCOUNT");

    Map<String, String> src = new Artefacts(contractsDir.resolve("artefacts.json")).srcColumns();

    List<String> lines = new ArrayList<>();
    for (Tds.Field field : record.fields()) {
      lines.add(
          "  " + field.name().toLowerCase() + " " + postgresType(field)
              + (field.required() ? " NOT NULL" : ""));
    }
    // Metadata columns, in artefacts.json's declared order. run_id is the only one the lane
    // cannot write a row without, hence the NOT NULL; the rest are populated but nullable,
    // matching the oracle's own dump.
    for (Map.Entry<String, String> e : src.entrySet()) {
      String column = e.getValue();
      String type = "batch_id".equals(e.getKey()) ? "BIGINT" : "TEXT";
      lines.add("  " + column + " " + type + ("run_id".equals(e.getKey()) ? " NOT NULL" : ""));
    }

    return "CREATE TABLE etlr_extraction.account_src (\n" + String.join(",\n", lines) + "\n);";
  }

  /** Prints the generated DDL — {@code mvn -q -pl common exec:java -Dexec.mainClass=...}. */
  public static void main(String[] args) {
    Path contractsDir = Path.of(args.length > 0 ? args[0] : "contracts");
    System.out.println(accountSrcDdl(contractsDir));
  }
}
