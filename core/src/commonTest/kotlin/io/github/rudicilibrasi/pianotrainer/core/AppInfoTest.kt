package io.github.rudicilibrasi.pianotrainer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {

    @Test
    fun appHasItsName() {
        assertEquals("Piano Trainer", AppInfo.NAME)
    }
}
