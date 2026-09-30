package harness.probe

import com.statsig.androidsdk.Statsig
import com.statsig.androidsdk.StatsigOptions
import com.statsig.androidsdk.StatsigUser
import harness.Env
import harness.FakeServer
import harness.HarnessApp
import harness.responseA
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain

fun main() {
    Dispatchers.setMain(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    val server = FakeServer(); server.initBody = responseA()
    val app = HarnessApp(); Env.install(app)
    runBlocking(Dispatchers.IO) {
        Statsig.initialize(app, "client-probe", StatsigUser("u"), StatsigOptions(api = server.api, eventLoggingAPI = server.api))
        repeat(300_000) { Statsig.checkGate("always_on") }
    }
    System.exit(0)
}
