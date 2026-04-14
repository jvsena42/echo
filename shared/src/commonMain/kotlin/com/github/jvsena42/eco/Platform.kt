package com.github.jvsena42.eco

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform