# JVM harness

Builds and runs the SDK on a plain JVM so it can be tested and benchmarked without the Android
SDK or Google Maven (neither is reachable from the environment this was built in).

* Android framework classes come from Robolectric's `android-all` jar (Maven Central) at compile
  time. At run time `:shims` provides JVM versions of the few that are native on a device
  (`Log`, `SystemClock`, `Build`, `ConnectivityManager`) and an in-memory `SharedPreferences`.
* `:baseline` compiles the **original** SDK straight from git (the repository's first commit,
  `-PbaselineCommit=<sha>` to change it). Its DataStore storage class is replaced by a stub
  because androidx.datastore is Google-Maven-only; the default SharedPreferences storage, which
  the benchmark uses, is untouched.
* `:sqlsdk` compiles **this** SDK (`../src/main`) and runs its SQL on the SQLite that
  [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) bundles (`-PsqliteJdbc=org.xerial:sqlite-jdbc:<v>`
  to try another SQLite version) instead of Android's SQLite. Its `SqlDriver`
  (`JdbcSqlDriver`) opens the database through JDBC but runs statements through SQLite's own
  statement API (prepare once, bind, step, read columns, reset), as Android's framework binding
  does; the JDBC wrapper's per-call bookkeeping cost several microseconds per statement.

Both projects compile the same public-API-only sources in `scenario/src`:

| Task | What it does |
| --- | --- |
| `gradle :baseline:scenario :sqlsdk:scenario` | Runs `Scenario.kt` and writes `build/scenario.json`: every return value, evaluation detail, request and logged event of a long script of API calls. |
| `python3 compare_transcripts.py golden/baseline-scenario.json sqlsdk/build/scenario.json golden/expected-differences.json` | Diffs the SQL SDK's transcript against the original's (`golden/`). Documented, intentional differences are listed in `golden/expected-differences.json`. |
| `gradle :baseline:bench :sqlsdk:bench` | Runs `Benchmark.kt` (median of 7 rounds after warm-up) and writes `build/bench.json`. The JVM pre-touches its 1 GB heap (otherwise first-touch page faults slow allocation-heavy loops of either SDK for seconds). `-PbenchJvmArgs="-Dbench.only=<text>"` measures only the matching cases; other JVM flags (e.g. `-XX:StartFlightRecording=...`) go there too. |
| `python3 bench_table.py baseline/build/bench.json sqlsdk/build/bench.json` | The before/after table. |
| `gradle :sqlsdk:probe -Pmain=<class>` | Runs any `main` from the test classpath (ad-hoc measurements). |
| `gradle :sqlsdk:test` | Unit tests for the SQL: hashing and hashed lookups, eviction, sticky values, retention, de-duplication, event JSON, the host cache / deferred / quiet directives, fallback urls, the script parser, concurrency. |

Run from this directory with Gradle 8.13+ and JDK 21 (e.g. `../gradlew -p . :sqlsdk:test`).
