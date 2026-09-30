package com.statsig.androidsdk

import com.statsig.androidsdk.sql.StatsigDb

/** Records diagnostics markers; storing, capping and turning them into events is SQL. */
internal class Diagnostics(private val db: StatsigDb) {
    private val gson = StatsigUtil.getOrBuildGson()

    fun markStart(
        key: KeyType,
        context: ContextType,
        step: StepType? = null,
        attempt: Int? = null
    ) = mark(context, key, ActionType.START, step, attempt = attempt)

    fun markEnd(
        key: KeyType,
        context: ContextType,
        success: Boolean,
        step: StepType? = null,
        evaluationDetails: EvalDetails? = null,
        attempt: Int? = null,
        sdkRegion: String? = null,
        statusCode: Int? = null,
        error: Marker.ErrorMessage? = null,
        hasNetwork: Boolean? = null
    ) = mark(
        context, key, ActionType.END, step, success, attempt, sdkRegion, statusCode, error,
        hasNetwork, evaluationDetails
    )

    fun logDiagnostics(context: ContextType): Boolean =
        db.one("log_diagnostics", mapOf("context" to wire(context)))?.bool("should_flush") == true

    private fun mark(
        context: ContextType,
        key: KeyType,
        action: ActionType,
        step: StepType?,
        success: Boolean? = null,
        attempt: Int? = null,
        sdkRegion: String? = null,
        statusCode: Int? = null,
        error: Marker.ErrorMessage? = null,
        hasNetwork: Boolean? = null,
        evaluationDetails: EvalDetails? = null
    ) {
        db.run(
            "mark",
            mapOf(
                "context" to wire(context),
                "key" to wire(key),
                "action" to wire(action),
                "step" to step?.let { wire(it) },
                "success" to success,
                "attempt" to attempt,
                "sdk_region" to sdkRegion,
                "status_code" to statusCode,
                "error" to error?.let { gson.toJson(it) },
                "has_network" to hasNetwork,
                "evaluation_details" to evaluationDetails?.let { gson.toJson(it.toLoggingEvaluationDetails()) }
            )
        )
    }

    /** The enum's wire name, e.g. "update_user". */
    private fun wire(value: Enum<*>): String = gson.toJson(value).trim('"')

    companion object {
        fun formatFailedResponse(failure: InitializeResponse.FailedInitializeResponse): Marker.ErrorMessage =
            Marker.ErrorMessage(
                message = "${failure.reason} : ${failure.exception?.message}",
                name = failure.exception?.javaClass?.toString() ?: "unknown"
            )
    }
}
