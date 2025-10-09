package com.laurelid.testing.vectors

import android.content.Context
import androidx.annotation.RawRes
import com.laurelid.R
import java.io.InputStreamReader
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class HpkeVectorLoader @Inject constructor(private val context: Context) {

    fun loadDefault(): HpkeVector = load(R.raw.sample_hpke_vector)

    fun load(@RawRes resId: Int): HpkeVector {
        context.resources.openRawResource(resId).use { stream ->
            val text = InputStreamReader(stream).readText()
            val json = JSONObject(text)
            return HpkeVector(
                info = json.getString("info"),
                kem = json.getString("kem"),
                kdf = json.getString("kdf"),
                aead = json.getString("aead"),
                recipientPrivateKey = Base64.getDecoder().decode(json.getString("recipient_private_key_b64")),
                encapsulatedKey = Base64.getDecoder().decode(json.getString("encapsulated_key_b64")),
                ciphertext = Base64.getDecoder().decode(json.getString("ciphertext_b64")),
                aad = json.optString("aad_b64").takeIf { it.isNotEmpty() }?.let {
                    Base64.getDecoder().decode(it)
                },
                plaintext = Base64.getDecoder().decode(json.getString("plaintext_b64")),
            )
        }
    }
}

data class HpkeVector(
    val info: String,
    val kem: String,
    val kdf: String,
    val aead: String,
    val recipientPrivateKey: ByteArray,
    val encapsulatedKey: ByteArray,
    val ciphertext: ByteArray,
    val aad: ByteArray?,
    val plaintext: ByteArray,
)
