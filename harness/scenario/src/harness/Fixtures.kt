package harness

import com.google.gson.GsonBuilder

val gson = GsonBuilder().serializeNulls().create()

/** Same DJB2 the Statsig servers use to hash names when the client asks for "djb2". */
fun djb2(input: String): String {
    var hash = 0
    for (c in input) {
        hash = (hash shl 5) - hash + c.code
        hash = hash and hash
    }
    return hash.toUInt().toString()
}

val depExposure = mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "dep_rule")
val holdoutExposure = mapOf("gate" to "holdout", "gateValue" to "false", "ruleID" to "hold_rule")

fun gate(name: String, value: Boolean, rule: String, group: String? = null, exposures: List<Map<String, String>> = listOf()) =
    mapOf(
        "name" to name, "value" to value, "rule_id" to rule, "group_name" to group,
        "secondary_exposures" to exposures, "id_type" to "userID"
    )

fun config(
    name: String,
    value: Map<String, Any?>,
    rule: String,
    group: String? = null,
    exposures: List<Map<String, String>> = listOf(),
    active: Boolean = false,
    inExperiment: Boolean = false,
    deviceBased: Boolean = false,
    passed: Boolean? = null,
    extra: Map<String, Any?> = mapOf()
): Map<String, Any?> = mapOf(
    "name" to name, "value" to value, "rule_id" to rule, "group_name" to group,
    "secondary_exposures" to exposures, "undelegated_secondary_exposures" to listOf<Any>(),
    "is_experiment_active" to active, "is_user_in_experiment" to inExperiment,
    "is_device_based" to deviceBased
) + (if (passed == null) mapOf() else mapOf("passed" to passed)) + extra

/** Builds an initialize response, keying every entity by djb2(name) unless hashUsed is "none". */
fun initResponse(
    time: Long,
    gates: List<Map<String, Any?>>,
    configs: List<Map<String, Any?>>,
    layers: List<Map<String, Any?>> = listOf(),
    paramStores: Map<String, Any?> = mapOf(),
    hashUsed: String = "djb2",
    extra: Map<String, Any?> = mapOf()
): String {
    fun key(m: Map<String, Any?>) = (m["name"] as String).let { if (hashUsed == "djb2") djb2(it) else it }
    fun hashed(m: Map<String, Any?>) = if (hashUsed == "djb2") m + ("name" to key(m)) else m
    return gson.toJson(
        mapOf(
            "feature_gates" to gates.associate { key(it) to hashed(it) },
            "dynamic_configs" to configs.associate { key(it) to hashed(it) },
            "layer_configs" to layers.associate { key(it) to hashed(it) },
            "param_stores" to paramStores,
            "sdkParams" to mapOf<String, Any>(),
            "has_updates" to true,
            "time" to time,
            "hash_used" to hashUsed,
            "derived_fields" to mapOf("ip" to "1.2.3.4", "country" to "US"),
            "full_checksum" to "checksum-$time",
            "sdk_flags" to mapOf<String, Any>(),
            "sdk_configs" to mapOf<String, Any>()
        ) + extra
    )
}

val configValue = mapOf(
    "string" to "hello", "number" to 42, "double" to 3.5, "bool" to true,
    "arr" to listOf("a", "b"), "obj" to mapOf("k" to "v", "n" to 2), "nul" to null
)

fun paramStore() = mapOf(
    "store_one" to mapOf(
        "static_str" to mapOf("ref_type" to "static", "param_type" to "string", "value" to "sv"),
        "static_num" to mapOf("ref_type" to "static", "param_type" to "number", "value" to 7),
        "static_bool" to mapOf("ref_type" to "static", "param_type" to "boolean", "value" to true),
        "static_obj" to mapOf("ref_type" to "static", "param_type" to "object", "value" to mapOf("a" to 1)),
        "static_arr" to mapOf("ref_type" to "static", "param_type" to "array", "value" to listOf(1, 2)),
        "gate_str" to mapOf(
            "ref_type" to "gate", "param_type" to "string", "gate_name" to "always_on",
            "pass_value" to "yes", "fail_value" to "no"
        ),
        "gate_num" to mapOf(
            "ref_type" to "gate", "param_type" to "number", "gate_name" to "always_off",
            "pass_value" to 1, "fail_value" to 2
        ),
        "cfg_num" to mapOf(
            "ref_type" to "dynamic_config", "param_type" to "number",
            "config_name" to "test_config", "param_name" to "number"
        ),
        "exp_str" to mapOf(
            "ref_type" to "experiment", "param_type" to "string",
            "experiment_name" to "exp_active", "param_name" to "color"
        ),
        "layer_str" to mapOf(
            "ref_type" to "layer", "param_type" to "string",
            "layer_name" to "layer_one", "param_name" to "p2"
        ),
        "wrong_type" to mapOf("ref_type" to "static", "param_type" to "boolean", "value" to "no"),
        "bad_ref" to mapOf("ref_type" to "nope", "param_type" to "string", "value" to "x")
    )
)

fun responseA(time: Long = 1_700_000_000_000, color: String = "red", expActive: Boolean = true) = initResponse(
    time,
    gates = listOf(
        gate("always_on", true, "rule_on", "group_on", listOf(depExposure)),
        gate("always_off", false, "default"),
        gate("weird name \"quoted\" ünï", true, "rule_weird")
    ),
    configs = listOf(
        config("test_config", configValue, "cfg_rule", exposures = listOf(depExposure, holdoutExposure)),
        config("exp_active", mapOf("color" to color), "exp_rule", "Control", active = expActive, inExperiment = true, passed = true),
        config("exp_device", mapOf("size" to 1), "dev_rule", "Test", active = true, inExperiment = true, deviceBased = true),
        config("exp_inactive", mapOf("x" to 1), "inactive_rule", active = false, inExperiment = true),
        config("layer_exp", mapOf("p1" to "a", "p2" to "b"), "layer_exp_rule", active = true, inExperiment = true)
    ),
    layers = listOf(
        config(
            "layer_one", mapOf("p1" to "a", "p2" to "b", "p3" to 3, "p4" to listOf("x"), "p5" to mapOf("q" to true)),
            "layer_rule", "layer_group", exposures = listOf(depExposure, holdoutExposure),
            active = true, inExperiment = true,
            extra = mapOf(
                "allocated_experiment_name" to djb2("layer_exp"),
                "explicit_parameters" to listOf("p1"),
                "undelegated_secondary_exposures" to listOf(holdoutExposure),
                "parameter_rule_ids" to mapOf("p2" to "param_rule_p2")
            )
        )
    ),
    paramStores = paramStore()
)

fun responseB(time: Long = 1_700_000_100_000) = initResponse(
    time,
    gates = listOf(gate("always_on", false, "rule_b"), gate("b_only", true, "b_rule")),
    configs = listOf(config("test_config", mapOf("string" to "b"), "cfg_b")),
    hashUsed = "none"
)

/** A compact ("init-v2") payload, as produced by server-side SDKs for bootstrapping. */
fun bootstrapV2(userId: String?) = gson.toJson(
    mapOf(
        "response_format" to "init-v2",
        "feature_gates" to mapOf("boot_gate" to mapOf("v" to true, "r" to "boot_rule", "s" to listOf("0"))),
        "dynamic_configs" to mapOf(
            "boot_config" to mapOf("v" to "0", "r" to "boot_cfg_rule", "ea" to true, "ue" to true, "s" to listOf("0"))
        ),
        "layer_configs" to mapOf<String, Any>(),
        "values" to mapOf("0" to mapOf("k" to "boot")),
        "exposures" to mapOf("0" to depExposure),
        "has_updates" to true,
        "hash_used" to "none",
        "time" to 1_690_000_000_000,
        "evaluated_keys" to mapOf("userID" to userId),
        "sdkInfo" to mapOf("sdkType" to "node", "sdkVersion" to "1.0.0"),
        "user" to mapOf("userID" to userId, "privateAttributes" to mapOf("secret" to "x"))
    )
)

fun bootstrapV1(userId: String?) = gson.toJson(
    mapOf(
        "feature_gates" to mapOf(djb2("boot_gate") to gate(djb2("boot_gate"), true, "boot_v1_rule")),
        "dynamic_configs" to mapOf<String, Any>(),
        "layer_configs" to mapOf<String, Any>(),
        "has_updates" to true,
        "hash_used" to "djb2",
        "time" to 1_690_000_000_001,
        "evaluated_keys" to mapOf("userID" to userId, "customIDs" to mapOf("companyID" to "c1"))
    )
)
