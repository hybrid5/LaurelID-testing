package com.laurelid.auth

import com.laurelid.data.VerificationResult
import com.laurelid.observability.StructuredEventLogger
import com.laurelid.network.TrustListRepository
import com.laurelid.util.Logger
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.inject.Singleton

@Singleton
open class VerifierService constructor(
    private val trustListRepository: TrustListRepository,
    private val clock: Clock
) {

    open suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
        val verificationStartMs = System.currentTimeMillis()
        val issuerAuth = parsed.issuerAuth
        val deviceSignedEntries = parsed.deviceSignedEntries
        val deviceSignedCose = parsed.deviceSignedCose

        if (issuerAuth == null || deviceSignedEntries == null || deviceSignedCose == null) {
            Logger.w(TAG, "Issuer auth or device-signed payload missing; failing closed.")
            return failure(verificationStartMs, null, parsed, ERROR_NOT_IMPLEMENTED)
        }

        val trustSnapshot = try {
            trustListRepository.getOrRefresh(clock.millis(), maxCacheAgeMillis)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to refresh trust list; failing closed.", throwable)
            null
        }

        val trustList = trustSnapshot?.entries.orEmpty()
        val trustStale = trustSnapshot?.stale
        val revokedSerials = trustSnapshot?.revokedSerialNumbers.orEmpty()
        val normalizedRevocations = revokedSerials.map { it.uppercase(Locale.US) }.toSet()
        if (trustSnapshot?.stale == true) {
            Logger.w(TAG, "Trust list cache is stale; verification proceeding with last known entries.")
        }

        if (trustList.isEmpty()) {
            return failure(verificationStartMs, trustStale, parsed, ERROR_TRUST_LIST_UNAVAILABLE)
        }

        fun fail(
            error: String,
            docTypeOverride: String? = null,
        ): VerificationResult = failure(verificationStartMs, trustStale, parsed, error, docTypeOverride)

        val decodedIssuerSign1 = decodeSign1(issuerAuth)
            ?: return fail(ERROR_MALFORMED_ISSUER_AUTH)
        val mso = try {
            CBORObject.DecodeFromBytes(decodedIssuerSign1.payload)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode Mobile Security Object payload", throwable)
            return fail(ERROR_MALFORMED_ISSUER_AUTH)
        }

        val docType = mso.getStringOrNull(DOCTYPE_KEY) ?: parsed.docType
        val issuer = mso.getStringOrNull(ISSUER_KEY) ?: parsed.issuer
        val digestAlgorithm = mso.getStringOrNull(DIGEST_ALG_KEY) ?: DEFAULT_DIGEST_ALGORITHM

        val validityInfo = parseValidityWindow(mso[CBORObject.FromObject(VALIDITY_INFO_KEY)])
        val now = clock.instant()

        validityInfo.notBefore?.let { notBefore ->
            if (now.isBefore(notBefore)) {
                Logger.w(TAG, "Credential not yet valid (notBefore=$notBefore)")
                return fail(ERROR_NOT_YET_VALID, docType)
            }
        }

        validityInfo.notAfter?.let { notAfter ->
            if (now.isAfter(notAfter)) {
                Logger.w(TAG, "Credential expired (notAfter=$notAfter)")
                return fail(ERROR_DOC_EXPIRED, docType)
            }
        }

        val trustAnchor = trustList[issuer]
            ?: return fail(ERROR_UNTRUSTED_ISSUER, docType)

        val certificate = decodeCertificate(trustAnchor)
            ?: return fail(ERROR_INVALID_TRUST_ENTRY, docType)

        try {
            certificate.checkValidity(java.util.Date.from(now))
        } catch (expired: CertificateExpiredException) {
            Logger.e(TAG, "Trust anchor certificate expired", expired)
            return fail(ERROR_TRUST_ANCHOR_EXPIRED, docType)
        } catch (notYetValid: CertificateNotYetValidException) {
            Logger.e(TAG, "Trust anchor certificate not yet valid", notYetValid)
            return fail(ERROR_TRUST_ANCHOR_NOT_YET_VALID, docType)
        }

        val validatedChain = validateCertificateChain(decodedIssuerSign1.certificateChain, certificate)
            ?: return fail(ERROR_ISSUER_AUTH_CHAIN_MISMATCH, docType)

        if (isCertificateRevoked(validatedChain, normalizedRevocations)) {
            Logger.w(TAG, "Issuer or trust anchor certificate revoked")
            return fail(ERROR_CERTIFICATE_REVOKED, docType)
        }

        if (!validatedChain.any { it.encoded.contentEquals(certificate.encoded) }) {
            Logger.w(TAG, "Issuer authentication chain mismatch")
            return fail(ERROR_ISSUER_AUTH_CHAIN_MISMATCH, docType)
        }

        if (!validateSignature(decodedIssuerSign1, certificate.publicKey)) {
            Logger.w(TAG, "Issuer signature validation failed")
            return fail(ERROR_INVALID_SIGNATURE, docType)
        }

        val devicePublicKey = extractDevicePublicKey(mso)
            ?: return fail(ERROR_INVALID_DEVICE_KEY_INFO, docType)

        val decodedDeviceSign1 = decodeSign1(deviceSignedCose)
            ?: return fail(ERROR_MALFORMED_DEVICE_SIGNED, docType)

        if (!validateSignature(decodedDeviceSign1, devicePublicKey)) {
            Logger.w(TAG, "Device signature validation failed")
            return fail(ERROR_INVALID_DEVICE_SIGNATURE, docType)
        }

        val signedEntries = parseDeviceSignedEntries(decodedDeviceSign1.payload)
            ?: return fail(ERROR_MALFORMED_DEVICE_SIGNED, docType)

        if (!deviceEntriesMatch(deviceSignedEntries, signedEntries)) {
            Logger.w(TAG, "Device signed payload mismatch between COSE and parsed entries")
            return fail(ERROR_DEVICE_DATA_MISMATCH, docType)
        }

        val digestMap = parseValueDigests(mso[CBORObject.FromObject(VALUE_DIGESTS_KEY)])
        if (digestMap.isEmpty()) {
            Logger.w(TAG, "No value digests present in MSO; failing closed")
            return fail(ERROR_NOT_IMPLEMENTED, docType)
        }

        val messageDigest = try {
            MessageDigest.getInstance(digestAlgorithm)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unsupported digest algorithm $digestAlgorithm", throwable)
            return fail(ERROR_UNSUPPORTED_DIGEST, docType)
        }

        for ((namespace, expectedDigests) in digestMap) {
            val actualValues = signedEntries[namespace]
                ?: return fail(ERROR_MISSING_DEVICE_VALUES, docType)

            for ((element, expectedDigest) in expectedDigests) {
                val rawValue = actualValues[element]
                    ?: return fail(ERROR_MISSING_DEVICE_VALUES, docType)

                val computed = messageDigest.digest(rawValue)
                if (!computed.contentEquals(expectedDigest)) {
                    Logger.w(TAG, "Digest mismatch for $namespace/$element")
                    return fail(ERROR_DEVICE_DATA_TAMPERED, docType)
                }
            }
        }

        val ageFlag = extractAgeFlag(signedEntries)

        val result = VerificationResult(
            success = true,
            ageOver21 = ageFlag,
            issuer = issuer,
            subjectDid = parsed.subjectDid,
            docType = docType,
            error = null,
            trustStale = trustStale,
        )
        logVerificationEvent(verificationStartMs, success = true, reasonCode = REASON_OK, trustStale = trustStale)
        return result
    }

    private fun failure(
        startTimeMs: Long,
        trustStale: Boolean?,
        parsed: ParsedMdoc,
        error: String,
        docTypeOverride: String? = null,
    ): VerificationResult {
        val sanitizedError = sanitizeReasonCode(error)
        val result = VerificationResult(
            success = false,
            ageOver21 = parsed.ageOver21,
            issuer = null,
            subjectDid = null,
            docType = docTypeOverride ?: parsed.docType,
            error = sanitizedError,
            trustStale = trustStale,
        )
        logVerificationEvent(startTimeMs, success = false, reasonCode = sanitizedError, trustStale = trustStale)
        return result
    }

    private fun logVerificationEvent(
        startTimeMs: Long,
        success: Boolean,
        reasonCode: String?,
        trustStale: Boolean?,
    ) {
        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        StructuredEventLogger.log(
            event = VERIFICATION_EVENT,
            timestampMs = startTimeMs,
            scanDurationMs = duration,
            success = success,
            reasonCode = reasonCode,
            trustStale = trustStale,
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
        val unprotectedSection = coseObject[1]
        val payloadSection = coseObject[2]
        val signatureSection = coseObject[3]

        if (protectedSection == null || protectedSection.type != CBORType.ByteString) return null
        if (unprotectedSection != null && unprotectedSection.type != CBORType.Map) return null
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

        val unprotectedHeaders = unprotectedSection ?: CBORObject.NewMap()

        val algorithmId = protectedHeaders[CBORObject.FromObject(HEADER_ALG_ID)]
            ?: unprotectedHeaders[CBORObject.FromObject(HEADER_ALG_ID)]
            ?: return null
        val signatureAlgorithm = SignatureAlgorithm.fromCoseId(algorithmId.AsNumber().ToInt32Checked())
            ?: return null

        val certificateChain = extractCertificateChain(protectedHeaders, unprotectedHeaders) ?: return null

        return DecodedSign1(
            payload = payloadSection.GetByteString(),
            signature = signatureSection.GetByteString(),
            protectedBytes = protectedBytes,
            algorithm = signatureAlgorithm,
            certificateChain = certificateChain
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

    private fun decodeCertificateBytes(bytes: ByteArray): X509Certificate? {
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode certificate from issuer auth chain", throwable)
            null
        }
    }

    private fun extractCertificateChain(
        protectedHeaders: CBORObject,
        unprotectedHeaders: CBORObject
    ): List<ByteArray>? {
        val chain = mutableListOf<ByteArray>()
        val protectedChain = parseX5Chain(protectedHeaders[CBORObject.FromObject(HEADER_X5CHAIN)])
            ?: return null
        val unprotectedChain = parseX5Chain(unprotectedHeaders[CBORObject.FromObject(HEADER_X5CHAIN)])
            ?: return null
        chain.addAll(protectedChain)
        chain.addAll(unprotectedChain)
        return chain
    }

    private fun parseX5Chain(header: CBORObject?): List<ByteArray>? {
        if (header == null) {
            return emptyList()
        }

        if (header.type == CBORType.ByteString) {
            return listOf(header.GetByteString())
        }

        if (header.type != CBORType.Array) {
            return null
        }

        val result = mutableListOf<ByteArray>()
        for (index in 0 until header.size()) {
            val item = header[index]
            if (item == null || item.type != CBORType.ByteString) {
                return null
            }
            result.add(item.GetByteString())
        }
        return result
    }

    private fun validateCertificateChain(
        chainBytes: List<ByteArray>,
        trustAnchor: X509Certificate
    ): List<X509Certificate>? {
        if (chainBytes.isEmpty()) {
            Logger.w(TAG, "Issuer auth missing certificate chain")
            return null
        }

        val decoded = mutableListOf<X509Certificate>()
        for (entry in chainBytes) {
            val certificate = decodeCertificateBytes(entry) ?: return null
            decoded.add(certificate)
        }

        val anchorEncoding = trustAnchor.encoded
        val certFactory = CertificateFactory.getInstance("X.509")
        val anchor = TrustAnchor(trustAnchor, null)
        val trustAnchors = setOf(anchor)
        val intermediates = decoded.filterNot { it.encoded.contentEquals(anchorEncoding) }

        return try {
            val certPath = certFactory.generateCertPath(intermediates)
            val parameters = PKIXParameters(trustAnchors).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(certPath, parameters)
            if (decoded.none { it.encoded.contentEquals(anchorEncoding) }) {
                decoded.add(trustAnchor)
            }
            decoded
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Issuer authentication chain validation failed", throwable)
            null
        }
    }

    private fun isCertificateRevoked(
        certificateChain: List<X509Certificate>,
        revokedSerials: Set<String>,
    ): Boolean {
        if (revokedSerials.isEmpty()) {
            return false
        }
        for (certificate in certificateChain) {
            val serialHex = certificate.serialNumber.toString(16).uppercase(Locale.US)
            if (revokedSerials.contains(serialHex)) {
                return true
            }
        }
        return false
    }

    private fun extractDevicePublicKey(mso: CBORObject): PublicKey? {
        val deviceKeyInfo = mso[CBORObject.FromObject(DEVICE_KEY_INFO_KEY)] ?: return null
        if (deviceKeyInfo.type != CBORType.Map) return null
        val deviceKey = deviceKeyInfo[CBORObject.FromObject(DEVICE_KEY_KEY)] ?: return null
        return coseKeyToPublicKey(deviceKey)
    }

    private fun coseKeyToPublicKey(cbor: CBORObject): PublicKey? {
        if (cbor.type != CBORType.Map) return null
        val kty = cbor[CBORObject.FromObject(COSE_KEY_KTY)] ?: return null
        if (!kty.IsNumber) return null
        if (kty.AsNumber().ToInt32Checked() != COSE_KEY_KTY_EC2) return null

        val curve = cbor[CBORObject.FromObject(COSE_KEY_CRV)] ?: return null
        if (!curve.IsNumber || curve.AsNumber().ToInt32Checked() != COSE_KEY_CRV_P256) return null

        val x = cbor[CBORObject.FromObject(COSE_KEY_X)] ?: return null
        val y = cbor[CBORObject.FromObject(COSE_KEY_Y)] ?: return null
        if (x.type != CBORType.ByteString || y.type != CBORType.ByteString) return null

        val xBytes = x.GetByteString()
        val yBytes = y.GetByteString()
        if (xBytes.size != P256_COORDINATE_LENGTH || yBytes.size != P256_COORDINATE_LENGTH) {
            return null
        }

        return createEcPublicKey(xBytes, yBytes)
    }

    private fun createEcPublicKey(x: ByteArray, y: ByteArray): PublicKey? {
        return try {
            val parameters = AlgorithmParameters.getInstance("EC")
            parameters.init(ECGenParameterSpec("secp256r1"))
            val ecParameters = parameters.getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
            val keySpec = ECPublicKeySpec(point, ecParameters)
            KeyFactory.getInstance("EC").generatePublic(keySpec)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to construct EC public key", throwable)
            null
        }
    }

    private fun parseDeviceSignedEntries(payload: ByteArray): Map<String, Map<String, ByteArray>>? {
        val root = try {
            CBORObject.DecodeFromBytes(payload)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode device signed payload", throwable)
            return null
        }

        if (root.type != CBORType.Map) return null
        val nameSpaces = root[CBORObject.FromObject(DEVICE_NAMESPACES_KEY)]
        return parseNameSpacesCbor(nameSpaces)
    }

    private fun parseNameSpacesCbor(root: CBORObject?): Map<String, Map<String, ByteArray>>? {
        if (root == null || root.type != CBORType.Map) return null
        val result = mutableMapOf<String, Map<String, ByteArray>>()
        for (namespaceKey in root.keys) {
            val namespace = namespaceKey.AsString()
            val entryMap = root[namespaceKey]
            if (entryMap == null || entryMap.type != CBORType.Map) {
                return null
            }
            val elements = mutableMapOf<String, ByteArray>()
            for (elementKey in entryMap.keys) {
                val elementValue = entryMap[elementKey]
                if (elementValue == null || elementValue.type != CBORType.ByteString) {
                    return null
                }
                elements[elementKey.AsString()] = elementValue.GetByteString()
            }
            result[namespace] = elements
        }
        return result
    }

    private fun deviceEntriesMatch(
        parsedEntries: Map<String, Map<String, ByteArray>>?,
        signedEntries: Map<String, Map<String, ByteArray>>
    ): Boolean {
        if (parsedEntries == null) return false
        if (parsedEntries.size != signedEntries.size) return false
        for ((namespace, parsedElements) in parsedEntries) {
            val signedElements = signedEntries[namespace] ?: return false
            if (parsedElements.size != signedElements.size) return false
            for ((element, parsedValue) in parsedElements) {
                val signedValue = signedElements[element] ?: return false
                if (!signedValue.contentEquals(parsedValue)) {
                    return false
                }
            }
        }
        return true
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
        val algorithm: SignatureAlgorithm,
        val certificateChain: List<ByteArray>
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
        private const val DEVICE_KEY_INFO_KEY = "deviceKeyInfo"
        private const val DEVICE_KEY_KEY = "deviceKey"
        private const val DEVICE_NAMESPACES_KEY = "nameSpaces"
        private const val AGE_NAMESPACE = "org.iso.18013.5.1"
        private const val AGE_ELEMENT = "age_over_21"
        private const val DEFAULT_DIGEST_ALGORITHM = "SHA-256"
        private const val HEADER_ALG_ID = 1
        private const val HEADER_X5CHAIN = 33
        private const val COSE_KEY_KTY = 1
        private const val COSE_KEY_KTY_EC2 = 2
        private const val COSE_KEY_CRV = -1
        private const val COSE_KEY_CRV_P256 = 1
        private const val COSE_KEY_X = -2
        private const val COSE_KEY_Y = -3
        private const val P256_COORDINATE_LENGTH = 32
        private const val VERIFICATION_EVENT = "verification_completed"
        private const val REASON_OK = "OK"
        private val REASON_CODE_REGEX = Regex("^[A-Z0-9_]+$")

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
        const val ERROR_ISSUER_AUTH_CHAIN_MISMATCH = "ISSUER_AUTH_CHAIN_MISMATCH"
        const val ERROR_CERTIFICATE_REVOKED = "CERTIFICATE_REVOKED"
        const val ERROR_INVALID_DEVICE_KEY_INFO = "INVALID_DEVICE_KEY_INFO"
        const val ERROR_MALFORMED_DEVICE_SIGNED = "MALFORMED_DEVICE_SIGNED"
        const val ERROR_INVALID_DEVICE_SIGNATURE = "INVALID_DEVICE_SIGNATURE"
        const val ERROR_DEVICE_DATA_MISMATCH = "DEVICE_DATA_MISMATCH"
        const val ERROR_CLIENT_EXCEPTION = "CLIENT_EXCEPTION"

        internal fun sanitizeReasonCode(reason: String?): String? {
            if (reason.isNullOrBlank()) return null
            val normalized = reason.uppercase(Locale.US)
            return if (REASON_CODE_REGEX.matches(normalized)) normalized else ERROR_CLIENT_EXCEPTION
        }
    }
}
