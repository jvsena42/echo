package com.github.jvsena42.loopky.ui.util

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the clipboard held when the user tapped Paste in the image sheet.
 *
 * Copying a picture off the web produces one of two entirely different clips, and the difference
 * is not something the user chose knowingly: Chrome's "Copy image" writes the bytes behind a
 * `content:` uri, while "Copy image address" writes the URL as text. Both are "I copied that
 * image", so Paste has to accept either.
 */
sealed interface ClipboardImage {

    /** Image bytes, read out of the clip's `content:` uri. Still uncompressed. */
    class Bytes(val bytes: ByteArray, val mime: String) : ClipboardImage

    /** Whatever text was on the clipboard — a link, or just words to search for. */
    data class Text(val text: String) : ClipboardImage

    /** Nothing usable: an empty clipboard, or an image the app was not allowed to read. */
    data object Empty : ClipboardImage
}

/**
 * Reads the clipboard for the image sheet's Paste button.
 *
 * An image clip is never fallen back to as text. `ClipData.Item.coerceToText` happily renders an
 * unreadable `content:` uri as the string `content://com.android.chrome/…`, which would land in
 * the search field and be searched for — a failure that looks like the app misunderstanding the
 * paste rather than failing it.
 *
 * Reading a clipboard from Android 12 onward shows the system's "pasted from clipboard" toast.
 * That is the correct disclosure for a button the user just pressed, and the reason this is not
 * read speculatively anywhere else.
 */
suspend fun Context.readClipboardImage(): ClipboardImage {
    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ClipboardImage.Empty
    if (clip.itemCount == 0) return ClipboardImage.Empty
    val item = clip.getItemAt(0)
    val uri = item.uri
    if (uri != null && uri.scheme == ContentResolver.SCHEME_CONTENT) {
        // The provider's own type wins; the clip description is the fallback for providers that
        // report none. Anything that is not an image falls through to the text branch.
        val mime = contentResolver.getType(uri)
            ?: clip.description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)
        if (mime != null && mime.startsWith(IMAGE_MIME_PREFIX)) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .onFailure { Log.w(TAG, "Clipboard image could not be read", it) }
                    .getOrNull()
            }
            return bytes?.takeIf { it.isNotEmpty() }
                ?.let { ClipboardImage.Bytes(it, mime) }
                ?: ClipboardImage.Empty
        }
    }
    val text = item.coerceToText(this).toString().trim()
    return if (text.isEmpty()) ClipboardImage.Empty else ClipboardImage.Text(text)
}

private const val IMAGE_MIME_PREFIX = "image/"
private const val TAG = "Loopky/Clipboard"
