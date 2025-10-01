package com.laurelid.auth

import com.laurelid.data.VerificationResult
import com.laurelid.network.TrustListRepository
import com.laurelid.util.Logger
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.Base64

class VerifierService(
    private val trustListRepository: TrustListRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
        val issuerAuth = parsed.issuerAuth
        val deviceSigned = parsed.deviceSignedEntries

        if (issuerAuth == null || deviceSigned == null) {
            Logger.w(TAG, "Issuer auth or device-signed payload missing; failing closed.")
            return failure(parsed, ERROR_NOT_IMPLEMENTED)
        }

        val trustSnapshot = try {
            trustListRepository.getOrRefresh(clock.millis(), maxCacheAgeMillis)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to refresh trust list; failing closed.", throwable)
            null
        }

        val trustList = trustSnapshot?.entries.orEmpty()
        if (trustSnapshot?.stale == true) {
            Logger.w(TAG, "Trust list cache is stale; verification proceeding with last known entries.")
        }

        if (trustList.isEmpty()) {
            return failure(parsed, ERROR_TRUST_LIST_UNAVAILABLE)
        }

        val decodedSign1 = decodeSign1(issuerAuth) ?: return failure(parsed, ERROR_MALFORMED_ISSUER_AUTH)
        val payload = decodedSign1.payload
        val mso = try {
            CBORObject.DecodeFromBytes(payload)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode Mobile Security Object payload", throwable)
            return failure(parsed, ERROR_MALFORMED_ISSUER_AUTH)
        }

        val docType = mso.getStringOrNull(DOCTYPE_KEY) ?: parsed.docType
        val issuer = mso.getStringOrNull(ISSUER_KEY) ?: parsed.issuer
        val digestAlgorithm = mso.getStringOrNull(DIGEST_ALG_KEY) ?: DEFAULT_DIGEST_ALGORITHM

        val validityInfo = parseValidityWindow(mso[CBORObject.FromObject(VALIDITY_INFO_KEY)])
        val now = clock.instant()

        validityInfo.notBefore?.let { notBefore ->
            if (now.isBefore(notBefore)) {
                Logger.w(TAG, "Credential not yet valid (notBefore=$notBefore)")
                return failure(parsed, ERROR_NOT_YET_VALID, issuer, docType)
            }
        }

        validityInfo.notAfter?.let { notAfter ->
            if (now.isAfter(notAfter)) {
                Logger.w(TAG, "Credential expired (notAfter=$notAfter)")
                return failure(parsed, ERROR_DOC_EXPIRED, issuer, docType)
            }
        }

        val trustAnchor = trustList[issuer]
            ?: return failure(parsed, ERROR_UNTRUSTED_ISSUER, issuer, docType)

        val certificate = decodeCertificate(trustAnchor)
            ?: return failure(parsed, ERROR_INVALID_TRUST_ENTRY, issuer, docType)

        try {
            certificate.checkValidity(java.util.Date.from(now))
        } catch (expired: CertificateExpiredException) {
            Logger.e(TAG, "Trust anchor certificate expired", expired)
            return failure(parsed, ERROR_TRUST_ANCHOR_EXPIRED, issuer, docType)
        } catch (notYetValid: CertificateNotYetValidException) {
            Logger.e(TAG, "Trust anchor certificate not yet valid", notYetValid)
            return failure(parsed, ERROR_TRUST_ANCHOR_NOT_YET_VALID, issuer, docType)
        }

        if (!validateSignature(decodedSign1, certificate.publicKey)) {
            Logger.w(TAG, "Issuer signature validation failed")
            return failure(parsed, ERROR_INVALID_SIGNATURE, issuer, docType)
        }

        val digestMap = parseValueDigests(mso[CBORObject.FromObject(VALUE_DIGESTS_KEY)])
        if (digestMap.isEmpty()) {
            Logger.w(TAG, "No value digests present in MSO; failing closed")
            return failure(parsed, ERROR_NOT_IMPLEMENTED, issuer, docType)
        }

        val messageDigest = try {
            MessageDigest.getInstance(digestAlgorithm)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unsupported digest algorithm $digestAlgorithm", throwable)
            return failure(parsed, ERROR_UNSUPPORTED_DIGEST, issuer, docType)
        }

        for ((namespace, expectedDigests) in digestMap) {
            val actualValues = deviceSigned[namespace]
                ?: return failure(parsed, ERROR_MISSING_DEVICE_VALUES, issuer, docType)

            for ((element, expectedDigest) in expectedDigests) {
                val rawValue = actualValues[element]
                    ?: return failure(parsed, ERROR_MISSING_DEVICE_VALUES, issuer, docType)

                val computed = messageDigest.digest(rawValue)
                if (!computed.contentEquals(expectedDigest)) {
                    Logger.w(TAG, "Digest mismatch for $namespace/$element")
                    return failure(parsed, ERROR_DEVICE_DATA_TAMPERED, issuer, docType)
                }
            }
        }

        val ageFlag = extractAgeFlag(deviceSigned)

        return VerificationResult(
            success = true,
            ageOver21 = ageFlag,
            issuer = issuer,
            subjectDid = parsed.subjectDid,
            docType = docType,
            error = null
        )
    }

    private fun failure(
        parsed: ParsedMdoc,
        error: String,
        issuerOverride: String? = null,
        docTypeOverride: String? = null
    ): VerificationResult {
        return VerificationResult(
            success = false,
            ageOver21 = parsed.ageOver21,
            issuer = issuerOverride ?: parsed.issuer,
            subjectDid = parsed.subjectDid,
            docType = docTypeOverride ?: parsed.docType,
            error = error
        )
    }

    private fun decodeSign1(bytes: ByteArray): DecodedSign1? {
        val coseObject = try {
            CBORObject.DecodeFromBytes(bytes)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode COSE_Sign1 envelope", throwable)
            return null
        }

        if (coseObject.type != CBORType.Array || coseObject.size() != 4) {
            Logger.w(TAG, "COSE_Sign1 structure malformed")
            return null
        }

        val protectedSection = coseObject[0]
        val payloadSection = coseObject[2]
        val signatureSection = coseObject[3]

        if (protectedSection == null || protectedSection.type != CBORType.ByteString) return null
        if (payloadSection == null || payloadSection.type != CBORType.ByteString) return null
        if (signatureSection == null || signatureSection.type != CBORType.ByteString) return null

        val protectedBytes = protectedSection.GetByteString()
        val protectedHeaders = if (protectedBytes.isEmpty()) {
            CBORObject.NewMap()
        } else {
            try {
                CBORObject.DecodeFromBytes(protectedBytes)
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Unable to decode protected headers", throwable)
                return null
            }
        }

        val algorithmId = protectedHeaders[CBORObject.FromObject(HEADER_ALG_ID)] ?: return null
        val signatureAlgorithm = SignatureAlgorithm.fromCoseId(algorithmId.AsNumber().ToInt32Checked())
            ?: return null

        return DecodedSign1(
            payload = payloadSection.GetByteString(),
            signature = signatureSection.GetByteString(),
            protectedBytes = protectedBytes,
            algorithm = signatureAlgorithm
        )
    }

    private fun validateSignature(message: DecodedSign1, publicKey: PublicKey): Boolean {
        return try {
            val verifier = Signature.getInstance(message.algorithm.jcaName)
            verifier.initVerify(publicKey)
            verifier.update(buildSignatureStructure(message.protectedBytes, message.payload))
            verifier.verify(convertSignature(message))
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Signature validation error", throwable)
            false
        }
    }

    private fun decodeCertificate(base64: String): X509Certificate? {
        return try {
            val decoded = Base64.getDecoder().decode(base64)
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(decoded)) as X509Certificate
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode trust anchor certificate", throwable)
            null
        }
    }

    private fun parseValueDigests(root: CBORObject?): Map<String, Map<String, ByteArray>> {
        if (root == null || root.type != CBORType.Map) return emptyMap()
        val result = mutableMapOf<String, Map<String, ByteArray>>()
        for (namespaceKey in root.keys) {
            val namespace = namespaceKey.AsString()
            val digestMap = root[namespaceKey]
            if (digestMap == null || digestMap.type != CBORType.Map) continue
            val inner = mutableMapOf<String, ByteArray>()
            for (elementKey in digestMap.keys) {
                val digestValue = digestMap[elementKey]
                if (digestValue != null && digestValue.type == CBORType.ByteString) {
                    inner[elementKey.AsString()] = digestValue.GetByteString()
                }
            }
            if (inner.isNotEmpty()) {
                result[namespace] = inner
            }
        }
        return result
    }

    private fun parseValidityWindow(cbor: CBORObject?): ValidityWindow {
        if (cbor == null || cbor.type != CBORType.Map) return ValidityWindow()
        val notBefore = cbor[CBORObject.FromObject(VALID_FROM_KEY)].toInstantOrNull()
        val notAfter = cbor[CBORObject.FromObject(VALID_UNTIL_KEY)].toInstantOrNull()
        return ValidityWindow(notBefore, notAfter)
    }

    private fun extractAgeFlag(deviceSigned: Map<String, Map<String, ByteArray>>): Boolean? {
        val namespace = deviceSigned[AGE_NAMESPACE] ?: return null
        val rawValue = namespace[AGE_ELEMENT] ?: return null
        return try {
            CBORObject.DecodeFromBytes(rawValue).AsBoolean()
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildSignatureStructure(protectedBytes: ByteArray, payload: ByteArray): ByteArray {
        return CBORObject.NewArray().apply {
            Add("Signature1")
            Add(CBORObject.FromObject(protectedBytes))
            Add(CBORObject.FromObject(ByteArray(0)))
            Add(CBORObject.FromObject(payload))
        }.EncodeToBytes()
    }

    private fun convertSignature(message: DecodedSign1): ByteArray {
        return when (message.algorithm) {
            SignatureAlgorithm.ES256 -> coseToDer(message.signature, message.algorithm.coordinateLength)
        }
    }

    private fun coseToDer(signature: ByteArray, coordinateLength: Int): ByteArray {
        if (signature.size != coordinateLength * 2) {
            return signature.copyOf() // Unexpected length; return as-is to force verification failure
        }

        val r = signature.copyOfRange(0, coordinateLength)
        val s = signature.copyOfRange(coordinateLength, signature.size)
        val rEncoded = prependZeroIfNeeded(trimLeadingZeros(r))
        val sEncoded = prependZeroIfNeeded(trimLeadingZeros(s))

        val sequenceLength = 2 + rEncoded.size + 2 + sEncoded.size
        val der = ByteArray(2 + sequenceLength)
        der[0] = 0x30
        der[1] = sequenceLength.toByte()
        der[2] = 0x02
        der[3] = rEncoded.size.toByte()
        System.arraycopy(rEncoded, 0, der, 4, rEncoded.size)
        val sIndex = 4 + rEncoded.size
        der[sIndex] = 0x02
        der[sIndex + 1] = sEncoded.size.toByte()
        System.arraycopy(sEncoded, 0, der, sIndex + 2, sEncoded.size)
        return der
    }

    private fun trimLeadingZeros(bytes: ByteArray): ByteArray {
        var index = 0
        while (index < bytes.size - 1 && bytes[index] == 0.toByte()) {
            index++
        }
        return bytes.copyOfRange(index, bytes.size)
    }

    private fun prependZeroIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return byteArrayOf(0)
        return if (bytes[0].toInt() and 0x80 != 0) {
            byteArrayOf(0) + bytes
        } else {
            bytes
        }
    }

    private fun CBORObject?.toInstantOrNull(): Instant? {
        if (this == null) return null
        return try {
            val epochSeconds = this.AsNumber().ToInt64Checked()
            Instant.ofEpochSecond(epochSeconds)
        } catch (_: Throwable) {
            null
        }
    }

    private fun CBORObject?.getStringOrNull(key: String): String? {
        val value = this?.get(CBORObject.FromObject(key)) ?: return null
        return if (value.type == CBORType.TextString) value.AsString() else null
    }

    private data class ValidityWindow(
        val notBefore: Instant? = null,
        val notAfter: Instant? = null
    )

    private data class DecodedSign1(
        val payload: ByteArray,
        val signature: ByteArray,
        val protectedBytes: ByteArray,
        val algorithm: SignatureAlgorithm
    )

    private enum class SignatureAlgorithm(val coseId: Int, val jcaName: String, val coordinateLength: Int) {
        ES256(-7, "SHA256withECDSA", 32);

        companion object {
            fun fromCoseId(id: Int): SignatureAlgorithm? = values().firstOrNull { it.coseId == id }
        }
    }

    companion object {
        private const val TAG = "VerifierService"

        private const val DOCTYPE_KEY = "docType"
        private const val ISSUER_KEY = "issuer"
        private const val DIGEST_ALG_KEY = "digestAlgorithm"
        private const val VALIDITY_INFO_KEY = "validityInfo"
        private const val VALID_FROM_KEY = "validFrom"
        private const val VALID_UNTIL_KEY = "validUntil"
        private const val VALUE_DIGESTS_KEY = "valueDigests"
        private const val AGE_NAMESPACE = "org.iso.18013.5.1"
        private const val AGE_ELEMENT = "age_over_21"
        private const val DEFAULT_DIGEST_ALGORITHM = "SHA-256"
        private const val HEADER_ALG_ID = 1

        const val ERROR_NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val ERROR_TRUST_LIST_UNAVAILABLE = "TRUST_LIST_UNAVAILABLE"
        const val ERROR_MALFORMED_ISSUER_AUTH = "MALFORMED_ISSUER_AUTH"
        const val ERROR_UNTRUSTED_ISSUER = "UNTRUSTED_ISSUER"
        const val ERROR_INVALID_TRUST_ENTRY = "INVALID_TRUST_ENTRY"
        const val ERROR_TRUST_ANCHOR_EXPIRED = "TRUST_ANCHOR_EXPIRED"
        const val ERROR_TRUST_ANCHOR_NOT_YET_VALID = "TRUST_ANCHOR_NOT_YET_VALID"
        const val ERROR_INVALID_SIGNATURE = "INVALID_SIGNATURE"
        const val ERROR_DOC_EXPIRED = "DOC_EXPIRED"
        const val ERROR_NOT_YET_VALID = "DOC_NOT_YET_VALID"
        const val ERROR_UNSUPPORTED_DIGEST = "UNSUPPORTED_DIGEST"
        const val ERROR_MISSING_DEVICE_VALUES = "MISSING_DEVICE_VALUES"
        const val ERROR_DEVICE_DATA_TAMPERED = "DEVICE_DATA_TAMPERED"
    }
}
