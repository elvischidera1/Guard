package com.statsig.androidsdk

import com.google.gson.reflect.TypeToken
import com.statsig.androidsdk.sql.Row

// Turns rows returned by the SQL blocks into the public model objects. No decisions are made
// here: every value, default and reason comes from the SQL.

private val gson = StatsigUtil.getOrBuildGson()
private val exposuresType = object : TypeToken<Array<Map<String, String>>>() {}.type
private val mapType = object : TypeToken<Map<String, Any>>() {}.type
private val paramStoreType = object : TypeToken<Map<String, Map<String, Any>>>() {}.type

internal fun Row.string(column: String): String? = this[column] as String?

internal fun Row.long(column: String): Long? = (this[column] as Number?)?.toLong()

internal fun Row.bool(column: String): Boolean = long(column)?.let { it != 0L } ?: false

internal fun jsonMap(json: String?): Map<String, Any> =
    json?.let { gson.fromJson<Map<String, Any>>(it, mapType) } ?: emptyMap()

/** A JSON value that should be an object; anything else reads as an empty map. */
internal fun jsonObject(json: String?): Map<String, Any> =
    if (json != null && json.trimStart().startsWith("{")) jsonMap(json) else emptyMap()

internal fun jsonExposures(json: String?): Array<Map<String, String>> =
    json?.let { gson.fromJson<Array<Map<String, String>>>(it, exposuresType) } ?: arrayOf()

internal fun jsonStrings(json: String?): Set<String>? =
    json?.let { gson.fromJson(it, Array<String>::class.java)?.toSet() }

internal fun jsonParamStore(json: String?): Map<String, Map<String, Any>> =
    json?.let { gson.fromJson<Map<String, Map<String, Any>>>(it, paramStoreType) } ?: emptyMap()

internal fun toJson(value: Any?): String? = value?.let { gson.toJson(it) }

/** EvalDetails from the source/reason/lcut/received_at columns. */
internal fun Row.evalDetails(): EvalDetails = EvalDetails(
    source = EvalSource.valueOf(string("source")!!),
    reason = string("reason")?.let { EvalReason.valueOf(it) },
    lcut = long("lcut"),
    receivedAt = long("received_at")
)

internal fun Row.toFeatureGate(): FeatureGate {
    val name = string("name")!!
    val details = evalDetails()
    return when {
        bool("overridden") -> FeatureGate(name, details, string("value") == "true", "override")
        !bool("found") -> FeatureGate(name, details, false)
        else -> FeatureGate(
            name,
            details,
            string("value") == "true",
            string("rule_id") ?: "",
            string("group_name"),
            jsonExposures(string("secondary_exposures")),
            string("id_type")
        )
    }
}

internal fun Row.toDynamicConfig(): DynamicConfig {
    val name = string("name")!!
    val details = evalDetails()
    return when {
        bool("overridden") -> DynamicConfig(name, details, jsonObject(string("value")), "override")
        !bool("found") -> DynamicConfig(name, details)
        else -> DynamicConfig(
            name,
            details,
            jsonObject(string("value")),
            string("rule_id") ?: "",
            string("group_name"),
            jsonExposures(string("secondary_exposures")),
            bool("is_user_in_experiment"),
            bool("is_experiment_active"),
            bool("is_device_based"),
            string("allocated_experiment_name"),
            long("rule_passed")?.let { it != 0L }
        )
    }
}

internal fun Row.toLayer(client: StatsigClient?): Layer {
    val name = string("name")!!
    val details = evalDetails()
    return when {
        bool("overridden") -> Layer(null, name, details, jsonObject(string("value")), "override")
        !bool("found") -> Layer(client, name, details)
        else -> Layer(
            client,
            name,
            details,
            jsonObject(string("value")),
            string("rule_id") ?: "",
            string("group_name"),
            jsonExposures(string("secondary_exposures")),
            jsonExposures(string("undelegated_secondary_exposures")),
            bool("is_user_in_experiment"),
            bool("is_experiment_active"),
            bool("is_device_based"),
            string("allocated_experiment_name"),
            jsonStrings(string("explicit_parameters")),
            string("parameter_rule_ids")?.let { json -> jsonMap(json).mapValues { it.value.toString() } }
        )
    }
}
