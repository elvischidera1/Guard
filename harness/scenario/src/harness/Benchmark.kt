package harness

import com.statsig.androidsdk.OnDeviceEvalAdapter
import com.statsig.androidsdk.Statsig
import com.statsig.androidsdk.StatsigOptions
import com.statsig.androidsdk.StatsigUser
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/**
 * Public-API micro benchmarks. The same source is compiled against the original SDK and the SQL
 * re-implementation. Each case reports the median of several timed rounds after warm-up.
 */
object Bench {
    private const val ROUNDS = 7
    val results = LinkedHashMap<String, Map<String, Any>>()

    /** -Dbench.only=<text>: only measure cases whose name contains it (setup still runs). */
    val only: String? = System.getProperty("bench.only")

    inline fun measure(name: String, opsPerRound: Int, warmupRounds: Int = 3, crossinline body: (round: Int) -> Unit) {
        if (only != null && !name.contains(only)) return
        repeat(warmupRounds) { body(-1 - it) }
        val perOp = (0 until 7).map { round ->
            val start = System.nanoTime()
            body(round)
            (System.nanoTime() - start).toDouble() / opsPerRound
        }.sorted()
        val median = perOp[perOp.size / 2]
        results[name] = mapOf("ns_per_op" to median, "min" to perOp.first(), "max" to perOp.last(), "ops" to opsPerRound)
        println(String.format("%-48s %12.0f ns/op  (min %.0f, max %.0f)", name, median, perOp.first(), perOp.last()))
    }
}

private fun largeResponse(n: Int) = initResponse(
    1_700_000_000_000,
    gates = (0 until n).map { gate("gate_$it", it % 2 == 0, "rule_$it", "group_$it", listOf(depExposure)) },
    configs = (0 until n).map {
        config("config_$it", mapOf("i" to it, "s" to "value_$it", "nested" to mapOf("a" to listOf(1, 2, 3))), "cfg_rule_$it", active = true, inExperiment = true)
    },
    layers = (0 until n / 10).map { config("layer_$it", mapOf("p" to it, "q" to "x"), "layer_rule_$it") },
    paramStores = paramStore()
)

fun main(args: Array<String>) {
    Dispatchers.setMain(Executors.newSingleThreadExecutor { r -> Thread(r, "main").apply { isDaemon = true } }.asCoroutineDispatcher())
    val server = FakeServer()
    fun options(configure: StatsigOptions.() -> Unit = {}) =
        StatsigOptions(api = server.api, eventLoggingAPI = server.api, sdkErrorAPI = server.api).apply(configure)
    val user = StatsigUser("bench-user").apply { email = "b@example.com"; custom = mapOf("level" to 3.0) }
    val small = responseA()
    val large = largeResponse(2000)

    runBlocking(Dispatchers.IO) {
        // --- Initialization ---
        server.initBody = small
        Bench.measure("initialize (network, fresh install)", 1, warmupRounds = 3) {
            val app = HarnessApp(); Env.install(app)
            runBlocking { Statsig.initialize(app, "client-bench", user, options()); Statsig.shutdownSuspend() }
        }
        val warmApp = HarnessApp(); Env.install(warmApp)
        Bench.measure("initialize (network, warm cache)", 1) {
            runBlocking { Statsig.initialize(warmApp, "client-bench", user, options()); Statsig.shutdownSuspend() }
        }
        Bench.measure("initialize (offline, from cache)", 1) {
            runBlocking { Statsig.initialize(warmApp, "client-bench", user, options { initializeOffline = true }); Statsig.shutdownSuspend() }
        }
        server.initBody = large
        Bench.measure("initialize (network, 2000 gates+2000 configs)", 1, warmupRounds = 2) {
            val app = HarnessApp(); Env.install(app)
            runBlocking { Statsig.initialize(app, "client-bench", user, options()); Statsig.shutdownSuspend() }
        }
        val largeApp = HarnessApp(); Env.install(largeApp)
        Statsig.initialize(largeApp, "client-bench", user, options()); Statsig.shutdownSuspend()
        Bench.measure("initialize (offline, 2000+2000 from cache)", 1, warmupRounds = 2) {
            runBlocking { Statsig.initialize(largeApp, "client-bench", user, options { initializeOffline = true }); Statsig.shutdownSuspend() }
        }

        // --- Hot-path reads on a small payload ---
        server.initBody = small
        val app = HarnessApp(); Env.install(app)
        Statsig.initialize(app, "client-bench", user, options())
        val n = 20_000
        Bench.measure("checkGate (hit, deduped exposure)", n) { repeat(n) { Statsig.checkGate("always_on") } }
        Bench.measure("checkGate (miss)", n) { repeat(n) { Statsig.checkGate("not_a_gate") } }
        Bench.measure("checkGateWithExposureLoggingDisabled", n) { repeat(n) { Statsig.checkGateWithExposureLoggingDisabled("always_on") } }
        Bench.measure("getConfig + getString", n) { repeat(n) { Statsig.getConfig("test_config").getString("string", "") } }
        Bench.measure("getExperiment(keepDeviceValue=true)", n) { repeat(n) { Statsig.getExperiment("exp_active", true) } }
        Bench.measure("getLayer + getString (param exposure)", n) { repeat(n) { Statsig.getLayer("layer_one").getString("p1", "") } }
        Bench.measure("getParameterStore + getString(static)", n) { repeat(n) { Statsig.getParameterStore("store_one").getString("static_str", "") } }
        Bench.measure("getParameterStore + getString(gate ref)", n) { repeat(n) { Statsig.getParameterStore("store_one").getString("gate_str", "") } }
        Bench.measure("overridden gate", n, warmupRounds = 3) { r ->
            if (r == -1) Statsig.overrideGate("always_off", true)
            repeat(n) { Statsig.checkGate("always_off") }
        }
        Statsig.removeAllOverrides()

        // --- Writes ---
        var unique = 0
        val m = 2_000
        Bench.measure("checkGate (unique names -> new exposure each)", m) {
            repeat(m) { Statsig.checkGate("g${unique++}") }
            runBlocking { withContext(Dispatchers.Main) {}; Statsig.flush() }
        }
        Bench.measure("logEvent x2000 + flush", m) {
            repeat(m) { i -> Statsig.logEvent("bench_event", i.toDouble(), mapOf("k" to "v$i")) }
            runBlocking { withContext(Dispatchers.Main) {}; Statsig.flush() }
        }
        server.drainLogs()
        var flip = false
        val bootA = gson.fromJson(bootstrapV1("boot-a"), Map::class.java) as Map<String, Any>
        val bootB = gson.fromJson(bootstrapV1("boot-b"), Map::class.java) as Map<String, Any>
        Bench.measure("updateUser(values) bootstrap switch", 200) {
            repeat(200) {
                flip = !flip
                runBlocking {
                    if (flip) Statsig.updateUser(StatsigUser("boot-a").apply { customIDs = mapOf("companyID" to "c1") }, bootA)
                    else Statsig.updateUser(StatsigUser("boot-b").apply { customIDs = mapOf("companyID" to "c1") }, bootB)
                }
            }
        }
        Bench.measure("updateUser (network) switch between 2 users", 20) {
            repeat(20) {
                flip = !flip
                runBlocking { Statsig.updateUser(if (flip) user else StatsigUser("other-user")) }
            }
        }
        Statsig.shutdownSuspend()

        // --- Hot-path reads on a large payload ---
        server.initBody = large
        val bigApp = HarnessApp(); Env.install(bigApp)
        Statsig.initialize(bigApp, "client-bench", user, options())
        Bench.measure("checkGate on 2000-gate payload (hit)", n) { repeat(n) { i -> Statsig.checkGate("gate_${i % 2000}") } }
        Bench.measure("getConfig on 2000-config payload", n) { repeat(n) { i -> Statsig.getConfig("config_${i % 2000}").getInt("i", 0) } }
        Statsig.shutdownSuspend()

        // --- On-device evaluation ---
        server.initBody = small
        val dcsApp = HarnessApp(); Env.install(dcsApp)
        val adapter = OnDeviceEvalAdapter(DcsFixture.json(1_800_000_000_000))
        Statsig.initialize(dcsApp, "client-bench", StatsigUser("user-1").apply { email = "x@corp.com"; custom = mapOf("level" to 2.0) }, options { onDeviceEvalAdapter = adapter })
        val k = 2_000
        Bench.measure("on-device checkGate (public rule)", k) { repeat(k) { Statsig.checkGate("dcs_public") } }
        Bench.measure("on-device checkGate (nested pass_gate)", k) { repeat(k) { Statsig.checkGate("dcs_nested") } }
        Bench.measure("on-device checkGate (bucketing/sha256)", k) { repeat(k) { Statsig.checkGate("dcs_bucket") } }
        Bench.measure("on-device getLayer (delegated experiment)", k) { repeat(k) { Statsig.getLayer("dcs_layer").getString("color", "") } }
        Statsig.shutdownSuspend()
    }
    server.close()
    File(args[0]).apply { parentFile.mkdirs() }.writeText(gson.toJson(Bench.results))
    System.exit(0)
}
