package org.bootmoder.kmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform