package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

enum class InitializeFailReason {
    CoroutineTimeout,
    NetworkTimeout,
    NetworkError,
    InternalError
}

sealed class InitializeResponse {
    data class FailedInitializeResponse(
        @SerializedName("reason") val reason: InitializeFailReason,
        @SerializedName("exception") val exception: Exception? = null,
        @SerializedName("statusCode") val statusCode: Int? = null
    ) : InitializeResponse()
}

/** How entity names are hashed in an initialize response. */
enum class HashAlgorithm(val value: String) {
    @SerializedName("sha256")
    SHA256("sha256"),

    @SerializedName("djb2")
    DJB2("djb2"),

    @SerializedName("none")
    NONE("none")
}
