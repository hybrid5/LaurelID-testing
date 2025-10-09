package com.laurelid.testing

import java.io.InputStream
import java.util.Base64

object FixtureUtils {
    fun loadBase64(name: String): ByteArray =
        loadResource("mdoc/payloads/$name").use { stream -> Base64.getDecoder().decode(stream.readBytes()) }

    fun loadRaw(name: String): ByteArray = loadResource("mdoc/payloads/$name").use(InputStream::readBytes)

    fun loadCertificatePem(name: String): String =
        loadResource("mdoc/trust/$name").use { stream -> String(stream.readBytes()) }

    private fun loadResource(path: String): InputStream =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing fixture: $path" }
}

