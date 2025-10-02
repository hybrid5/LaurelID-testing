package com.laurelid.auth

import com.augustcellars.cose.AlgorithmID
import com.augustcellars.cose.Attribute
import com.augustcellars.cose.HeaderKeys
import com.augustcellars.cose.OneKey
import com.augustcellars.cose.Sign1Message
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.DeviceResponseFormat
import com.upokecenter.cbor.CBORObject
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.Security
import java.util.Base64
import java.util.Locale

class ISO18013ParserTest {

    private val parser = ISO18013Parser()

    @Test
    fun `parseFromQrPayload negotiates COSE transport and decodes device response`() {
        val parsed = parser.parseFromQrPayload(GOLDEN_QR_COSE)

        assertEquals("did:example:alice", parsed.subjectDid)
        assertEquals("org.iso.18013.5.1.mDL", parsed.docType)
        assertEquals("AZ-MVD", parsed.issuer)
        assertTrue(parsed.ageOver21)
        assertNotNull(parsed.deviceSignedEntries)
        val namespace = parsed.deviceSignedEntries!!["org.iso.18013.5.1"]
        assertNotNull(namespace)
        namespace!!
        assertEquals("Doe", decodeCborString(namespace.getValue("family_name")))
        assertEquals("Alice", decodeCborString(namespace.getValue("given_name")))
        assertTrue(decodeCborBoolean(namespace.getValue("age_over_21")))
        assertEquals("did:example:alice", decodeCborString(namespace.getValue("subject_did")))
        assertEquals("issuer-auth", parsed.issuerAuth?.toString(StandardCharsets.UTF_8))
        assertNotNull(parsed.deviceSignedCose)
    }

    @Test
    fun `parseFromQrPayload falls back to SD-JWT when COSE unavailable`() {
        val parsed = parser.parseFromQrPayload(GOLDEN_QR_SD_JWT)

        assertEquals("did:example:bob", parsed.subjectDid)
        assertEquals("org.iso.18013.5.1.mDL", parsed.docType)
        assertEquals("CA-DMV", parsed.issuer)
        assertTrue(parsed.ageOver21)
        val namespace = parsed.deviceSignedEntries!!["org.iso.18013.5.1"]
        assertEquals("Bob", decodeCborString(namespace!!.getValue("given_name")))
        assertEquals("Doe", decodeCborString(namespace.getValue("family_name")))
    }

    @Test
    fun `parseFromNfc decodes COSE device response`() {
        val parsed = parser.parseFromNfc(GOLDEN_COSE_DEVICE_RESPONSE)

        assertEquals("did:example:alice", parsed.subjectDid)
        assertEquals("org.iso.18013.5.1.mDL", parsed.docType)
        assertEquals("AZ-MVD", parsed.issuer)
        assertEquals("issuer-auth", parsed.issuerAuth?.toString(StandardCharsets.UTF_8))
    }

    companion object {
        private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val baseDecoder = Base64.getDecoder()

        private val SIGNING_KEY: OneKey by lazy { buildSigningKey() }

        private val GOLDEN_COSE_PAYLOAD: ByteArray by lazy {
            buildDeviceResponsePayload(
                subjectDid = "did:example:alice",
                givenName = "Alice",
                issuer = "AZ-MVD"
            )
        }

        val GOLDEN_COSE_DEVICE_RESPONSE: ByteArray by lazy {
            signPayload(GOLDEN_COSE_PAYLOAD)
        }

        private val GOLDEN_SD_JWT_RESPONSE: String by lazy { buildSdJwtPayload() }

        val GOLDEN_QR_COSE: String by lazy {
            buildQrPayload(
                mapOf(
                    TransportType.NFC to mapOf(
                        DeviceResponseFormat.COSE_SIGN1 to GOLDEN_COSE_DEVICE_RESPONSE,
                        DeviceResponseFormat.SD_JWT to GOLDEN_SD_JWT_RESPONSE.toByteArray(StandardCharsets.UTF_8)
                    ),
                    TransportType.BLE to mapOf(DeviceResponseFormat.COSE_SIGN1 to GOLDEN_COSE_DEVICE_RESPONSE)
                ),
                supportedFormats = listOf(DeviceResponseFormat.COSE_SIGN1, DeviceResponseFormat.SD_JWT)
            )
        }

        val GOLDEN_QR_SD_JWT: String by lazy {
            buildQrPayload(
                mapOf(
                    TransportType.NFC to mapOf(DeviceResponseFormat.SD_JWT to GOLDEN_SD_JWT_RESPONSE.toByteArray(StandardCharsets.UTF_8))
                ),
                supportedFormats = listOf(DeviceResponseFormat.SD_JWT)
            )
        }

        @JvmStatic
        @BeforeClass
        fun installProvider() {
            Security.addProvider(BouncyCastleProvider())
        }

        private fun buildSigningKey(): OneKey {
            val dBytes = baseDecoder.decode("X6rM7cPfvGzBGVnOjFkWDXLI4YAlnXrhIRbkIuWGHGk=")
            val params = ECNamedCurveTable.getParameterSpec("secp256r1")
            val point = params.g.multiply(BigInteger(1, dBytes)).normalize()
            val xBytes = point.xCoord.toBigInteger().toUnsignedBytes(params.n.bitLength())
            val yBytes = point.yCoord.toBigInteger().toUnsignedBytes(params.n.bitLength())
            val map = CBORObject.NewMap()
            map.Add(com.augustcellars.cose.KeyKeys.KeyType.AsCBOR(), com.augustcellars.cose.KeyKeys.KeyType_EC2)
            map.Add(com.augustcellars.cose.KeyKeys.EC2_Curve.AsCBOR(), com.augustcellars.cose.KeyKeys.EC2_P256)
            map.Add(com.augustcellars.cose.KeyKeys.EC2_X.AsCBOR(), CBORObject.FromObject(xBytes))
            map.Add(com.augustcellars.cose.KeyKeys.EC2_Y.AsCBOR(), CBORObject.FromObject(yBytes))
            map.Add(com.augustcellars.cose.KeyKeys.EC2_D.AsCBOR(), CBORObject.FromObject(dBytes))
            return OneKey(map)
        }

        private fun signPayload(payload: ByteArray): ByteArray {
            val message = Sign1Message()
            message.AddAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), Attribute.PROTECTED)
            message.SetContent(payload)
            message.sign(SIGNING_KEY)
            return message.EncodeToBytes()
        }

        private fun buildDeviceResponsePayload(
            subjectDid: String,
            givenName: String,
            issuer: String
        ): ByteArray {
            val namespace = CBORObject.NewMap()
            namespace.Add("family_name", CBORObject.FromObject("Doe"))
            namespace.Add("given_name", CBORObject.FromObject(givenName))
            namespace.Add("subject_did", CBORObject.FromObject(subjectDid))
            namespace.Add("age_over_21", CBORObject.FromObject(true))

            val namespaces = CBORObject.NewMap()
            namespaces.Add("org.iso.18013.5.1", namespace)

            val deviceAuth = CBORObject.NewMap()
            deviceAuth.Add("deviceSignature", CBORObject.FromObject("device-signature".toByteArray(StandardCharsets.UTF_8)))

            val deviceSigned = CBORObject.NewMap()
            deviceSigned.Add("nameSpaces", namespaces)
            deviceSigned.Add("deviceAuth", deviceAuth)

            val issuerSigned = CBORObject.NewMap()
            issuerSigned.Add("issuer", CBORObject.FromObject(issuer))
            issuerSigned.Add("issuerAuth", CBORObject.FromObject("issuer-auth".toByteArray(StandardCharsets.UTF_8)))

            val document = CBORObject.NewMap()
            document.Add("docType", CBORObject.FromObject("org.iso.18013.5.1.mDL"))
            document.Add("issuerSigned", issuerSigned)
            document.Add("deviceSigned", deviceSigned)

            val documents = CBORObject.NewArray()
            documents.Add(document)

            val response = CBORObject.NewMap()
            response.Add("version", CBORObject.FromObject(1))
            response.Add("documents", documents)

            return response.EncodeToBytes()
        }

        private fun buildSdJwtPayload(): String {
            val header = urlEncoder.encodeToString("""{""alg"":""ES256"",""typ"":""vc+sd-jwt""}""".toByteArray(StandardCharsets.UTF_8))
            val payload = urlEncoder.encodeToString(
                JSONObject(
                    mapOf(
                        "iss" to "CA-DMV",
                        "doc_type" to "org.iso.18013.5.1.mDL"
                    )
                ).toString().toByteArray(StandardCharsets.UTF_8)
            )
            val signature = urlEncoder.encodeToString("sig".toByteArray(StandardCharsets.UTF_8))
            val disclosureSubject = urlEncoder.encodeToString(
                JSONArray(listOf("salt1", "subject_did", "did:example:bob")).toString().toByteArray(StandardCharsets.UTF_8)
            )
            val disclosureAge = urlEncoder.encodeToString(
                JSONArray(listOf("salt2", "age_over_21", true)).toString().toByteArray(StandardCharsets.UTF_8)
            )
            val disclosureFamily = urlEncoder.encodeToString(
                JSONArray(listOf("salt3", "family_name", "Doe")).toString().toByteArray(StandardCharsets.UTF_8)
            )
            val disclosureGiven = urlEncoder.encodeToString(
                JSONArray(listOf("salt4", "given_name", "Bob")).toString().toByteArray(StandardCharsets.UTF_8)
            )
            return listOf(
                "$header.$payload.$signature",
                disclosureSubject,
                disclosureAge,
                disclosureFamily,
                disclosureGiven
            ).joinToString("~")
        }

        private fun buildQrPayload(
            responses: Map<TransportType, Map<DeviceResponseFormat, ByteArray>>,
            supportedFormats: List<DeviceResponseFormat>
        ): String {
            val retrievalMethods = CBORObject.NewArray()
            responses.forEach { (type, formatMap) ->
                val method = CBORObject.NewMap()
                method.Add("type", CBORObject.FromObject(type.name.lowercase(Locale.ROOT)))
                val handshake = CBORObject.NewMap()
                val formats = CBORObject.NewArray()
                supportedFormats.forEach { format -> formats.Add(CBORObject.FromObject(format.label)) }
                handshake.Add("formats", formats)
                method.Add("handshake", handshake)
                val responseMap = CBORObject.NewMap()
                formatMap.forEach { (format, bytes) ->
                    responseMap.Add(CBORObject.FromObject(format.label), CBORObject.FromObject(bytes))
                }
                method.Add("responses", responseMap)
                retrievalMethods.Add(method)
            }
            val engagement = CBORObject.NewMap()
            engagement.Add("version", CBORObject.FromObject(1))
            engagement.Add("retrievalMethods", retrievalMethods)
            val encoded = engagement.EncodeToBytes()
            val parameter = urlEncoder.encodeToString(encoded)
            return "mdoc://engagement?e=$parameter"
        }

        private fun Map<String, ByteArray>.getValue(key: String): ByteArray =
            this[key] ?: error("Missing key $key")

        private fun decodeCborString(bytes: ByteArray): String =
            CBORObject.DecodeFromBytes(bytes).AsString()

        private fun decodeCborBoolean(bytes: ByteArray): Boolean =
            CBORObject.DecodeFromBytes(bytes).AsBoolean()

        private fun BigInteger.toUnsignedBytes(bitLength: Int): ByteArray {
            val byteLength = (bitLength + Byte.SIZE - 1) / Byte.SIZE
            val raw = toByteArray()
            if (raw.size == byteLength) return raw
            if (raw.size > byteLength) return raw.copyOfRange(raw.size - byteLength, raw.size)
            val result = ByteArray(byteLength)
            System.arraycopy(raw, 0, result, byteLength - raw.size, raw.size)
            return result
        }
    }
}
