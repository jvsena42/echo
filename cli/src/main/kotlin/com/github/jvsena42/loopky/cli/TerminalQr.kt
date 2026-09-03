package com.github.jvsena42.loopky.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO

/**
 * A `pubkyauth://` URL as something a phone camera can read off a terminal.
 *
 * Half-blocks, so one text row carries two QR rows and the code stays square-ish in a font whose
 * cells are twice as tall as they are wide. A full block per module is either twice as wide as it
 * is tall — which scanners handle badly — or twice as many lines as most terminals show at once.
 *
 * **The colours are not decoration.** A QR code has to be dark-on-light, and a terminal's own
 * theme is unknown and frequently dark, so drawing modules in the default foreground produces an
 * inverted code no scanner will read. Every line therefore sets black-on-white explicitly and
 * resets at the end.
 */
object TerminalQr {

    fun render(text: String): String {
        val matrix = encode(text, MODULE_SIZE)
        val builder = StringBuilder()
        // Two matrix rows per text row; a matrix with an odd height leaves the last bottom half
        // blank, which the quiet zone already accounts for.
        var y = 0
        while (y < matrix.height) {
            builder.append(ANSI_BLACK_ON_WHITE)
            for (x in 0 until matrix.width) {
                val top = matrix.get(x, y)
                val bottom = y + 1 < matrix.height && matrix.get(x, y + 1)
                builder.append(
                    when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            builder.append(ANSI_RESET).append('\n')
            y += 2
        }
        return builder.toString()
    }

    /**
     * The same code as a PNG, for a box with no terminal anyone is looking at.
     *
     * **Created 0600 before a byte is written.** A QR code is an encoding, not a protection: this
     * file *is* the `pubkyauth://` URL, `secret=` included, and that secret is what the auth
     * token is encrypted to — anyone who reads it before the user approves in Ring can poll the
     * relay and take the session instead of the legitimate client. Written at the ambient umask on
     * a shared host, `--qr-out /tmp/qr.png` publishes a live credential to every uid on the machine
     * for the length of the approval window.
     *
     * Same order as `JsonFileStore.persist` and for the same reason: permissions first, content
     * second, so the readable window never exists. The caller deletes it once approval lands.
     */
    fun writePng(text: String, file: File) {
        val matrix = encode(text, PNG_SIZE)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) BLACK else WHITE)
            }
        }
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val path = createOwnerOnly(target.toPath())
        // A stream on the file we just created, **not** `ImageIO.write(…, File)`: that overload
        // deletes the file and recreates it, which throws away the mode set above and puts the
        // credential back at the ambient umask. Caught by QrCredentialTest, not by reading the API.
        Files.newOutputStream(path).use { output -> ImageIO.write(image, "png", output) }
    }

    /**
     * An empty file only its owner can read.
     *
     * Best-effort on the mode, like the session store: a filesystem with no POSIX permissions
     * cannot express it, and refusing to write there would cost a capability the host was never
     * going to give anyway.
     */
    private fun createOwnerOnly(path: Path): Path {
        Files.deleteIfExists(path)
        return runCatching {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)))
        }.getOrElse { Files.createFile(path) }
    }

    private fun encode(text: String, size: Int): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                // A `pubkyauth://` URL carries a relay address and a secret and runs long, so the
                // code is dense. L keeps the module count down; the scanner is 20cm from a screen,
                // not reading a smudged label.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * Requested size in modules. ZXing treats this as a floor and rounds up to whatever the
     * content needs, so a long URL produces a bigger matrix rather than a failure.
     */
    private const val MODULE_SIZE = 1

    private const val PNG_SIZE = 512

    /** Four modules is the QR spec's minimum quiet zone; below it scanners start missing the code. */
    private const val QUIET_ZONE_MODULES = 4

    private const val ANSI_BLACK_ON_WHITE = "\u001B[30;47m"
    private const val ANSI_RESET = "\u001B[0m"

    private const val FILE_MODE = "rw-------"

    private const val BLACK = 0x000000
    private const val WHITE = 0xFFFFFF
}
