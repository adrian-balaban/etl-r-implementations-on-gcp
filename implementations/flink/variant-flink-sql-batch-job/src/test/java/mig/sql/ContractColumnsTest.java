package mig.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import mig.common.Mapping;

/**
 *  /  — the transformation-side column names checked against
 * the contract that declares them.
 *
 * <p>{@code 10-intake} derives its header from the TDS and {@code account_src}'s DDL is generated
 * (GeneratePostgresDdlTest), but {@code 20-curate-enrich} and {@code 30-produce} address column names as
 * SQL literals. The contract has a real declaration of them — {@code curated.aliases} in
 * {@code mapping-project1.yaml}, the bridge between source field names and curated column names —
 * and until now nothing compared it with the SQL. Rename an alias (or a template column) and the
 * lane would keep running, writing the old name or NULLs, until a downstream reader noticed.
 *
 * <p>These are structural checks on text, deliberately not a SQL parser: they catch renames and
 * omissions, which is the failure that actually happens.
 */
class ContractColumnsTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");

  private static Mapping.Config mapping() {
    return Mapping.load(ROOT.resolve("contracts/mappings/mapping-project1.yaml"), ROOT);
  }

  @Test
  void everyCuratedAliasIsAColumnOfTheEnrichedTableInThePostgresSchema() throws Exception {
    Set<String> enrichedColumns = columnsOf("etlr_transformation.account_enriched");
    Map<String, String> aliases = mapping().curatedAliases();
    assertThat(aliases).as("the contract declares curated aliases").isNotEmpty();

    for (Map.Entry<String, String> a : aliases.entrySet()) {
      assertThat(enrichedColumns)
          .as("alias %s -> %s (mapping-project1.yaml) must be a column of account_enriched in sql/postgres-init.sql", a.getKey(), a.getValue())
          .contains(a.getValue());
    }
  }

  @Test
  void everyCuratedAliasIsProducedByTheCurateTemplate() throws Exception {
    String curate = Files.readString(ROOT.resolve("variant-flink-sql-batch-job/src/main/resources/sql/20-curate-enrich.sql.template"));
    Set<String> produced = selectedAliasesOf(curate);

    for (Map.Entry<String, String> a : mapping().curatedAliases().entrySet()) {
      assertThat(produced)
          .as("alias %s -> %s: 20-curate-enrich.sql.template must output a column with that name", a.getKey(), a.getValue())
          .contains(a.getValue());
    }
  }

  @Test
  void everySourceFieldTheContractMapsIsActuallyConsumedByTheCurateTemplate() throws Exception {
    String curate = Files.readString(ROOT.resolve("variant-flink-sql-batch-job/src/main/resources/sql/20-curate-enrich.sql.template"));
    // Only the account_curated_view SELECT reads account_src; take just that statement so a column
    // named in a COMMENT or in the enriched view cannot satisfy the check.
    int from = curate.indexOf("CREATE TEMPORARY VIEW account_curated_view");
    String view = stripComments(curate.substring(from, curate.indexOf(";", from))).toLowerCase();

    Set<String> sourceFields = new java.util.TreeSet<>();
    mig.common.Tds.Record record = mapping().srcRecord();
    record.fieldNames().forEach(n -> sourceFields.add(n.toLowerCase()));

    for (String sourceField : mapping().curatedAliases().keySet()) {
      String column = sourceField.toLowerCase();
      if (!sourceFields.contains(column)) {
        continue; // segment / branch / risk_rating / branch_name: enrichment columns, not source fields
      }
      assertThat(Pattern.compile("\\b" + Pattern.quote(column) + "\\b").matcher(view).find())
          .as("source field %s is mapped by the contract but 20-curate-enrich's account_curated_view never reads it", sourceField)
          .isTrue();
    }
  }

  @Test
  void theEnrichedColumnsTheProduceTemplateReadsAllExist() throws Exception {
    Set<String> enrichedColumns = columnsOf("etlr_transformation.account_enriched");
    String produce = stripComments(Files.readString(ROOT.resolve("variant-flink-sql-batch-job/src/main/resources/sql/30-produce.sql.template")));

    // The document's inputs, as the produce template writes them out. If one of these is renamed in
    // the schema the template keeps compiling — Flink resolves it at plan time, on the cluster.
    for (String column : List.of("account_id", "customer_id", "product_code", "currency", "opened_on",
        "status_code", "balance_minor", "balance_amount", "account_key")) {
      assertThat(enrichedColumns).as("schema has %s", column).contains(column);
      assertThat(produce).as("30-produce.sql.template reads %s", column).contains(column);
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Column names of a table, read from sql/postgres-init.sql's CREATE TABLE. */
  private static Set<String> columnsOf(String qualifiedTable) throws Exception {
    String sql = stripComments(Files.readString(ROOT.resolve("sql/postgres-init.sql")));
    int start = sql.indexOf("CREATE TABLE " + qualifiedTable);
    assertThat(start).as("CREATE TABLE %s in postgres-init.sql", qualifiedTable).isNotNegative();
    String body = sql.substring(sql.indexOf('(', start) + 1, sql.indexOf(");", start));
    Set<String> columns = new java.util.LinkedHashSet<>();
    for (String line : body.split("\n")) {
      Matcher m = Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s+[A-Za-z]").matcher(line);
      if (m.find()) {
        columns.add(m.group(1));
      }
    }
    return columns;
  }

  /** The output names of a SELECT list: every {@code AS name} plus bare {@code c.name} references. */
  private static Set<String> selectedAliasesOf(String template) {
    String sql = stripComments(template).toLowerCase();
    Set<String> names = new java.util.LinkedHashSet<>();
    Matcher as = Pattern.compile("\\bas\\s+([a-z_][a-z0-9_]*)").matcher(sql);
    while (as.find()) {
      names.add(as.group(1));
    }
    Matcher qualified = Pattern.compile("\\b[a-z]\\.([a-z_][a-z0-9_]*)").matcher(sql);
    while (qualified.find()) {
      names.add(qualified.group(1)); // c.account_id, b.branch_name in the enriched view
    }
    return names;
  }

  private static String stripComments(String sql) {
    List<String> out = new ArrayList<>();
    for (String line : sql.split("\n")) {
      int i = line.indexOf("--");
      out.add(i >= 0 ? line.substring(0, i) : line);
    }
    return String.join("\n", out);
  }
}
