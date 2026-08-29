package com.github.jvsena42.loopky.data.anki

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ZipReader] is the one piece of the iOS `.apkg` path with no counterpart on Android — Android
 * gets zip from `java.util.zip`, this parses the container by hand — so it is where a mistake would
 * be both easy and silent. The archives here are built byte by byte rather than by a zip library,
 * so the test does not depend on the same assumptions the reader does.
 */
class ZipReaderTest {

    @Test
    fun `recognises a zip by its signature`() {
        assertTrue(ZipReader.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `does not mistake other files for a zip`() {
        // A JPEG, which is what someone picking the wrong file usually hands over.
        assertTrue(!ZipReader.isZip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(!ZipReader.isZip(byteArrayOf(0x50)))
    }

    @Test
    fun `lists entries from the central directory`() {
        val zip = storedZip("collection.anki21" to "hello".encodeToByteArray())
        val entries = ZipReader.entries(zip)

        assertEquals(1, entries.size)
        assertEquals("collection.anki21", entries[0].name)
        assertEquals(5, entries[0].uncompressedSize)
    }

    @Test
    fun `reads a stored entry`() {
        val payload = "collection bytes".encodeToByteArray()
        val zip = storedZip("collection.anki2" to payload)
        val entry = ZipReader.entries(zip).single()

        assertContentEquals(payload, ZipReader.read(zip, entry))
    }

    @Test
    fun `reads several entries and keeps them apart`() {
        val zip = storedZip(
            "collection.anki21" to "first".encodeToByteArray(),
            "media" to "{}".encodeToByteArray(),
            "0" to "blob".encodeToByteArray(),
        )
        val byName = ZipReader.entries(zip).associateBy { it.name }

        assertEquals(3, byName.size)
        assertContentEquals("first".encodeToByteArray(), ZipReader.read(zip, byName.getValue("collection.anki21")))
        assertContentEquals("{}".encodeToByteArray(), ZipReader.read(zip, byName.getValue("media")))
        assertContentEquals("blob".encodeToByteArray(), ZipReader.read(zip, byName.getValue("0")))
    }

    @Test
    fun `finds the directory past a trailing comment`() {
        // A zip may carry a comment after the end-of-directory record, so the signature has to be
        // scanned for backwards rather than assumed to sit at a fixed offset.
        val zip = storedZip("a" to "b".encodeToByteArray(), comment = "a trailing comment")
        assertEquals("a", ZipReader.entries(zip).single().name)
    }

    @Test
    fun `a file with no directory record is refused`() {
        assertFailsWith<IllegalStateException> { ZipReader.entries("not a zip at all".encodeToByteArray()) }
    }

    @Test
    fun `an unsupported compression method is refused rather than returning junk`() {
        // Method 93 is zstd — what `collection.anki21b` uses, and the one thing this cannot read.
        val zip = storedZip("collection.anki21b" to "x".encodeToByteArray(), method = 93)
        val entry = ZipReader.entries(zip).single()

        assertFailsWith<IllegalStateException> { ZipReader.read(zip, entry) }
    }

    @Test
    fun `an entry pointing past the end of the file is refused`() {
        val zip = storedZip("a" to "bcd".encodeToByteArray())
        val entry = ZipReader.entries(zip).single().copy(compressedSize = 9_999)

        assertFailsWith<IllegalArgumentException> { ZipReader.read(zip, entry) }
    }

    @Test
    fun `an archive with no entries lists nothing`() {
        assertTrue(ZipReader.entries(storedZip()).isEmpty())
    }

    @Test
    fun `ZipBytesArchive refuses an entry over the limit`() {
        val archive = ZipBytesArchive(storedZip("big" to ByteArray(64) { 1 }))
        assertNull(archive.read("big", limit = 16))
        assertEquals(64, archive.read("big", limit = 128)?.size)
    }

    @Test
    fun `ZipBytesArchive reports an absent entry as null`() {
        val archive = ZipBytesArchive(storedZip("a" to "b".encodeToByteArray()))
        assertNull(archive.read("collection.anki21", limit = 1024))
        assertTrue(archive.contains("a"))
        assertTrue(!archive.contains("collection.anki21"))
    }

    /**
     * A zip built by hand, with every entry STORED.
     *
     * Deliberately not produced by a zip library: a fixture written by the same assumptions the
     * reader makes would pass whether or not either was right about the format.
     */
    private fun storedZip(
        vararg files: Pair<String, ByteArray>,
        comment: String = "",
        method: Int = 0,
    ): ByteArray {
        val out = mutableListOf<Byte>()
        val offsets = mutableListOf<Int>()

        files.forEach { (name, payload) ->
            offsets += out.size
            val nameBytes = name.encodeToByteArray()
            out += u32(0x04034b50)          // local file header
            out += u16(20) + u16(0)          // version, flags
            out += u16(method)
            out += u16(0) + u16(0)           // time, date
            out += u32(0)                    // crc, unchecked by the reader
            out += u32(payload.size) + u32(payload.size)
            out += u16(nameBytes.size) + u16(0)
            out += nameBytes.toList()
            out += payload.toList()
        }

        val directoryStart = out.size
        files.forEachIndexed { index, (name, payload) ->
            val nameBytes = name.encodeToByteArray()
            out += u32(0x02014b50)          // central file header
            out += u16(20) + u16(20) + u16(0)
            out += u16(method)
            out += u16(0) + u16(0)
            out += u32(0)
            out += u32(payload.size) + u32(payload.size)
            out += u16(nameBytes.size) + u16(0) + u16(0)
            out += u16(0) + u16(0) + u32(0)
            out += u32(offsets[index])
            out += nameBytes.toList()
        }

        val commentBytes = comment.encodeToByteArray()
        out += u32(0x06054b50)              // end of central directory
        out += u16(0) + u16(0)
        out += u16(files.size) + u16(files.size)
        out += u32(out.size - directoryStart) + u32(directoryStart)
        out += u16(commentBytes.size)
        out += commentBytes.toList()
        return out.toByteArray()
    }

    private fun u16(value: Int): List<Byte> =
        listOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun u32(value: Int): List<Byte> =
        u16(value and 0xFFFF) + u16((value shr 16) and 0xFFFF)
}
