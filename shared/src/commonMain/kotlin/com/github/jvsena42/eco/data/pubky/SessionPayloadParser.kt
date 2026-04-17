package com.github.jvsena42.eco.data.pubky

import com.github.jvsena42.eco.domain.model.Capability
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the session JSON payload returned by `pubky-core-ffi-fork` into a [Session].
 *
 * Payload shape (from `utils::session_to_json_with_secret`):
 * ```json
 * { "pubky": "...", "capabilities": ["/pub/echo/:rw"], "session_secret": "..." }
 * ```
 *
 * Extra/aliased field names are tolerated so a future FFI bump continues to work.
 */
internal fun parseSessionPayload(payload: String, json: Json): Session {
    val obj: JsonObject = json.parseToJsonElement(payload).jsonObject

    val pubkey = obj.stringField("pubky", "public_key", "publicKey")
        ?: error("session payload missing 'pubky'")
    val secret = obj.stringField("session_secret", "sessionSecret", "secret")
        ?: error("session payload missing 'session_secret'")
    val homeserver = obj.stringField("homeserver", "home_server").orEmpty()
    val caps = obj["capabilities"]
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.map(::Capability)
        ?: emptyList()

    return Session(
        identity = PubkyIdentity(
            pubky = pubkey,
            displayName = null,
            avatarUrl = null,
            bio = null,
        ),
        sessionSecret = secret,
        capabilities = caps,
        homeserver = homeserver,
    )
}

private fun JsonObject.stringField(vararg names: String): String? {
    for (name in names) {
        val v = this[name]?.jsonPrimitive?.contentOrNull
        if (!v.isNullOrEmpty()) return v
    }
    return null
}
