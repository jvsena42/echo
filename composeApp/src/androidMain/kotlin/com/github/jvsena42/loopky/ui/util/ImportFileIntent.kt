package com.github.jvsena42.loopky.ui.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The file this intent is handing us, if any.
 *
 * Two ways in, mirroring [pubkyLink]: a file manager or a browser download sends `ACTION_VIEW`
 * with the uri as data, while a chat client sends `ACTION_SEND` with it in `EXTRA_STREAM`.
 *
 * Only `content:`/`file:` uris count. That is what keeps this from swallowing the `pubky://` and
 * `loopky://` links the same two actions also deliver — a `VIEW` on one of those has a scheme
 * this rejects, and shared text has no stream at all.
 *
 * Nothing here looks at the MIME type or the extension to decide *what* the file is. The manifest
 * filters use those to decide whether Loopky is worth offering; `ApkgReader.canRead` sniffs the
 * bytes once the file is in hand, and that is the actual type check.
 */
fun Intent.importFileUri(): Uri? {
    val uri = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    } ?: return null
    return uri.takeIf {
        it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE
    }
}
