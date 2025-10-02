package com.laurelid.auth

/**
 * Represents the structured failure modes that can occur while processing an mdoc engagement.
 */
sealed class MdocError(
    val code: Code,
    open val detail: String
) {

    enum class Code {
        INVALID_URI,
        MISSING_DEVICE_ENGAGEMENT,
        MALFORMED_DEVICE_ENGAGEMENT,
        UNSUPPORTED_TRANSPORT,
        NEGOTIATION_ERROR,
        UNSUPPORTED_RESPONSE_FORMAT,
        MALFORMED_DEVICE_RESPONSE,
        UNEXPECTED_FAILURE,
    }

    data class InvalidUri(override val detail: String) : MdocError(Code.INVALID_URI, detail)

    data class MissingDeviceEngagement(override val detail: String) :
        MdocError(Code.MISSING_DEVICE_ENGAGEMENT, detail)

    data class MalformedDeviceEngagement(override val detail: String) :
        MdocError(Code.MALFORMED_DEVICE_ENGAGEMENT, detail)

    data class UnsupportedTransport(override val detail: String) :
        MdocError(Code.UNSUPPORTED_TRANSPORT, detail)

    data class NegotiationFailure(override val detail: String) :
        MdocError(Code.NEGOTIATION_ERROR, detail)

    data class UnsupportedResponseFormat(override val detail: String) :
        MdocError(Code.UNSUPPORTED_RESPONSE_FORMAT, detail)

    data class MalformedDeviceResponse(override val detail: String) :
        MdocError(Code.MALFORMED_DEVICE_RESPONSE, detail)

    data class Unexpected(override val detail: String) :
        MdocError(Code.UNEXPECTED_FAILURE, detail)
}

class MdocParseException(
    val error: MdocError,
    cause: Throwable? = null
) : Exception(error.detail, cause)
