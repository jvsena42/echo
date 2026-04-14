package com.github.jvsena42.eco.data.repository.impl

import kotlinx.serialization.json.Json

internal val echoJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}
