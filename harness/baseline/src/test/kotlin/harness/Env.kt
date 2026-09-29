package harness

import com.statsig.androidsdk.HttpUtils

/** The original SDK needs no environment setup beyond HarnessApp and a loopback-friendly client. */
object Env {
    fun install(app: HarnessApp) {
        HttpUtils.okHttpClient = fastHttpClient()
    }
}
