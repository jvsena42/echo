package com.github.jvsena42.loopky

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
