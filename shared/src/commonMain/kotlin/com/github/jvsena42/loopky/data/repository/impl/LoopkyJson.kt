package com.github.jvsena42.loopky.data.repository.impl

import kotlinx.serialization.json.Json

internal val loopkyJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}
