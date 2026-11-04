package com.coati.checador.shared

class Greeting {
    private val platform: Platform = getPlatform()

    fun greet(): String {
        return "CoatiCheck corriendo en ${platform.name}"
    }
}
