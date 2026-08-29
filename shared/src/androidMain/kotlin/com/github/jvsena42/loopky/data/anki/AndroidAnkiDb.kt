package com.github.jvsena42.loopky.data.anki

import android.database.sqlite.SQLiteDatabase

/**
 * [AnkiDb] over Android's platform SQLite.
 *
 * All this does is fetch rows; every query and every bit of interpretation lives in the shared
 * `ApkgCollection.kt`, so Android and iOS cannot drift into reading the same collection
 * differently.
 */
internal class AndroidAnkiDb(private val db: SQLiteDatabase) : AnkiDb {

    override fun query(sql: String, args: List<String>): List<AnkiRow> =
        db.rawQuery(sql, args.toTypedArray().takeIf { it.isNotEmpty() }).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    // Materialised per row rather than handing back the cursor: the shared reader
                    // walks the list more than once, and a cursor cannot be rewound.
                    add(CursorRow(cursor.columnNames.indices.map { index ->
                        Triple(
                            runCatching { cursor.getString(index) }.getOrNull(),
                            runCatching { cursor.getBlob(index) }.getOrNull(),
                            runCatching { cursor.getInt(index) }.getOrDefault(0),
                        )
                    }))
                }
            }
        }

    private class CursorRow(private val values: List<Triple<String?, ByteArray?, Int>>) : AnkiRow {
        override fun text(index: Int): String? = values.getOrNull(index)?.first
        override fun blob(index: Int): ByteArray? = values.getOrNull(index)?.second
        override fun int(index: Int): Int = values.getOrNull(index)?.third ?: 0
    }
}
