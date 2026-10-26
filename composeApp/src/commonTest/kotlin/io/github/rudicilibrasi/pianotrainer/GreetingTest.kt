package io.github.rudicilibrasi.pianotrainer

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {

    @Test
    fun greetingNamesThePlatform() {
        assertEquals("Hello from Android 36!", greeting("Android 36"))
    }
}
