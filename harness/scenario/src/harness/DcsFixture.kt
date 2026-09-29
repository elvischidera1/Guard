package harness

/** A config-specs payload for OnDeviceEvalAdapter that touches every condition/operator family. */
object DcsFixture {
    private fun cond(type: String, op: String?, target: Any?, field: String? = null, idType: String = "userID", extra: Map<String, Any>? = null) =
        mapOf("type" to type, "operator" to op, "targetValue" to target, "field" to field, "idType" to idType, "additionalValues" to extra)

    private fun rule(
        id: String,
        conditions: List<Map<String, Any?>>,
        pass: Double = 100.0,
        value: Any? = true,
        group: String = "group_$id",
        delegate: String? = null,
        idType: String = "userID",
        experimentGroup: Boolean? = null
    ) = mapOf(
        "name" to id, "id" to id, "salt" to "salt_$id", "passPercentage" to pass, "returnValue" to value,
        "conditions" to conditions, "idType" to idType, "groupName" to group,
        "configDelegate" to delegate, "isExperimentGroup" to experimentGroup
    )

    private fun spec(
        name: String,
        type: String,
        rules: List<Map<String, Any?>>,
        default: Any? = false,
        enabled: Boolean = true,
        entity: String = type,
        active: Boolean = true,
        explicit: List<String>? = null
    ) = mapOf(
        "name" to name, "type" to type, "isActive" to active, "salt" to "salt_$name", "defaultValue" to default,
        "enabled" to enabled, "rules" to rules, "idType" to "userID", "entity" to entity,
        "explicitParameters" to explicit, "hasSharedParams" to false, "version" to 3
    )

    val gates = listOf(
        spec("dcs_public", "feature_gate", listOf(rule("public", listOf(cond("public", null, null))))),
        spec("dcs_email", "feature_gate", listOf(
            rule("email_any", listOf(cond("user_field", "any", listOf("A@EXAMPLE.com", "b@x.io"), "email"))),
            rule("email_contains", listOf(cond("user_field", "str_contains_any", listOf("@corp"), "email")))
        )),
        spec("dcs_numbers", "feature_gate", listOf(
            rule("level_gt", listOf(cond("user_field", "gt", 5, "level"))),
            rule("level_lte_str", listOf(cond("user_field", "lte", "2", "level"))),
            rule("level_gte_lt", listOf(cond("user_field", "gte", 3, "level"), cond("user_field", "lt", "4.5", "level")))
        )),
        spec("dcs_versions", "feature_gate", listOf(
            rule("v_gt", listOf(cond("user_field", "version_gt", "2.10.1", "appVersion"))),
            rule("v_eq", listOf(cond("user_field", "version_eq", "1.2", "app_version"))),
            rule("v_lt", listOf(cond("user_field", "version_lt", "1.0.0-beta", "appversion")))
        )),
        spec("dcs_strings", "feature_gate", listOf(
            rule("starts", listOf(cond("user_field", "str_starts_with_any", listOf("user-1"), "userID"))),
            rule("ends", listOf(cond("user_field", "str_ends_with_any", listOf("-7"), "userid"))),
            rule("regex", listOf(cond("user_field", "str_matches", "^user-[2-3]$", "userID"))),
            rule("none", listOf(cond("user_field", "none", listOf("US", "CA"), "country"))),
            rule("case", listOf(cond("user_field", "any_case_sensitive", listOf("Bronze"), "tier"))),
            rule("contains_none", listOf(cond("user_field", "str_contains_none", listOf("x"), "tier"))),
            rule("none_cs", listOf(cond("user_field", "none_case_sensitive", listOf("gold"), "tier")))
        )),
        spec("dcs_env", "feature_gate", listOf(
            rule("env", listOf(cond("environment_field", "any", listOf("staging"), "tier")))
        )),
        spec("dcs_unit", "feature_gate", listOf(
            rule("unit", listOf(cond("unit_id", "any", listOf("c1", "c3"), idType = "companyID"), cond("ip_based", "eq", "US", "country")), idType = "companyID")
        )),
        spec("dcs_bucket", "feature_gate", listOf(
            rule("bucket", listOf(cond("user_bucket", "lt", 500, extra = mapOf("salt" to "bucket_salt")))),
            rule("half", listOf(cond("public", null, null)), pass = 50.0)
        )),
        spec("dcs_time", "feature_gate", listOf(
            rule("before_2000", listOf(cond("current_time", "before", 946684800000L))),
            rule("after_2001", listOf(cond("current_time", "after", "978307200"))),
        )),
        spec("dcs_dates", "feature_gate", listOf(
            rule("signup_on", listOf(cond("user_field", "on", "2024-03-05T10:00:00.000Z", "signupAt"))),
            rule("signup_after", listOf(cond("user_field", "after", 1704067200, "signupAt")))
        )),
        spec("dcs_eq", "feature_gate", listOf(
            rule("eq_bool", listOf(cond("user_field", "eq", true, "beta"))),
            rule("neq_str", listOf(cond("user_field", "neq", "Bronze", "tier")))
        )),
        spec("dcs_nested", "feature_gate", listOf(
            rule("needs_email", listOf(cond("pass_gate", null, "dcs_email"), cond("fail_gate", null, "segment:internal"))),
            rule("not_numbers", listOf(cond("fail_gate", null, "dcs_numbers")))
        )),
        spec("segment:internal", "feature_gate", listOf(
            rule("seg", listOf(cond("user_field", "str_ends_with_any", listOf("@corp.com"), "email")))
        ), entity = "segment"),
        spec("dcs_disabled", "feature_gate", listOf(rule("x", listOf(cond("public", null, null)))), enabled = false),
        spec("dcs_unsupported", "feature_gate", listOf(
            rule("weird", listOf(cond("public", null, null), cond("mystery_type", "eq", "x"))),
        )),
        spec("dcs_bad_op", "feature_gate", listOf(
            rule("public_first", listOf(cond("user_field", "any", listOf("user-4"), "userID"))),
            rule("bad_op", listOf(cond("user_field", "sorta_equals", "x", "userID")))
        ))
    )

    val configs = listOf(
        spec("dcs_config", "dynamic_config", listOf(
            rule("cfg_email", listOf(cond("pass_gate", null, "dcs_email")), value = mapOf("tier" to "email", "n" to 1)),
            rule("cfg_half", listOf(cond("public", null, null)), pass = 50.0, value = mapOf("tier" to "half", "n" to 2.5))
        ), default = mapOf("tier" to "default"), entity = "dynamic_config"),
        spec("dcs_experiment", "dynamic_config", listOf(
            rule("layerAssignment", listOf(cond("user_bucket", "lt", 700, extra = mapOf("salt" to "exp_salt"))), value = mapOf("color" to "blue", "size" to 3), experimentGroup = true),
            rule("control", listOf(cond("public", null, null)), value = mapOf("color" to "grey", "size" to 1), experimentGroup = false)
        ), default = mapOf("color" to "none"), entity = "experiment", explicit = listOf("color"))
    )

    val layers = listOf(
        spec("dcs_layer", "dynamic_config", listOf(
            rule("delegate_rule", listOf(cond("user_field", "any", listOf("user-1", "user-2", "user-3"), "userID")), value = mapOf<String, Any>(), delegate = "dcs_experiment"),
            rule("layer_default", listOf(cond("public", null, null)), value = mapOf("color" to "layer", "size" to 9))
        ), default = mapOf("color" to "layer_default"), entity = "layer")
    )

    fun json(time: Long) = gson.toJson(
        mapOf(
            "feature_gates" to gates, "dynamic_configs" to configs, "layer_configs" to layers,
            "param_stores" to mapOf(
                "dcs_store" to mapOf(
                    "targetAppIDs" to listOf<String>(),
                    "parameters" to mapOf(
                        "p_gate" to mapOf("ref_type" to "gate", "param_type" to "boolean", "gate_name" to "dcs_public", "pass_value" to true, "fail_value" to false),
                        "p_static" to mapOf("ref_type" to "static", "param_type" to "string", "value" to "dcs")
                    )
                )
            ),
            "layers" to mapOf("dcs_layer" to listOf("dcs_experiment")),
            "time" to time, "has_updates" to true
        )
    )
}
