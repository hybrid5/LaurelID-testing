package com.laurelid.trust

import COSE.AlgorithmID
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laurelid.auth.cose.DefaultCoseVerifier
import com.laurelid.auth.session.VerificationError
import com.laurelid.ui.AdminBanner
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustBootstrapTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context

    @BeforeTest
    fun installProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun bannerVisibleWhenAnchorsMissing() {
        val bootstrap = TrustBootstrap(AssetTrustProvider(context, "trust/iaca"), Clock.systemUTC())
        val status = bootstrap.refreshAnchors()
        assertTrue(status.isEmpty())
        val banner = AdminBanner(context)
        instrumentation.runOnMainSync {
            banner.render(bootstrap.status.value)
        }
        assertTrue(bootstrap.state.value is TrustState.Degraded)
        assertEquals(View.VISIBLE, banner.visibility)
    }

    @Test
    fun issuerVerificationFailsWithoutAnchors() {
        val bootstrap = TrustBootstrap(AssetTrustProvider(context, "trust/iaca"), Clock.systemUTC())
        assertTrue(bootstrap.refreshAnchors().isEmpty())
        val message = buildSignedMessage()
        val verifier = DefaultCoseVerifier()
        assertFailsWith<VerificationError.IssuerTrustUnavailable> {
            verifier.verifyIssuer(message.EncodeToBytes(), bootstrap.anchors(), Instant.now())
        }
    }

    private fun buildSignedMessage(): Sign1Message {
        val keyPair = generateKeyPair()
        val certificate = selfSignedCertificate(keyPair)
        return Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            addAttribute(HeaderKeys.X5CHAIN, COSE.CBORObject.NewArray().apply { Add(certificate.encoded) }, true)
            SetContent(mapOf("given_name" to "Test").toCbor())
            sign(OneKey(keyPair.public, keyPair.private))
        }
    }

    private fun Map<String, Any?>.toCbor(): ByteArray {
        val obj = COSE.CBORObject.NewMap()
        for ((key, value) in this) {
            obj[COSE.CBORObject.FromObject(key)] = COSE.CBORObject.FromObject(value)
        }
        return obj.EncodeToBytes()
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        return generator.generateKeyPair()
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = java.util.Date()
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test"),
            BigInteger.ONE,
            now,
            java.util.Date(now.time + 86_400_000L),
            X500Name("CN=Test"),
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val holder: X509CertificateHolder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
    }
}
