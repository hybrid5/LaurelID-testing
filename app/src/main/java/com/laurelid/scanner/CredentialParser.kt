package com.laurelid.scanner

import com.laurelid.auth.ISO18013Parser
import com.laurelid.auth.ParsedMdoc
import javax.inject.Inject

interface CredentialParser {
    fun fromQr(payload: String): ParsedMdoc
    fun fromNfc(bytes: ByteArray): ParsedMdoc
}

class DefaultCredentialParser @Inject constructor(
    private val parser: ISO18013Parser,
) : CredentialParser {
    override fun fromQr(payload: String): ParsedMdoc = parser.parseFromQrPayload(payload)

    override fun fromNfc(bytes: ByteArray): ParsedMdoc = parser.parseFromNfc(bytes)
}
