package com.coati.checador.shared

import com.coati.checador.shared.data.network.ApiBaseUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiBaseUrlTest {

    @Test
    fun `agrega sufijo api a host https`() {
        assertEquals(
            "https://api.coati.mx/api/",
            ApiBaseUrl.normalize("https://api.coati.mx")
        )
    }

    @Test
    fun `conserva sufijo api existente`() {
        assertEquals(
            "https://api.coati.mx/api/",
            ApiBaseUrl.normalize("https://api.coati.mx/api")
        )
    }

    @Test
    fun `rechaza http sin allowInsecure`() {
        assertNull(ApiBaseUrl.normalize("http://192.168.1.10:3000"))
    }

    @Test
    fun `acepta http solo en desarrollo local`() {
        assertEquals(
            "http://192.168.1.10:3000/api/",
            ApiBaseUrl.normalize("http://192.168.1.10:3000", allowInsecure = true)
        )
    }

    @Test
    fun `rechaza url nula o vacia`() {
        assertNull(ApiBaseUrl.normalize(null))
        assertNull(ApiBaseUrl.normalize(""))
        assertNull(ApiBaseUrl.normalize("   "))
    }

    @Test
    fun `rechaza entrada sin esquema`() {
        assertNull(ApiBaseUrl.normalize("api.coati.mx"))
    }

    @Test
    fun `fromHost usa https por defecto`() {
        assertEquals(
            "https://api.coati.mx/api/",
            ApiBaseUrl.fromHost("api.coati.mx")
        )
    }

    @Test
    fun `delega en normalize si ya trae esquema`() {
        assertEquals(
            "https://api.coati.mx/api/",
            ApiBaseUrl.fromHost("https://api.coati.mx")
        )
        assertNull(ApiBaseUrl.fromHost("http://10.0.0.1:3000"))
    }
}
