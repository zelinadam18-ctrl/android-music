package io.github.rudicilibrasi.pianotrainer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform