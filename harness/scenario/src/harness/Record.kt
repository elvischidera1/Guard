package harness

import com.google.gson.JsonParser
import com.statsig.androidsdk.BaseConfig
import com.statsig.androidsdk.DynamicConfig
import com.statsig.androidsdk.EvalDetails
import com.statsig.androidsdk.FeatureGate
import com.statsig.androidsdk.InitializationDetails
import com.statsig.androidsdk.Layer
import com.statsig.androidsdk.ParameterStore
import java.util.TreeMap

/** Canonical form: maps sorted by key, arrays → lists, whole numbers kept distinct from doubles. */
fun canon(v: Any?): Any? = when (v) {
    is Map<*, *> -> TreeMap<String, Any?>().apply { v.forEach { (k, x) -> put(k.toString(), canon(x)) } }
    is Array<*> -> v.map { canon(it) }
    is Iterable<*> -> v.map { canon(it) }
    else -> v
}

fun canonJson(v: Any?): String = gson.toJson(canon(v))

fun details(d: EvalDetails) = mapOf(
    "source" to d.source.toString(),
    "reason" to d.reason?.name,
    "lcut" to d.lcut,
    "receivedAt" to (d.receivedAt != null),
    "detailed" to d.getDetailedReasonString()
)

fun rec(g: FeatureGate) = mapOf(
    "name" to g.getName(), "value" to g.getValue(), "rule" to g.getRuleID(),
    "group" to g.getGroupName(), "idType" to g.getIDType(),
    "secondary" to canon(g.getSecondaryExposures()), "details" to details(g.getEvalDetails())
)

fun rec(c: DynamicConfig) = mapOf(
    "name" to c.getName(), "value" to canon(c.getValue()), "rule" to c.getRuleID(),
    "group" to c.getGroupName(), "inExperiment" to c.getIsUserInExperiment(),
    "active" to c.getIsExperimentActive(), "rulePassed" to c.getRulePassed(),
    "details" to details(c.getEvalDetails()),
    "getters" to mapOf(
        "string" to c.getString("string", "dflt"),
        "stringIfPresent" to c.getStringIfPresent("number"),
        "number" to c.getInt("number", -1),
        "long" to c.getLong("number", -1),
        "double" to c.getDouble("double", -1.0),
        "doubleIfPresent" to c.getDoubleIfPresent("string"),
        "bool" to c.getBoolean("bool", false),
        "arr" to c.getArray("arr", null)?.toList(),
        "dict" to canon(c.getDictionary("obj", null)),
        "nested" to c.getConfig("obj")?.getValue()?.let { canon(it) },
        "missing" to c.getString("missing", "fallback")
    )
)

/** Layer getters log parameter exposures, so the key list is part of the scenario. */
fun rec(l: Layer, keys: List<String> = listOf("p1", "p2", "p3", "p4", "p5", "missing")) = mapOf(
    "name" to l.getName(), "rule" to l.getRuleID(), "group" to l.getGroupName(),
    "allocated" to l.getAllocatedExperimentName(), "inExperiment" to l.getIsUserInExperiment(),
    "active" to l.getIsExperimentActive(), "details" to details(l.getEvalDetails()),
    "ruleForP2" to l.getRuleIDForParameter("p2"),
    "values" to keys.associateWith { k ->
        when (k) {
            "p3" -> l.getInt(k, -1)
            "p4" -> l.getArray(k, null)?.toList()
            "p5" -> canon(l.getDictionary(k, null))
            else -> l.getString(k, "dflt")
        }
    }
)

fun rec(p: ParameterStore) = mapOf(
    "name" to p.name,
    "keys" to p.getKeys().sorted(),
    "details" to details(p.getEvalDetails()),
    "static_str" to p.getString("static_str", "d"),
    "static_num" to p.getDouble("static_num", -1.0),
    "static_bool" to p.getBoolean("static_bool", false),
    "static_obj" to canon(p.getDictionary("static_obj", null)),
    "static_arr" to p.getArray("static_arr", null)?.toList(),
    "gate_str" to p.getString("gate_str", "d"),
    "gate_num" to p.getDouble("gate_num", -1.0),
    "cfg_num" to p.getDouble("cfg_num", -1.0),
    "exp_str" to p.getString("exp_str", "d"),
    "layer_str" to p.getString("layer_str", "d"),
    "wrong_type" to p.getBoolean("wrong_type", false),
    "bad_ref" to p.getStringIfPresent("bad_ref"),
    "missing" to p.getStringIfPresent("missing")
)

fun rec(i: InitializationDetails?) = i?.let {
    mapOf(
        "success" to it.success, "source" to it.source.toString(),
        "failure" to it.failureDetails?.reason?.name, "status" to it.failureDetails?.statusCode
    )
}

fun rec(b: BaseConfig) = "${b.javaClass.simpleName}:${b.getName()}"

private val TIMESTAMP = Regex("^\\d{10,}$")

/**
 * The markers of the last overall start..end run. The original SDK never clears update_user
 * markers, so each update re-sends all earlier ones (fixed in the SQL version); comparing the
 * latest run keeps both transcripts comparable.
 */
private fun lastRun(markers: List<com.google.gson.JsonElement>): List<com.google.gson.JsonElement> {
    val start = markers.indexOfLast {
        it.asJsonObject.get("key")?.asString == "overall" && it.asJsonObject.get("action")?.asString == "start"
    }
    return if (start <= 0) markers else markers.subList(start, markers.size)
}

/** Strips run-dependent parts (times, ids) from a log_event request. */
fun normalizeLogRequest(request: Map<String, Any?>): List<String> {
    @Suppress("UNCHECKED_CAST")
    val events = request["events"] as List<Map<String, Any?>>
    return events.map { e ->
        val m = e.toMutableMap()
        m.remove("time")
        @Suppress("UNCHECKED_CAST")
        val meta = (m["metadata"] as Map<String, Any?>?)?.toMutableMap()
        if (meta != null) {
            meta.replaceAll { k, v ->
                when {
                    k == "markers" && v is String -> lastRun(JsonParser.parseString(v).asJsonArray.toList()).map { mk ->
                        val o = mk.asJsonObject
                        listOf("key", "action", "step", "success", "attempt", "statusCode", "isBlocking")
                            .mapNotNull { f -> o.get(f)?.let { "$f=${it.asString}" } }.joinToString(",") +
                            (o.getAsJsonObject("evaluationDetails")?.get("reason")?.let { ",reason=${it.asString}" } ?: "")
                    }
                    k == "statsigOptions" && v is String -> "<options>"
                    // an encoded JSON map whose key order is not meaningful
                    k == "checks" && v is String -> canonJson(gson.fromJson(v, Map::class.java))
                    v is String && TIMESTAMP.matches(v) -> "<ts>"
                    else -> v
                }
            }
            m["metadata"] = meta
        }
        canonJson(m)
    }.sorted()
}
