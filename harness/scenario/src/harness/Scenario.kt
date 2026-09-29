package harness

import com.statsig.androidsdk.BaseConfig
import com.statsig.androidsdk.IStatsigCallback
import com.statsig.androidsdk.InitializationDetails
import com.statsig.androidsdk.OnDeviceEvalAdapter
import com.statsig.androidsdk.ParameterStoreEvaluationOptions
import com.statsig.androidsdk.Statsig
import com.statsig.androidsdk.StatsigOptions
import com.statsig.androidsdk.StatsigRuntimeMutableOptions
import com.statsig.androidsdk.StatsigUser
import com.statsig.androidsdk.Tier
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/**
 * Drives the SDK purely through its public API and records everything observable (return
 * values, evaluation details, network requests, logged events) into an ordered JSON transcript.
 * The transcript recorded from the original SDK is the golden file for the SQL re-implementation.
 */
class Scenario(private val app: HarnessApp = HarnessApp(), val server: FakeServer = FakeServer()) {
    val transcript = LinkedHashMap<String, Any?>()
    private val callbacks = ArrayList<String>()

    fun record(key: String, value: Any?) {
        check(!transcript.containsKey(key)) { "duplicate key $key" }
        transcript[key] = canon(value)
    }

    private fun options(configure: StatsigOptions.() -> Unit = {}) = StatsigOptions(
        api = server.api,
        eventLoggingAPI = server.api,
        sdkErrorAPI = server.api,
        evaluationCallback = { c: BaseConfig -> synchronized(callbacks) { callbacks.add(rec(c)) } }
    ).apply {
        setTier(Tier.STAGING)
        configure()
    }

    fun userA() = StatsigUser("user-a").apply {
        email = "a@example.com"
        appVersion = "3.1.4"
        custom = mapOf("level" to 3.0, "tier" to "Bronze", "beta" to true)
        privateAttributes = mapOf("secret" to "shh")
        customIDs = mapOf("companyID" to "c1")
    }

    fun userB() = StatsigUser("user-b").apply { customIDs = mapOf("companyID" to "c2") }

    /** Lets fire-and-forget SDK work (exposure/log coroutines, sticky persistence) finish. */
    private suspend fun settle() {
        withContext(Dispatchers.Main) {}
        delay(150)
        withContext(Dispatchers.Main) {}
    }

    private suspend fun flushAndRecord(key: String) {
        settle()
        Statsig.flush()
        settle()
        record("$key.events", server.drainLogs().flatMap { normalizeLogRequest(it) })
    }

    private fun recordInits(key: String) {
        record(
            key,
            server.drainInits().map { body ->
                @Suppress("UNCHECKED_CAST")
                val meta = body["statsigMetadata"] as Map<String, Any?>
                mapOf(
                    "user" to body["user"], "hash" to body["hash"], "sinceTime" to body["sinceTime"],
                    "previousDerivedFields" to body["previousDerivedFields"],
                    "full_checksum" to body["full_checksum"],
                    "metadataKeys" to meta.keys.sorted(),
                    "sdkType" to meta["sdkType"], "hasStableID" to (meta["stableID"] != null)
                )
            }
        )
    }

    private fun stableId(): String = Statsig.getStatsigMetadata().stableID!!

    suspend fun run() {
        Env.install(app)
        record("preInit.checkGate", runCatching { Statsig.checkGate("always_on") }.exceptionOrNull()?.javaClass?.simpleName)

        // ---- Phase 1: network initialize for user A ----
        server.initBody = responseA()
        val init1 = Statsig.initialize(app, "client-harness", userA(), options())
        record("p1.init", rec(init1))
        recordInits("p1.initRequests")
        val stable = stableId()
        record("p1.isInitialized", Statsig.isInitialized())
        record("p1.gate.on", Statsig.checkGate("always_on"))
        record("p1.gate.on.again", Statsig.checkGate("always_on"))
        record("p1.gate.off", rec(Statsig.getFeatureGate("always_off")))
        record("p1.gate.weird", rec(Statsig.getFeatureGate("weird name \"quoted\" ünï")))
        record("p1.gate.missing", rec(Statsig.getFeatureGate("nope")))
        record("p1.gate.noExposure", Statsig.checkGateWithExposureLoggingDisabled("always_on"))
        record("p1.gate.noExposureObj", rec(Statsig.getFeatureGateWithExposureLoggingDisabled("always_off")))
        record("p1.config", rec(Statsig.getConfig("test_config")))
        record("p1.config.noExposure", rec(Statsig.getConfigWithExposureLoggingDisabled("test_config")))
        record("p1.config.missing", rec(Statsig.getConfig("nope")))
        record("p1.exp", rec(Statsig.getExperiment("exp_active")))
        record("p1.exp.inactive", rec(Statsig.getExperimentWithExposureLoggingDisabled("exp_inactive")))
        record("p1.layer", rec(Statsig.getLayer("layer_one")))
        record("p1.layer.again", rec(Statsig.getLayer("layer_one"), listOf("p1", "p2")))
        record("p1.layer.noExposure", rec(Statsig.getLayerWithExposureLoggingDisabled("layer_one")))
        record("p1.layer.missing", rec(Statsig.getLayer("nope")))
        record("p1.paramStore", rec(Statsig.getParameterStore("store_one")))
        record("p1.paramStore.noExposure", rec(Statsig.getParameterStore("store_one", ParameterStoreEvaluationOptions(disableExposureLog = true))))
        record("p1.paramStore.missing", rec(Statsig.getParameterStore("nope")))
        Statsig.logEvent("plain")
        Statsig.logEvent("with_value", 1.5, mapOf("k" to "v"))
        Statsig.logEvent("with_string", "sv", mapOf("k2" to "v2"))
        Statsig.logEvent("meta_only", mapOf("m" to "1"))
        Statsig.logEvent("any_meta", 2.0, mapOf<String, Any>("n" to 3, "nested" to mapOf("x" to true)))
        Statsig.manuallyLogGateExposure("always_off")
        Statsig.manuallyLogConfigExposure("test_config")
        Statsig.manuallyLogExperimentExposure("exp_active", false)
        Statsig.manuallyLogLayerParameterExposure("layer_one", "p1", false)
        Statsig.manuallyLogGateExposure(Statsig.getFeatureGateWithExposureLoggingDisabled("always_on"))
        Statsig.manuallyLogLayerParameterExposure(Statsig.getLayerWithExposureLoggingDisabled("layer_one"), "p2")
        val ir = Statsig.getInitializeResponseJson()
        record("p1.initResponseJson", mapOf("hasJson" to (ir.getInitializeResponseJSON() != null), "details" to details(ir.getEvalDetails())))
        flushAndRecord("p1")

        // ---- Overrides (persisted) ----
        Statsig.overrideGate("always_off", true)
        Statsig.overrideConfig("test_config", mapOf("over" to "ridden"))
        Statsig.overrideConfig("exp_active", mapOf("color" to "green"))
        Statsig.overrideLayer("layer_one", mapOf("p1" to "over"))
        Statsig.overrideGate("temp", true)
        Statsig.removeOverride("temp")
        record("ov.gate", rec(Statsig.getFeatureGate("always_off")))
        record("ov.config", rec(Statsig.getConfig("test_config")))
        record("ov.exp", rec(Statsig.getExperiment("exp_active", true)))
        record("ov.layer", rec(Statsig.getLayer("layer_one")))
        record("ov.all", Statsig.getAllOverrides().let { mapOf("gates" to it.gates, "configs" to it.configs, "layers" to it.layers) })
        flushAndRecord("ov")

        // ---- Sticky experiments ----
        record("sticky.first", rec(Statsig.getExperiment("layer_exp", true)))
        record("sticky.device", rec(Statsig.getExperiment("exp_device", true)))
        record("sticky.layer", rec(Statsig.getLayer("layer_one", true), listOf("p1")))
        server.initBody = responseA(time = 1_700_000_050_000, color = "blue")
        Statsig.refreshCache()
        recordInits("sticky.refreshRequests")
        Statsig.removeOverride("exp_active")
        record("sticky.expKeep", rec(Statsig.getExperiment("exp_active", true)))
        record("sticky.expNoKeep", rec(Statsig.getExperiment("exp_active", false)))
        record("sticky.layerExpKeep", rec(Statsig.getExperiment("layer_exp", true)))
        server.initBody = responseA(time = 1_700_000_060_000, color = "purple")
        Statsig.refreshCache()
        record("sticky.stillSticky", rec(Statsig.getExperiment("exp_active", true)))
        server.initBody = responseA(time = 1_700_000_070_000, color = "orange", expActive = false)
        Statsig.refreshCache()
        record("sticky.inactiveNow", rec(Statsig.getExperiment("exp_active", true)))
        record("sticky.inactiveAgain", rec(Statsig.getExperiment("exp_active", true)))
        recordInits("sticky.moreRequests")
        flushAndRecord("sticky")

        // ---- Phase 2: switch to user B (unhashed response) ----
        server.initBody = responseB()
        Statsig.updateUser(userB())
        recordInits("p2.initRequests")
        record("p2.gate.on", rec(Statsig.getFeatureGate("always_on")))
        record("p2.gate.bOnly", rec(Statsig.getFeatureGate("b_only")))
        record("p2.config", rec(Statsig.getConfig("test_config")))
        record("p2.overrideSurvivesUser", rec(Statsig.getFeatureGate("always_off")))
        Statsig.logEvent("as_b")
        flushAndRecord("p2")

        // ---- Phase 3: back to A, server says 204/no updates ----
        server.initBody = """{"has_updates": false}"""
        Statsig.updateUser(userA())
        recordInits("p3.initRequests")
        record("p3.gate.on", rec(Statsig.getFeatureGate("always_on")))
        record("p3.config", rec(Statsig.getConfig("test_config")))
        flushAndRecord("p3")

        // ---- Phase 4: bootstrap via updateUser(values) ----
        val v2 = gson.fromJson(bootstrapV2("user-boot"), Map::class.java) as Map<String, Any>
        Statsig.updateUser(StatsigUser("user-boot"), v2)
        record("p4.v2.gate", rec(Statsig.getFeatureGate("boot_gate")))
        record("p4.v2.config", rec(Statsig.getConfig("boot_config")))
        record("p4.v2.missing", rec(Statsig.getFeatureGate("always_on")))
        val v1 = gson.fromJson(bootstrapV1("someone-else"), Map::class.java) as Map<String, Any>
        Statsig.updateUser(StatsigUser("user-boot2").apply { customIDs = mapOf("companyID" to "c1") }, v1)
        record("p4.v1.invalid", rec(Statsig.getFeatureGate("boot_gate")))
        val v1ok = gson.fromJson(bootstrapV1("user-boot3"), Map::class.java) as Map<String, Any>
        Statsig.updateUser(StatsigUser("user-boot3").apply { customIDs = mapOf("companyID" to "c1", "stableID" to "ignored") }, v1ok)
        record("p4.v1.valid", rec(Statsig.getFeatureGate("boot_gate")))
        flushAndRecord("p4")
        recordInits("p4.initRequests")

        // ---- Runtime options: logging disabled keeps events queued ----
        Statsig.updateRuntimeOptions(StatsigRuntimeMutableOptions(loggingEnabled = false))
        Statsig.logEvent("while_disabled")
        flushAndRecord("rt.disabled")
        Statsig.updateRuntimeOptions(StatsigRuntimeMutableOptions(loggingEnabled = true, eventLoggingAPI = server.api))
        flushAndRecord("rt.enabled")

        // ---- Failed log posts are persisted and retried on the next start ----
        server.logStatus = 500
        Statsig.logEvent("will_fail")
        flushAndRecord("fail.first")
        // Non-retryable status: the batch is stored and re-sent on the next start.
        server.logStatus = 400
        Statsig.logEvent("will_persist")
        flushAndRecord("fail.persist")
        server.logStatus = 200

        settle()
        Statsig.shutdownSuspend()
        record("shutdown.isInitialized", Statsig.isInitialized())
        record("shutdown.checkGate", runCatching { Statsig.checkGate("always_on") }.exceptionOrNull()?.javaClass?.simpleName)
        record("shutdown.events", server.drainLogs().flatMap { normalizeLogRequest(it) })

        // ---- Phase 5: restart as A with the network down -> cache ----
        server.initStatus = 500
        val init5 = Statsig.initialize(app, "client-harness", userA(), options())
        record("p5.init", rec(init5))
        recordInits("p5.initRequests")
        record("p5.sameStableId", stableId() == stable)
        record("p5.gate.on", rec(Statsig.getFeatureGate("always_on")))
        record("p5.config", rec(Statsig.getConfig("test_config")))
        record("p5.overridesPersisted", rec(Statsig.getFeatureGate("always_off")))
        record("p5.stickyPersisted", rec(Statsig.getExperiment("layer_exp", true)))
        record("p5.deviceStickyPersisted", rec(Statsig.getExperiment("exp_device", true)))
        Statsig.removeAllOverrides()
        record("p5.afterRemoveAll", rec(Statsig.getFeatureGate("always_off")))
        settle()
        record("p5.retriedLogs", server.drainLogs().flatMap { normalizeLogRequest(it) })
        flushAndRecord("p5")
        Statsig.shutdownSuspend()

        // ---- Phase 6: unknown user, network down -> no values ----
        val init6 = Statsig.initialize(app, "client-harness", StatsigUser("user-new"), options())
        record("p6.init", rec(init6))
        recordInits("p6.initRequests")
        record("p6.gate.on", rec(Statsig.getFeatureGate("always_on")))
        record("p6.layer", rec(Statsig.getLayer("layer_one"), listOf("p1")))
        flushAndRecord("p6")
        Statsig.shutdownSuspend()

        // ---- Phase 7: initializeAsync + offline for A ----
        val latch = CountDownLatch(1)
        var asyncDetails: InitializationDetails? = null
        Statsig.initializeAsync(
            app, "client-harness", userA(),
            object : IStatsigCallback {
                override fun onStatsigInitialize(initDetails: InitializationDetails) {
                    asyncDetails = initDetails
                    latch.countDown()
                }

                override fun onStatsigUpdateUser() {}
            },
            options { initializeOffline = true }
        )
        latch.await(10, TimeUnit.SECONDS)
        record("p7.init", rec(asyncDetails))
        recordInits("p7.initRequests")
        record("p7.gate.on", rec(Statsig.getFeatureGate("always_on")))
        flushAndRecord("p7")
        Statsig.shutdownSuspend()

        // ---- Phase 8: on-device evaluation adapter ----
        server.initStatus = 200
        server.initBody = responseA()
        val adapter = OnDeviceEvalAdapter(DcsFixture.json(1_800_000_000_000))
        val users = (0..9).map { i ->
            StatsigUser("user-$i").apply {
                email = listOf("a@example.com", "x@corp.com", "B@X.IO", null, "c@corp.com")[i % 5]
                appVersion = listOf("2.10.2", "1.2.0", "0.9", "1.0.0-rc1", "2.10.1", null)[i % 6]
                country = listOf("US", "NG", null)[i % 3]
                custom = mapOf(
                    "level" to listOf(1, 2.0, 3, "4", 6.5, "abc")[i % 6],
                    "tier" to listOf("Bronze", "bronze", "gold", "xenon")[i % 4],
                    "beta" to (i % 2 == 0),
                    "signupAt" to listOf("2024-03-05T23:59:59.000Z", 1709633000000L, "1709633000", "garbage")[i % 4]
                )
                customIDs = mapOf("companyID" to "c${i % 4}")
            }
        }
        users.forEachIndexed { i, u ->
            val init = Statsig.initialize(app, "client-harness", u, options { onDeviceEvalAdapter = adapter })
            if (i == 0) record("p8.init", rec(init))
            val gates = DcsFixture.gates.map { it["name"] as String } + "dcs_missing"
            record("p8.user$i.gates", gates.associateWith { rec(Statsig.getFeatureGate(it)) })
            record("p8.user$i.config", rec(Statsig.getConfig("dcs_config")))
            record("p8.user$i.experiment", rec(Statsig.getExperiment("dcs_experiment")))
            record("p8.user$i.layer", rec(Statsig.getLayer("dcs_layer"), listOf("color", "size")))
            record("p8.user$i.store", Statsig.getParameterStore("dcs_store").let {
                mapOf("details" to details(it.getEvalDetails()), "p_gate" to it.getBoolean("p_gate", false), "p_static" to it.getString("p_static", null), "keys" to it.getKeys().sorted())
            })
            record("p8.user$i.fallsBackToNetworkForOld", rec(Statsig.getFeatureGate("always_on")))
            if (i < 2) flushAndRecord("p8.user$i") else { settle(); Statsig.flush(); server.drainLogs() }
            Statsig.shutdownSuspend()
            server.drainInits()
        }

        record("callbacks", synchronized(callbacks) { callbacks.toList() })
    }
}

fun main(args: Array<String>) {
    Dispatchers.setMain(Executors.newSingleThreadExecutor { r -> Thread(r, "main").apply { isDaemon = true } }.asCoroutineDispatcher())
    val scenario = Scenario()
    runBlocking(Dispatchers.IO) { scenario.run() }
    scenario.server.close()
    val out = File(args[0])
    out.parentFile.mkdirs()
    out.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(scenario.transcript))
    println("wrote ${scenario.transcript.size} entries to $out")
    System.exit(0)
}
