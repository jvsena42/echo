package com.github.jvsena42.loopky.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--qr-out` writes a **bearer credential**, and has to be created like one.
 *
 * The PNG is the `pubkyauth://` URL with `secret=` in it, and that secret is what the auth token
 * is encrypted to — anyone who reads the file before the user approves in Ring can poll the relay
 * and take the session instead of the legitimate client. A QR code is an encoding, not a
 * protection. The flag exists for a box with no terminal anyone is watching, so
 * `--qr-out /tmp/qr.png` on a shared host is the *intended* use, which is exactly where the
 * ambient umask publishes it to every uid on the machine.
 */
class QrCredentialTest {

    private val authUrl =
        "pubkyauth://signin?caps=%2Fpub%2Floopky%2F%3Arw&relay=https%3A%2F%2Fhttprelay.pubky.app" +
            "%2Finbox&secret=zSFFp0nyJ_kZINkVgxnC2tTUc02n9oDxBm_KdUP9SQY"

    @Test
    fun `the png is owner-only`() {
        val file = File.createTempFile("loopky-qr", ".png")
        // Start it world-readable, so passing proves the writer set the mode rather than inherited
        // a strict umask from whatever ran the test.
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )

        TerminalQr.writePng(authUrl, file)

        val mode = Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            mode,
            "the QR file is a live credential and was left at $mode",
        )
        file.delete()
    }

    @Test
    fun `and it is a real png`() {
        val file = File.createTempFile("loopky-qr", ".png")
        TerminalQr.writePng(authUrl, file)

        // PNG magic, so the permission work above did not just produce an empty file.
        val header = file.readBytes().take(PNG_MAGIC.size)
        assertEquals(PNG_MAGIC, header)
        file.delete()
    }

    @Test
    fun `a missing parent directory is created`() {
        val dir = Files.createTempDirectory("loopky-qr-dir").resolve("nested").toFile()
        val file = File(dir, "qr.png")

        TerminalQr.writePng(authUrl, file)

        assertTrue(file.isFile)
        file.delete()
    }

    private companion object {
        val PNG_MAGIC = listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
