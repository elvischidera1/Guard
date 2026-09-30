package harness

import com.statsig.androidsdk.HttpUtils
import com.statsig.androidsdk.StatsigClient
import com.statsig.androidsdk.sql.JdbcSqlDriver

/** Runs the SQL SDK on the JVM: SQLite through JDBC, files under the HarnessApp directory. */
object Env {
    fun install(app: HarnessApp) {
        HttpUtils.okHttpClient = fastHttpClient()
        StatsigClient.sqlDriverFactory = { application ->
            JdbcSqlDriver(application.getDatabasePath("statsig.db").path)
        }
    }
}
