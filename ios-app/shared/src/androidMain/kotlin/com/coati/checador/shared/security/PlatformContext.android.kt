package com.coati.checador.shared.security

import android.content.Context

/**
 * PlatformContext Android: envuelve el applicationContext de la app.
 * El host Android debe construirlo con [PlatformContext] y registrar
 * el contexto antes de usar SecureStorage.
 */
actual open class PlatformContext actual constructor() {

    var context: Context? = null

    constructor(appContext: Context) : this() {
        context = appContext
    }
}
