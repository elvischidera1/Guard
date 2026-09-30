package com.statsig.androidsdk.sql

/**
 * The SDK's SQL, parsed into named blocks of statements.
 *
 * Format (see 00_schema.sql): `-- name: <block>` starts a block, a statement ends with `;` at the
 * end of a line (CREATE TRIGGER statements end at a line `END;`), and parameters are `:name`.
 * Parameters are rewritten to `?` so that any SQLite binding can run the statements.
 */
internal class SqlScript private constructor(val blocks: Map<String, List<Statement>>) {

    class Statement(val sql: String, val params: List<String>, val returnsRows: Boolean)

    fun block(name: String): List<Statement> =
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
        private val CREATE_TRIGGER = Regex("^CREATE\\s+(TEMP\\s+)?TRIGGER", RegexOption.IGNORE_CASE)
        private val RETURNS_ROWS = Regex("^(SELECT|WITH|VALUES)\\b", RegexOption.IGNORE_CASE)

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
            val blocks = LinkedHashMap<String, MutableList<Statement>>()
            for (source in sources) {
                var block: String? = null
                val current = ArrayList<String>()
                fun finish() {
                    val sql = current.joinToString("\n").trim()
                    current.clear()
                    if (sql.isNotEmpty()) blocks.getOrPut(block!!) { ArrayList() }.add(compile(sql))
                }
                for (line in source.lines()) {
                    val header = BLOCK_HEADER.find(line)
                    if (header != null) {
                        finish()
                        block = header.groupValues[1]
                        continue
                    }
                    if (block == null) continue
                    // comments between statements are not part of any statement
                    if (current.isEmpty() && (line.isBlank() || line.trimStart().startsWith("--"))) continue
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
            return SqlScript(blocks)
        }

        /** Replaces `:name` parameters (outside quotes and comments) with `?`. */
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
                    c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_') &&
                        (i == 0 || !(sql[i - 1].isLetterOrDigit() || sql[i - 1] == '_' || sql[i - 1] == ':')) -> {
                        var j = i + 1
                        while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                        params.add(sql.substring(i + 1, j))
                        out.append('?')
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
