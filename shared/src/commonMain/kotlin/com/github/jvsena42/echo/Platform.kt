package com.github.jvsena42.echo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
