package com.statsig.androidsdk

import com.statsig.androidsdk.sql.Row
import com.statsig.androidsdk.sql.StatsigDb

/**
 * Evaluates gates, configs, layers and parameter stores on the device from a config-specs
 * payload, for values newer than the ones the client has. The rules engine is SQL (see
 * 05_evaluator.sql); this class only feeds it and wraps its answers.
 */
class OnDeviceEvalAdapter(data: String?) {
    private companion object {
        private val gson = StatsigUtil.getOrBuildGson()
    }

    @Volatile
    private var data: String? = null

    @Volatile
    private var receivedAt: Long = 0L

    @Volatile
    private var db: StatsigDb? = null

    init {
        data?.let { setData(it) }
    }

    fun setData(data: String) {
        this.data = data
        receivedAt = System.currentTimeMillis()
        db?.run("dcs_load", mapOf("payload" to data, "received_at" to receivedAt))
    }

    /** Called by the client that uses this adapter, with its database. */
    internal fun attach(db: StatsigDb) {
        this.db = db
        data?.let { db.run("dcs_load", mapOf("payload" to it, "received_at" to receivedAt)) }
    }

    fun getGate(current: FeatureGate, user: StatsigUser): FeatureGate? {
        val row = evaluate("gate", current, user) ?: return null
        return FeatureGate(
            current.getName(),
            row.onDeviceDetails(),
            row.bool("bool"),
            row.string("rule_id") ?: "",
            row.string("group_name"),
            jsonExposures(row.string("secondary"))
        )
    }

    fun getDynamicConfig(current: DynamicConfig, user: StatsigUser): DynamicConfig? {
        val row = evaluate("config", current, user) ?: return null
        return DynamicConfig(
            name = current.getName(),
            details = row.onDeviceDetails(),
            jsonValue = jsonObject(row.string("value")),
            rule = row.string("rule_id") ?: "",
            groupName = row.string("group_name"),
            secondaryExposures = jsonExposures(row.string("secondary")),
            isExperimentActive = row.bool("is_active"),
            isUserInExperiment = row.bool("is_experiment_group")
        )
    }

    fun getLayer(client: StatsigClient?, current: Layer, user: StatsigUser): Layer? {
        val row = evaluate("layer", current, user) ?: return null
        return Layer(
            client = client,
            name = current.getName(),
            details = row.onDeviceDetails(),
            jsonValue = jsonObject(row.string("value")),
            rule = row.string("rule_id") ?: "",
            groupName = row.string("group_name"),
            secondaryExposures = jsonExposures(row.string("secondary")),
            undelegatedSecondaryExposures = jsonExposures(row.string("undelegated")),
            isExperimentActive = row.bool("is_active"),
            isUserInExperiment = row.bool("is_experiment_group"),
            allocatedExperimentName = row.string("config_delegate"),
            explicitParameters = jsonStrings(row.string("explicit_parameters"))
        )
    }

    fun getParamStore(client: StatsigClient, current: ParameterStore): ParameterStore? {
        val db = db ?: return null
        val row = db.one("dcs_param_store", mapOf("name" to current.name)) ?: return null
        if (row.long("time")!! <= (current.getEvalDetails().lcut ?: 0)) return null
        val parameters = row.string("parameters")
        return ParameterStore(
            client,
            jsonParamStore(parameters),
            current.name,
            onDeviceDetails(parameters == null, row.long("time"), row.long("received_at")),
            current.options
        )
    }

    /** Runs the SQL evaluation of kind/name when the specs are newer than [current]'s values. */
    private fun evaluate(kind: String, current: BaseConfig, user: StatsigUser): Row? {
        val db = db ?: return null
        synchronized(db) {
            val time = db.one("dcs_time")?.long("time") ?: return null
            if (time <= (current.getEvalDetails().lcut ?: 0)) return null
            val name = current.getName()
            val regexRequests = db.run(
                "eval_begin",
                mapOf("kind" to kind, "name" to name, "user" to gson.toJson(user))
            )
            for (request in regexRequests) {
                // The one thing SQLite cannot do portably: regular expressions (str_matches).
                val result = try {
                    if (Regex(
                            request.string("pattern")!!
                        ).containsMatchIn(request.string("value")!!)
                    ) {
                        1L
                    } else {
                        0L
                    }
                } catch (e: IllegalArgumentException) {
                    -1L
                }
                db.run("regex_result", request + ("result" to result))
            }
            db.run("eval_conditions")
            var progress = -1L
            while (true) {
                val next = db.one("eval_step")!!.long("progress")!!
                if (next == progress) break
                progress = next
            }
            val row = db.one("eval_result", mapOf("kind" to kind, "name" to name)) ?: return null
            if (row.bool("any_unsupported")) {
                Statsig.client.errorBoundary.logException(
                    UnsupportedEvaluationException("Unsupported condition or operator in $name"),
                    tag = "evaluate"
                )
            }
            // a spec that could not be resolved (e.g. a gate cycle) is reported as unrecognized
            return if (row["bool"] == null) row + ("unrecognized" to 1L) else row
        }
    }

    private fun Row.onDeviceDetails() =
        onDeviceDetails(bool("unrecognized"), long("lcut"), long("received_at"))

    private fun onDeviceDetails(unrecognized: Boolean, lcut: Long?, receivedAt: Long?) =
        EvalDetails(
            EvalSource.OnDeviceEvalAdapterBootstrap,
            if (unrecognized) EvalReason.Unrecognized else EvalReason.Recognized,
            lcut = lcut ?: 0,
            receivedAt = receivedAt
        )
}

class UnsupportedEvaluationException(message: String) : Exception(message)
