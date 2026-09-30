package com.statsig.androidsdk.sql

/**
 * The SDK's SQL, parsed into named blocks of statements.
 *
 * Format (see 00_schema.sql): `-- name: <block>` starts a block, a statement ends with `;` at the
 * end of a line (CREATE TRIGGER statements end at a line `END;`), and parameters are `:name`.
 * Parameters are rewritten to `?` so that any SQLite binding can run the statements.
 * `-- @...` lines declare how a host may run the block (see [Block]).
 */
internal class SqlScript private constructor(val blocks: Map<String, Block>) {

    class Statement(val sql: String, val params: List<String>, val returnsRows: Boolean)

    /**
     * A named block and its directives:
     * `@reads: a, b` / `@writes: a, b` name the data it depends on / changes;
     * `@cache`: its result may be reused until a block writing what it reads runs (a result row
     * with `_cacheable` = 0 opts a single call out, and counts as a write);
     * `@defer`: calls may be queued and run later, in order, in one transaction: the first
     * statement once per call, the others once after each run of consecutive calls of the block
     * (calls also get `:time`);
     * `@coalesce`: queued calls with identical parameters may be merged (`repeat` counts them);
     * `@quiet: <ms>`: after a call that changed rows, identical calls made within <ms> change
     * nothing, as long as nothing the block reads is written in between (so they may be skipped).
     * Result columns: `_cacheable` = 0 (see `@cache`); `_then` = a block to run with the same
     * parameters before running this block once more.
     */
    class Block(
        val name: String,
        val statements: List<Statement>,
        val reads: Set<String>,
        val writes: Set<String>,
        val cache: Boolean,
        val defer: Boolean,
        val coalesce: Boolean,
        val quietMs: Long? = null
    )

    fun block(name: String): Block =
        blocks[name] ?: throw IllegalArgumentException("Unknown SQL block: $name")

    companion object {
        private val FILES = listOf(
            "00_schema.sql",
            "01_hashing.sql",
            "02_values.sql",
            "03_logging.sql",
            "04_network.sql",
            "05_evaluator.sql"
        )
        private val BLOCK_HEADER = Regex("^-- name: (\\S+)\\s*$")
        private val DIRECTIVE = Regex("^-- @(\\w+)(?::\\s*(.*))?$")
        private val CREATE_TRIGGER = Regex("^CREATE\\s+(TEMP\\s+)?TRIGGER", RegexOption.IGNORE_CASE)
        private val RETURNS_ROWS = Regex("^(SELECT|WITH|VALUES|PRAGMA)\\b", RegexOption.IGNORE_CASE)

        val default: SqlScript by lazy {
            parse(
                FILES.map { file ->
                    SqlScript::class.java.getResourceAsStream("/com/statsig/androidsdk/sql/$file")
                        ?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalStateException("Missing SQL resource $file")
                }
            )
        }

        fun parse(sources: List<String>): SqlScript {
            val statements = LinkedHashMap<String, MutableList<Statement>>()
            val directives = HashMap<String, MutableMap<String, String>>()
            for (source in sources) {
                var block: String? = null
                val current = ArrayList<String>()
                fun finish() {
                    val sql = current.joinToString("\n").trim()
                    current.clear()
                    if (sql.isNotEmpty()) {
                        statements.getOrPut(block!!) { ArrayList() }.add(compile(sql))
                    }
                }
                for (line in source.lines()) {
                    val header = BLOCK_HEADER.find(line)
                    if (header != null) {
                        finish()
                        block = header.groupValues[1]
                        statements.getOrPut(block) { ArrayList() }
                        continue
                    }
                    if (block == null) continue
                    if (current.isEmpty()) {
                        DIRECTIVE.find(line.trim())?.let {
                            directives.getOrPut(block) { HashMap() }[it.groupValues[1]] =
                                it.groupValues[2]
                        }
                        // comments between statements are not part of any statement
                        if (line.isBlank() || line.trimStart().startsWith("--")) continue
                    }
                    current.add(line)
                    val isTrigger = CREATE_TRIGGER.containsMatchIn(current.first().trimStart())
                    val ends = if (isTrigger) {
                        line.trim() == "END;"
                    } else {
                        !line.trimStart().startsWith("--") && line.trimEnd().endsWith(";")
                    }
                    if (ends) finish()
                }
                finish()
            }
            return SqlScript(
                statements.mapValues { (name, list) ->
                    val d = directives[name] ?: emptyMap()
                    fun set(key: String) =
                        d[key]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                            ?: emptySet()
                    Block(
                        name,
                        list,
                        set("reads"),
                        set("writes"),
                        "cache" in d,
                        "defer" in d,
                        "coalesce" in d,
                        d["quiet"]?.trim()?.toLong()
                    )
                }
            )
        }

        /**
         * Replaces `:name` parameters (outside quotes and comments) with `?N`, N numbering the
         * distinct names, so a parameter used several times is bound once.
         */
        private fun compile(sql: String): Statement {
            val out = StringBuilder(sql.length)
            val params = ArrayList<String>()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c == '\'' || c == '"' -> {
                        val end = sql.indexOf(c, i + 1).let { if (it < 0) sql.length - 1 else it }
                        out.append(sql, i, end + 1)
                        i = end + 1
                    }
                    c == '-' && sql.startsWith("--", i) -> {
                        val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                        i = end
                    }
                    c == ':' && i + 1 < sql.length &&
                        (sql[i + 1].isLetter() || sql[i + 1] == '_') &&
                        (
                            i == 0 ||
                                !(
                                    sql[i - 1].isLetterOrDigit() || sql[i - 1] == '_' ||
                                        sql[i - 1] == ':'
                                    )
                            ) -> {
                        var j = i + 1
                        while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                        val name = sql.substring(i + 1, j)
                        var index = params.indexOf(name)
                        if (index < 0) {
                            params.add(name)
                            index = params.size - 1
                        }
                        out.append('?').append(index + 1)
                        i = j
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            val text = out.toString().trim().removeSuffix(";")
            return Statement(text, params, RETURNS_ROWS.containsMatchIn(text))
        }
    }
}
