package com.coati.checador.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class CommonGreetingTest {

    @Test
    fun testExample() {
        assertTrue(Greeting().greet().contains("CoatiCheck"), "Check 'CoatiCheck' is mentioned")
    }
}
