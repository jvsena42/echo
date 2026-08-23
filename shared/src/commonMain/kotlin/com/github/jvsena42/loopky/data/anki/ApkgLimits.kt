package com.github.jvsena42.loopky.data.anki

/**
 * Ceilings on what one `.apkg` entry may inflate to.
 *
 * The picked-file limit bounds the **archive on disk** (500 MB, `MAX_IMPORT_FILE_BYTES`), which
 * says nothing about what is inside it: zip is a compressing format, so a few megabytes of crafted
 * archive inflates to gigabytes. `.apkg` files arrive from AnkiWeb and from chat shares, so the
 * input is untrusted by construction, and until now both read paths were unbounded — one entry
 * straight into a `ByteArray`, one straight into a temp file. Either is an OOM kill or a filled
 * cache partition.
 *
 * The policy lives in `commonMain` rather than beside the `java.util.zip` code so it can be tested
 * without a zip file, and so both platforms answer the question the same way if iOS ever grows its
 * own reader.
 */
internal object ApkgLimits {

    /**
     * One SQLite collection, extracted to a temp file.
     *
     * The largest real AnkiWeb collections are 1-5 MB; a text export of 20k cards is ~2 MB. 64 MB
     * is far above anything legitimate and far below anything that hurts.
     */
    const val MAX_COLLECTION_BYTES = 64L * 1024 * 1024

    /**
     * One media blob, read whole into memory before compression.
     *
     * Held entirely in heap by `MediaIndex`, so this is the number that decides whether a hostile
     * deck can OOM the app. 32 MB is generous for a flashcard picture and survivable on any device
     * Loopky runs on.
     */
    const val MAX_MEDIA_BYTES = 32L * 1024 * 1024

    /**
     * Whether an entry can be rejected before a byte is read.
     *
     * [declaredSize] is `ZipEntry.getSize()`, read from the archive's central directory — which
     * means the *archive* supplies it, and a hostile one can under-report or return -1 for unknown.
     * So this is an early-out that saves work on the honest case, never the enforcement: the copy
     * itself has to stay bounded as it runs. Unknown (-1) deliberately passes.
     */
    fun exceedsDeclaredSize(declaredSize: Long, limit: Long): Boolean =
        declaredSize > limit
}
