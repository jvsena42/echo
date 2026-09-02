package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Desktop JVM `.apkg` reader.
 *
 * Identical to Android's but for where SQLite comes from: a desktop JVM has none in the platform,
 * so this is the one place `:shared` takes a JDBC driver. Everything else is [JvmApkgReader].
 *
 * Bulk Anki import is the most CLI-shaped job there is (#46), which is why this is on the critical
 * path for the JVM target rather than a nice-to-have.
 */
actual object ApkgReader {

    private val reader = JvmApkgReader(
        object : AnkiDbOpener {
            override fun <T> use(file: File, read: (AnkiDb) -> T): T =
                // `mode=ro` rather than a plain path: nothing here writes, and a read-write open
                // of a collection whose journal is missing is how a driver ends up modifying a
                // file we spooled out of somebody's archive.
                DriverManager.getConnection("jdbc:sqlite:file:${'$'}{file.absolutePath}?mode=ro")
                    .use { connection -> read(JdbcAnkiDb(connection)) }
        },
    )

    actual fun canRead(header: ByteArray): Boolean = reader.canRead(header)

    actual suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport> = reader.readNotes(path, mapping, compressImage)
}

/**
 * [AnkiDb] over JDBC.
 *
 * Like `AndroidAnkiDb`, all this does is fetch rows — every query and every bit of interpretation
 * lives in the shared `ApkgCollection.kt`.
 */
internal class JdbcAnkiDb(private val connection: Connection) : AnkiDb {

    override fun query(sql: String, args: List<String>): List<AnkiRow> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rs ->
                val columns = rs.metaData.columnCount
                buildList {
                    while (rs.next()) {
                        // Materialised per row rather than handing back the ResultSet: the shared
                        // reader walks the list more than once, and a forward-only cursor closes
                        // with its statement.
                        add(
                            JdbcRow(
                                (1..columns).map { index ->
                                    Triple(
                                        runCatching { rs.getString(index) }.getOrNull(),
                                        runCatching { rs.getBytes(index) }.getOrNull(),
                                        runCatching { rs.getInt(index) }.getOrDefault(0),
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }

    private class JdbcRow(private val values: List<Triple<String?, ByteArray?, Int>>) : AnkiRow {
        override fun text(index: Int): String? = values.getOrNull(index)?.first
        override fun blob(index: Int): ByteArray? = values.getOrNull(index)?.second
        override fun int(index: Int): Int = values.getOrNull(index)?.third ?: 0
    }
}
