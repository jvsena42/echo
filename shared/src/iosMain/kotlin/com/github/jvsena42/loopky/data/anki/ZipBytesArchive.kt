package com.github.jvsena42.loopky.data.anki

/**
 * [ApkgArchive] over an archive already in memory, inflated by [ZipReader].
 *
 * iOS reads the whole `.apkg` into memory rather than streaming it, which is the one place this
 * differs from Android. Two things make that acceptable: the picked-file ceiling
 * (`MAX_IMPORT_FILE_BYTES`, 500 MB) bounds the archive before it gets here, and the central
 * directory is parsed rather than the entries, so nothing is inflated until it is asked for.
 *
 * The per-entry bound is still enforced, and enforced twice: the declared size rejects an honest
 * oversized entry before any work, and the inflated length is re-checked because a hostile archive
 * can under-report.
 */
internal class ZipBytesArchive(private val bytes: ByteArray) : ApkgArchive {

    private val entries: Map<String, ZipReader.Entry> by lazy {
        ZipReader.entries(bytes).associateBy { it.name }
    }

    fun contains(name: String): Boolean = entries.containsKey(name)

    override fun read(name: String, limit: Long): ByteArray? {
        val entry = entries[name] ?: return null
        if (ApkgLimits.exceedsDeclaredSize(entry.uncompressedSize.toLong(), limit)) return null
        val inflated = runCatching { ZipReader.read(bytes, entry) }.getOrNull() ?: return null
        if (inflated.size.toLong() > limit) return null
        return inflated.takeIf { it.isNotEmpty() }
    }
}
