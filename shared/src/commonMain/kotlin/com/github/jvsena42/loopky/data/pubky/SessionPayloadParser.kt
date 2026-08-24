package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the session JSON payload returned by `pubky-core-ffi-fork` into a [Session].
 *
 * Payload shape (from `utils::session_to_json_with_cookie_secret`):
 * ```json
 * { "pubky": "...", "capabilities": ["/pub/loopky/:rw"], "session_secret": "..." }
 * ```
 *
 * Extra/aliased field names are tolerated so a future FFI bump continues to work. `grant_secret`
 * is one such alias and not a hypothetical: pubky 0.10's grant flow names the field that instead,
 * and the two are interchangeable downstream because the FFI's `restore_session` sniffs which kind
 * of token it was handed. Loopky asks for the cookie flow today (see
 * [PubkyClient.startAuthFlow]), so the alias is what keeps a switch back to grant auth (#130)
 * from failing here with a missing-field error rather than anywhere informative.
 */
internal fun parseSessionPayload(payload: String, json: Json): Session {
    val obj: JsonObject = json.parseToJsonElement(payload).jsonObject

    val pubkey = obj.stringField("pubky", "public_key", "publicKey")
        ?: error("session payload missing 'pubky'")
    val secret = obj.stringField("session_secret", "sessionSecret", "grant_secret", "grantSecret", "secret")
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
