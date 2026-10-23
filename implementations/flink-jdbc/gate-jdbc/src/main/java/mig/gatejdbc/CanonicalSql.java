package mig.gatejdbc;

/**
 * The canonical line and content digest of spec/PIPELINE.md "Source System as a database", as Postgres
 * SQL — the one place the gate's formula lives. {@code to_char} pins the date format because
 * {@code DATE::text} follows the server's DateStyle; {@code NUMERIC::text} does not.
 */
public final class CanonicalSql {

  public static final String LINE =
      "concat_ws('|', coalesce(acct_id,''), coalesce(cust_id,''), coalesce(client_type,''),"
          + " coalesce(product_code,''), coalesce(currency,''), coalesce(to_char(open_date,'YYYY-MM-DD'),''),"
          + " coalesce(status,''), coalesce(balance::text,''))";

  public static final String PART =
      "('x' || substr(encode(sha256(convert_to(" + LINE + ", 'UTF8')), 'hex'), 1, 15))::bit(60)::bigint";

  private CanonicalSql() {}

  /** One row: {@code count(*)} and the exact digest (a {@code numeric}; 0 for an empty table). */
  public static String countAndDigest(String table) {
    return "SELECT count(*), coalesce(sum(" + PART + "), 0) FROM " + requireTableName(table);
  }

  /** Up to five non-blank keys that occur more than once after TRIM. */
  public static String duplicateKeys(String table) {
    return "SELECT btrim(acct_id) FROM " + requireTableName(table)
        + " WHERE btrim(acct_id) <> '' GROUP BY btrim(acct_id) HAVING count(*) > 1 ORDER BY 1 LIMIT 5";
  }

  /** table_name comes from a row another team writes: accept only {@code schema.table}, lower-case. */
  public static String requireTableName(String table) {
    if (table == null || !table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe table_name in extract_control: " + table);
    }
    return table;
  }
}
