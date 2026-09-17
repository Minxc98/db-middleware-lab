package com.pacvue.lab.mysql.domain;

/**
 * One line of tabular {@code EXPLAIN}, reduced to the columns the notes care about (08 §3).
 *
 * @param table      table or derived table this line is about
 * @param partitions partitions actually scanned - {@code null} when the table is not partitioned
 * @param type       access type; the ladder is {@code system > const > eq_ref > ref > range > index > ALL}
 * @param possibleKeys indexes the optimizer considered
 * @param key        index actually chosen, {@code null} when none was
 * @param keyLen     bytes of the index used - this is how you see how many columns of a
 *                   composite index a predicate really reached
 * @param rows       estimated rows to examine
 * @param filtered   percentage of those rows expected to survive the WHERE
 * @param extra      {@code Using index} (covering), {@code Using index condition} (ICP),
 *                   {@code Using filesort}, {@code Using temporary}, ...
 */
public record ExplainRow(
        String table,
        String partitions,
        String type,
        String possibleKeys,
        String key,
        Integer keyLen,
        Long rows,
        Double filtered,
        String extra) {

    public boolean usesIndex() {
        return key != null && !key.isBlank();
    }

    public boolean isFullTableScan() {
        return "ALL".equalsIgnoreCase(type);
    }

    public boolean isCoveringIndex() {
        // "Using index" means covering. "Using index condition" is ICP and is a different thing,
        // so match the token rather than a substring.
        return extra != null && java.util.Arrays.stream(extra.split(";"))
                .map(String::trim)
                .anyMatch("Using index"::equals);
    }

    public boolean hasExtra(String token) {
        return extra != null && extra.contains(token);
    }
}
