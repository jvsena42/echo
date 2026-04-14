package com.github.jvsena42.eco.data.pubky

/** Hex-encoded SHA-256 of [bytes]. Used as the content address for media blobs. */
internal expect fun sha256Hex(bytes: ByteArray): String
