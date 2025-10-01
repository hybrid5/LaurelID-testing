package com.laurelid.auth.deviceengagement

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

class DeviceEngagementParser {

    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun parse(payload: String): DeviceEngagement {
        try {
            val json = JSONObject(payload)
            val version = json.optInt(VERSION, 1)
            val handover = json.optJSONObject(HANDOVER)
                ?: throw IllegalArgumentException("Device engagement is missing handover information")

            val nfcDescriptor = handover.optJSONObject(NFC)?.let { parseTransportDescriptor(it) }
            val bleDescriptor = handover.optJSONObject(BLE)?.let { parseTransportDescriptor(it) }

            if (nfcDescriptor == null && bleDescriptor == null) {
                throw IllegalArgumentException("Device engagement does not contain a supported retrieval method")
            }

            return DeviceEngagement(version = version, nfc = nfcDescriptor, ble = bleDescriptor)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid device engagement payload", error)
        }
    }

    private fun parseTransportDescriptor(jsonObject: JSONObject): TransportDescriptor {
        val messagesArray: JSONArray = jsonObject.optJSONArray(MESSAGES)
            ?: throw IllegalArgumentException("Transport descriptor is missing messages array")
        val messages = mutableListOf<ByteArray>()
        for (index in 0 until messagesArray.length()) {
            val encoded = messagesArray.getString(index)
            messages += decoder.decode(encoded)
        }
        return TransportDescriptor(messages)
    }

    companion object {
        private const val VERSION = "version"
        private const val HANDOVER = "handover"
        private const val NFC = "nfc"
        private const val BLE = "ble"
        private const val MESSAGES = "messages"
    }
}
