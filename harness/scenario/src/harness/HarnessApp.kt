package harness

import android.app.Application
import android.content.Context
import android.content.InMemorySharedPreferences
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.nio.file.Files

/**
 * A JVM-friendly Application. One instance models one installed app: its storage (shared prefs
 * for the original SDK, the files dir for the SQLite database) survives across SDK restarts.
 */
class HarnessApp(val dir: File = Files.createTempDirectory("statsig-harness").toFile()) :
    Application() {
    private val prefs = HashMap<String, InMemorySharedPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        synchronized(prefs) { prefs.getOrPut(name) { InMemorySharedPreferences() } }

    override fun getSystemService(name: String): Any? = when (name) {
        Context.CONNECTIVITY_SERVICE ->
            Class.forName("android.net.ConnectivityManager").getDeclaredConstructor().newInstance()
        else -> null
    }

    override fun getPackageManager(): PackageManager? = null
    override fun getPackageName(): String = "harness.app"
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(dir, "databases").apply { mkdirs() }, name)
    override fun getApplicationInfo(): ApplicationInfo =
        ApplicationInfo().apply { processName = "harness.app" }
}
