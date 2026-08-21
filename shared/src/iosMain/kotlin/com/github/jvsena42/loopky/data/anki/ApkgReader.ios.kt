package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage

/**
 * iOS `.apkg` reading is not implemented.
 *
 * The Android implementation leans on `java.util.zip` and `android.database.sqlite`, both in the
 * platform. iOS has neither equivalent exposed to Kotlin/Native, so this genuinely does need a zip
 * reader and a SQLite binding — the dependencies #43 §7 warned about. It is stubbed rather than
 * half-built because iOS is not runnable end to end yet either (see CLAUDE.md), so there is
 * nothing here to exercise.
 *
 * Anki's "Notes in Plain Text" export works on both platforms today and needs no dependencies.
 */
actual object ApkgReader {

    actual fun canRead(header: ByteArray): Boolean = false

    actual suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport> =
        Result.failure(
            ApkgException(
                ApkgFailure.UnsupportedFormat,
                "Importing .apkg files isn't supported on iOS yet. In Anki, export the deck as " +
                    "\"Notes in Plain Text\" and import that instead.",
            ),
        )
}
