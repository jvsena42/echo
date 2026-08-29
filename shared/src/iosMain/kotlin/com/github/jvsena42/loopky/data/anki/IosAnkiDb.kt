package com.github.jvsena42.loopky.data.anki

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy
import sqlite3.SQLITE_OK
import sqlite3.SQLITE_OPEN_READONLY
import sqlite3.SQLITE_ROW
import sqlite3.SQLITE_TRANSIENT
import sqlite3.sqlite3_bind_text
import sqlite3.sqlite3_close
import sqlite3.sqlite3_column_blob
import sqlite3.sqlite3_column_bytes
import sqlite3.sqlite3_column_count
import sqlite3.sqlite3_column_int
import sqlite3.sqlite3_column_text
import sqlite3.sqlite3_finalize
import sqlite3.sqlite3_open_v2
import sqlite3.sqlite3_prepare_v2
import sqlite3.sqlite3_step

/**
 * [AnkiDb] over the system `libsqlite3`, reached by cinterop (`sqlite3.def`).
 *
 * Read-only, and read-only twice over: the handle is opened `SQLITE_OPEN_READONLY`, and the
 * collection it points at is a copy this import spooled out of the zip. Nothing here writes.
 *
 * Every query is materialised into rows before returning, matching the Android adapter — the
 * shared reader walks a result more than once, and a `sqlite3_stmt` cannot be rewound.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAnkiDb private constructor(private val handle: CPointerVar<sqlite3>) : AnkiDb {

    companion object {
        /** Opens [path], or null when SQLite refuses it — a corrupt or non-SQLite file. */
        fun open(path: String): IosAnkiDb? = memScoped {
            val handle = alloc<CPointerVar<sqlite3>>()
            val status = sqlite3_open_v2(path, handle.ptr, SQLITE_OPEN_READONLY, null)
            if (status != SQLITE_OK) {
                sqlite3_close(handle.value)
                null
            } else {
                IosAnkiDb(handle)
            }
        }
    }

    fun close() {
        sqlite3_close(handle.value)
    }

    override fun query(sql: String, args: List<String>): List<AnkiRow> = memScoped {
        val statement = alloc<CPointerVar<sqlite3_stmt>>()
        if (sqlite3_prepare_v2(handle.value, sql, -1, statement.ptr, null) != SQLITE_OK) {
            // A table this schema does not have. The shared reader tries both schemas and takes
            // whichever answers, so an absent table is an expected outcome, not a failure.
            return emptyList()
        }
        try {
            args.forEachIndexed { index, arg ->
                // 1-based, and SQLITE_TRANSIENT so SQLite copies the string rather than holding a
                // pointer into memory this scope is about to release.
                sqlite3_bind_text(statement.value, index + 1, arg, -1, SQLITE_TRANSIENT)
            }
            val columns = sqlite3_column_count(statement.value)
            buildList {
                while (sqlite3_step(statement.value) == SQLITE_ROW) {
                    add(readRow(statement.value, columns))
                }
            }
        } finally {
            sqlite3_finalize(statement.value)
        }
    }

    private fun readRow(statement: kotlinx.cinterop.CPointer<sqlite3_stmt>?, columns: Int): AnkiRow {
        val values = (0 until columns).map { index ->
            ColumnValue(
                text = sqlite3_column_text(statement, index)?.reinterpret<ByteVar>()?.toKString(),
                blob = readBlob(statement, index),
                int = sqlite3_column_int(statement, index),
            )
        }
        return MaterialisedRow(values)
    }

    private fun readBlob(statement: kotlinx.cinterop.CPointer<sqlite3_stmt>?, index: Int): ByteArray? {
        val size = sqlite3_column_bytes(statement, index)
        if (size <= 0) return null
        val pointer = sqlite3_column_blob(statement, index) ?: return null
        return ByteArray(size).apply {
            usePinned { memcpy(it.addressOf(0), pointer, size.toULong()) }
        }
    }

    private data class ColumnValue(val text: String?, val blob: ByteArray?, val int: Int)

    private class MaterialisedRow(private val values: List<ColumnValue>) : AnkiRow {
        override fun text(index: Int): String? = values.getOrNull(index)?.text
        override fun blob(index: Int): ByteArray? = values.getOrNull(index)?.blob
        override fun int(index: Int): Int = values.getOrNull(index)?.int ?: 0
    }
}
