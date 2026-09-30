# Statsig Android SDK, re-implemented as SQL — report

This repository started as an unmodified copy of
[statsig-io/android-sdk](https://github.com/statsig-io/android-sdk) at `507d86a` (the first
commit here). Its internal logic has since been re-implemented as SQL that runs in SQLite. What is
left in Kotlin is the public API, the model classes, and the I/O that SQL cannot do.

**TL;DR**

- **Portability.** Every decision the SDK makes now lives in about 1,700 lines of plain SQL: which
  values apply, sticky experiments, overrides, caching and eviction, exposure de-duplication, the
  event queue, diagnostics, retry storage, fallback URLs, name hashing and the whole on-device rules
  engine. Another platform ports it by running the same `.sql` files through its SQLite binding.
- **Behavior.** It matches the original SDK: a public-API script of 337 observations gives the same
  results on both SDKs, except one diagnostics event where the original has a bug (fixed here).
- **Performance.** It is slower. Hot-path reads (`checkGate`, `getConfig`) went from under 1 µs to
  30–50 µs. `initialize` is about 2–5× slower. On-device evaluation went from a few µs to
  0.4–1.1 ms. Details and mitigations are [below](#benchmark-before-and-after).
- **Compatibility.** The SQL needs SQLite 3.38+, which Android ships from API 34 (Android 14). Older
  devices need a bundled SQLite, which `StatsigClient.sqlDriverFactory` lets an app plug in.

## Architecture

```
            before                                          after
┌───────────────────────────────┐         ┌───────────────────────────────────────────┐
│ Statsig / StatsigClient (API) │         │ Statsig / StatsigClient (API, same shape) │
├───────────────────────────────┤         ├───────────────────────────────────────────┤
│ Store, StatsigLogger,         │         │ StatsigDb: runs named SQL blocks          │
│ Diagnostics, KeyValueStorage  │   ──►   │   src/main/resources/.../sql/*.sql        │
│ (SharedPreferences/DataStore),│         ├───────────────────────────────────────────┤
│ NetworkFallbackResolver,      │         │ Platform I/O only: HTTP (OkHttp), timers, │
│ evaluator/*, Hashing, ...     │         │ lifecycle, connectivity, regex, debug UI  │
└───────────────────────────────┘         └───────────────────────────────────────────┘
```

### The SQL (`src/main/resources/com/statsig/androidsdk/sql/`)

| File | Lines | Replaces | What it holds |
| --- | ---: | --- | --- |
| `00_schema.sql` | ~150 | `KeyValueStorage`, in-memory state | File format, durable tables (`main`), per-client tables (`temp`) |
| `01_hashing.sql` | ~160 | `Hashing`, `BoundedMemo` | DJB2, SHA-256 + base64, and bucketing, in plain SQL (recursive CTEs), memoized |
| `02_values.sql` | ~500 | `Store`, `BootstrapValidator`, `BootstrapMetadata`, `InitializeResponseFormatter` | Sessions, cache load/save with 10-entry LRU, bootstrap (v1 and compact init-v2), lookups, sticky experiments, overrides, legacy import |
| `03_logging.sql` | ~230 | `StatsigLogger`, `Diagnostics`, offline part of `StatsigNetwork` | Event queue (flush at 50, cap 1000), 10-minute exposure de-duplication (triggers), non-exposed counts, diagnostics markers, failed-batch retention |
| `04_network.sql` | ~95 | `StatsigNetwork` (bodies), `NetworkFallbackResolver` | Initialize request bodies, gzip decision, fallback-URL expiry/rotation, DNS cooldown, TXT-record parsing |
| `05_evaluator.sql` | ~600 | `evaluator/Evaluator`, `EvaluatorUtils`, `SpecStore` | The on-device rules engine: every condition type and operator, nested gates, segments, delegation, pass percentages, unsupported → default |

**Conventions.** These keep the SQL portable and the host code trivial:

- `-- name: <block>` starts a named block of statements. A block runs atomically; it is a
  transaction when it writes more than once. The rows of its last row-returning statement are its
  result.
- Parameters are `:name`. Values are bound as integer, real, text or NULL.
- `main.*` tables hold what the original kept in SharedPreferences.
- `temp.*` tables hold what it kept in memory. Each client owns a connection, so TEMP tables give
  every client its own session state with no extra code.
- Triggers carry the rules that should hold for every write:
  - the exposure de-duplication window,
  - the event-queue cap,
  - applying a new set of values,
  - computing hashes on demand.

**How a host drives it.** A host ports the SDK by running these blocks and moving bytes. For
`checkGate`:

1. Run `get_value` and turn its row into a `FeatureGate`.
2. Run `log_gate_exposure` with the gate's fields.
3. If that returns `should_flush`, run `take_batch` and POST the body it returns.

On-device evaluation is the same idea over several blocks: `eval_begin`, then fulfil `regex_request`
rows, then `eval_conditions`, then `eval_step` until it reports no progress, then `eval_result`.

### What stays in Kotlin, and why

| Kotlin | Why it is not SQL |
| --- | --- |
| Public API (`Statsig`, `StatsigClient`) and models (`FeatureGate`, `DynamicConfig`, `Layer`, `ParameterStore`, `StatsigUser`, `StatsigOptions`, ...) | The user-facing surface; kept source- and binary-compatible. |
| `Rows.kt` | Maps result columns to model constructors; makes no decisions. |
| `sql/` (`SqlScript`, `StatsigDb`, `SqlDriver`, `AndroidSqlDriver`) | Loads the SQL, binds parameters, and serializes access to one connection. |
| `StatsigNetwork.kt`, `HttpUtils`, `DnsTxtQuery` | HTTP, gzip, timeouts, retry/backoff schedule, DNS-over-HTTPS, and binary DNS response parsing. |
| `ErrorBoundary`, coroutines, flush timer, polling, lifecycle and connectivity listeners, `DebugView` | Host runtime concerns. |
| Regex for `str_matches` | SQLite has no portable regex. The SQL lists `(pattern, value)` pairs in `regex_request` and the host answers them. |

**Size.**

- **Before:** 9,561 lines of Kotlin in `src/main`.
- **After:** 4,903 lines of Kotlin (1,026 of them `StatsigClient`, whose public methods and
  KDoc are the bulk) plus 1,689 lines of SQL.
- **Deleted outright** (about 6,100 lines):
  - `Store`, `StatsigLogger`, `KeyValueStorage` (the SharedPreferences and DataStore backends)
  - `Diagnostics`, `NetworkFallbackResolver`
  - `InitializeResponseFormatter`, `BootstrapValidator`, `BootstrapMetadata`
  - `Hashing`, `BoundedMemo`, `ExposureKey`, `IntegratedSdkExperiments`
  - the `evaluator` package
- **Dependencies dropped:** `androidx.datastore`, `androidx.core`, `androidx.appcompat`,
  `annotation-experimental`.

## How it was verified

The Android SDK and Google Maven were unreachable from the build environment. So everything was
built and run on the JVM through `harness/` (see [harness/README.md](harness/README.md)):

- The SDK compiles against the real Android API jar, which Robolectric publishes to Maven Central.
- It runs against small shims for the framework classes that are native on a device.
- The **original** SDK is compiled straight from the first commit.
- The **new** SDK runs its SQL through sqlite-jdbc.

1. **Golden public-API transcript.**
   - `harness/scenario/src/harness/Scenario.kt` drives either SDK only through its public API,
     against a local fake Statsig server. It records every return value, evaluation detail, request
     body and logged event into 337 entries.
   - What it covers:
     - gates, configs, experiments, layers and parameter stores (all reference types)
     - overrides, sticky experiments across value updates and restarts
     - user switches, `204 Not Modified`, and bootstrap (v1, init-v2, SHA-256-keyed and legacy)
     - evaluated-keys validation, runtime options
     - failed-log persistence and retry
     - offline, cached and no-cache starts
     - on-device evaluation for 40 users with varied attributes across 16 gates, a config,
       an experiment, a delegating layer and a parameter store
   - The transcript recorded from the original SDK is checked in as
     `harness/golden/baseline-scenario.json`.
   - The SQL SDK matches it on **336 of 337 entries**. The remaining one is listed with its reason
     in `harness/golden/expected-differences.json` (see below).
2. **SQL unit tests** (`gradle -p harness :sqlsdk:test`, 18 tests):
   - hashing against the platform's DJB2/SHA-256 over random Unicode
   - LRU eviction, sticky values, and responses for a stale user
   - failed-log retention, exposure de-duplication, and the queue's flush/cap
   - fallback URLs, the script parser, and concurrency (8 threads)
   - regression tests for the review findings
3. **SQLite versions.**
   - The unit tests and the golden transcript pass on SQLite **3.50.3** and on **3.39.2**, the
     version Android 14 ships.
   - On 3.36 the SQL fails to parse (`->` JSON operators), which confirms the documented floor.
4. **Review passes.**
   - First pass: the golden diff and profiling. Profiling drove typed entity columns,
     single-statement lookups, in-memory TEMP storage and a materialized evaluation context.
   - Second pass: two independent reviews, one of the SQL against the original Kotlin semantics
     and one of the Kotlin for thread safety, lifecycle, error handling and API compatibility.
   - Every confirmed finding was fixed, with a regression test where the harness could express
     it. Main fixes:
     - failure paths that could throw into the app
     - a background retry that could crash the process
     - an O(n²) init-v2 decoder
     - a hash-memo trim that could break a huge evaluation
     - `HAVING` without `GROUP BY` (which needs SQLite 3.39)
     - version-independent number parsing
     - stable-ID continuity for upgrading installs
     - a public driver hook
   - Third pass: ktlint and a re-run of all of the above after the fixes.

## Benchmark before and after

`harness/scenario/src/harness/Benchmark.kt` uses only the public API, so both SDKs run the same
code.

- Each number is the median of 7 timed rounds, after warm-up.
- The two runs were made back to back on the same machine: a shared 4-vCPU cloud VM, JDK 21.
  Absolute numbers moved by up to ±30% between repeated runs on this VM. Ratios within about 1.5×
  are within noise.
- Network calls go to a local fake server with Nagle's algorithm disabled, so they measure the
  SDK, not the network.
- The original stores in SharedPreferences. The harness's SharedPreferences is purely in memory, so
  the original's storage cost is, if anything, understated.
- The SQL SDK uses a file-backed SQLite database through sqlite-jdbc.

| Benchmark (median per operation) | Original | SQL | Ratio |
| --- | ---: | ---: | ---: |
| initialize (network, fresh install) | 17.5 ms | 40.8 ms | 2.3× |
| initialize (network, warm cache) | 13.4 ms | 30.1 ms | 2.3× |
| initialize (offline, from cache) | 6.4 ms | 21.9 ms | 3.4× |
| initialize (network, 2000 gates+2000 configs) | 56.7 ms | 148.4 ms | 2.6× |
| initialize (offline, 2000+2000 from cache) | 19.2 ms | 100.8 ms | 5.3× |
| checkGate (hit, deduped exposure) | 694 ns | 44.6 µs | 64.3× |
| checkGate (miss) | 489 ns | 38.7 µs | 79.2× |
| checkGateWithExposureLoggingDisabled | 588 ns | 29.7 µs | 50.5× |
| getConfig + getString | 957 ns | 52.7 µs | 55.1× |
| getExperiment(keepDeviceValue=true) | 20.8 µs | 86.4 µs | 4.1× |
| getLayer + getString (param exposure) | 546 ns | 106.3 µs | 194.8× |
| getParameterStore + getString(static) | 405 ns | 34.5 µs | 85.2× |
| getParameterStore + getString(gate ref) | 500 ns | 82.3 µs | 164.8× |
| overridden gate | 174 ns | 38.8 µs | 223.0× |
| checkGate (unique names -> new exposure each) | 22.8 µs | 194.2 µs | 8.5× |
| logEvent x2000 + flush | 27.0 µs | 173.8 µs | 6.4× |
| updateUser(values) bootstrap switch | 221.6 µs | 222.1 µs | 1.0× |
| updateUser (network) switch between 2 users | 2.4 ms | 3.9 ms | 1.6× |
| checkGate on 2000-gate payload (hit) | 1.0 µs | 47.1 µs | 45.7× |
| getConfig on 2000-config payload | 1.1 µs | 48.2 µs | 45.2× |
| on-device checkGate (public rule) | 1.7 µs | 371.5 µs | 218.9× |
| on-device checkGate (nested pass_gate) | 8.3 µs | 1.1 ms | 126.8× |
| on-device checkGate (bucketing/sha256) | 1.8 µs | 439.2 µs | 238.6× |
| on-device getLayer (delegated experiment) | 3.6 µs | 743.4 µs | 204.0× |

**Reading the numbers**

- **Hot-path reads are 50–200× slower (about 30–100 µs instead of under 1 µs).**
  - Examples: `checkGate`, `getConfig`, layer and parameter-store reads.
  - The original answers these from a `HashMap`. Now each call is 2–4 SQLite statements: a lookup,
    then an exposure insert that a trigger de-duplicates.
  - Profiling shows the time is in SQLite's own `step`, not in JDBC or Kotlin. Two JDBC artifacts
    were found and removed on the way: generated-key lookups, and re-prepared `BEGIN`/`COMMIT`.
  - In absolute terms a check costs tens of µs, i.e. tens of thousands of checks per second.
    That's fine for app UIs, which check a handful of gates per screen, but it is a real
    regression for code that checks gates in tight loops.
- **Write-heavy paths lose much less.**
  - Sticky `getExperiment` is about 4× slower; the original also paid for a write here (it
    re-serialized the user's whole cache on every sticky read).
  - New exposures and `logEvent` + flush are 6–9× slower.
  - Bootstrap `updateUser(values)` is on par.
- **Initialization is about 2–5× slower.** Setup creates the schema on a fresh connection and
  applies payloads with `json_each`. Large payloads (2,000 gates + 2,000 configs) cost more to
  apply than Gson took to parse, but `initialize`
  stays in the tens to low hundreds of milliseconds (40 ms fresh, 150 ms for the large payload).
- **On-device evaluation is the most expensive part.**
  - It went from a few µs to 0.4–1.1 ms per check, because each evaluation is several set-based
    SQL passes. That is after a first optimization pass made it 4–5× faster.
  - SHA-256 written in SQL costs about 0.5–1 ms per new input. It is memoized, and only the rules
    that decide the result are hashed.
- **Where the difference really comes from:** an interpreted query engine behind a
  `synchronized` connection versus hand-written Kotlin on in-memory maps.

**If read latency matters, options that keep the design:**

- Cache model objects per (entity, values generation) in the host, invalidated by the value-apply
  trigger's generation.
- Combine `get_value` and `log_*_exposure` into one block, one round trip per check.
- On Android use a bundled SQLite (newer, JSONB, faster JSON).
- Run exposure logging off the calling thread, as the original did.

## Behavior differences

**Intentional (bug fixes):**

- **`update_user` diagnostics.** The original never clears `update_user` diagnostics markers.
  After a few `updateUser` calls it hits its 30-marker cap and re-sends the same stale markers on
  every update. The SQL clears markers once they are logged. This is the one entry that differs in
  the golden transcript.
- **Fallback-URL "timed out".** In the original, a request without a timeout counted as timed
  out whenever it took over 0 ms. On a connected device, every log post therefore looked like a
  domain failure and could switch logging to a DNS-discovered fallback host. Now a timeout only
  counts when one was set.
- **Malformed `200` initialize response.** A response body that is not JSON is treated as an
  ordinary failed response, and polling and log retries still start.

**Simplifications, documented rather than replicated:**

- **Overrides** apply per client and persist immediately (the original persisted asynchronously).
  Clients created afterwards see them.
- **Case-insensitive operators** (`any`, `str_contains_any`, ...) fold ASCII case only. Dates in
  `on`, and ISO timestamps, are compared in UTC; the original used the JVM default time zone.
- **Equality on numbers** (`eq`/`neq`) compares JSON number types. The original compared boxed
  Kotlin types, so `Int` vs `Long` differed.
- **A gate that references itself** in a rule the original never reaches evaluates as
  unrecognized instead of recursing.
- **Diagnostics marker timestamps** are wall-clock milliseconds, not `elapsedRealtime`.
- **`getInitializeResponseJson()`** returns the values in use rebuilt in the v1 response shape.
- **Upgrade from earlier SDK versions:**
  - The stable ID, local overrides and device-level sticky experiments are imported once from the
    SharedPreferences-based SDK.
  - Cached values are not; they are fetched again.
  - Data in the experimental DataStore backend is not imported.
- **The storage-migration experiment** (`IntegratedSdkExperiments`) is removed, since storage is
  now SQLite.
- **In-flight initialize requests** for the same user are no longer cancelled when a newer one
  starts. Responses for a user the session has left are still ignored.

**Public API:**

- Every user-facing class and method keeps its signature.
- `UnsupportedEvaluationException` moved packages; a typealias keeps the old name.
- **Removed:** accidentally-public internals (`BootstrapValidator`, `ExposureKey`,
  `FallbackInfoEntry`, a few constants and helpers, the DataStore storage class and its test hooks).
- **Added:** `StatsigClient.sqlDriverFactory` and the `SqlDriver` interface.

## Limitations and next steps

- **Not run on a device.**
  - The Android build (AGP) could not run here, so `AndroidSqlDriver` is compiled against the
    Android API but not executed.
  - It relies on framework behavior that should be confirmed with an instrumented test on
    API 34+:
    - single-connection mode for TEMP tables (no WAL),
    - `rawQueryWithFactory` binding,
    - a large-window retry for rows over 2 MB.
- **Old Android versions.**
  - `minSdk` is still 21, but framework SQLite is older than 3.38 below API 34.
  - Shipping this as-is needs either a bundled SQLite by default (e.g. androidx.sqlite's
    `BundledSQLiteDriver` behind `SqlDriver`) or `minSdk 34`.
  - Rewriting the SQL for 3.8 (no JSON functions) would give up most of the design.
- **Upstream unit tests.** They tested the deleted internals (`Store`, `StatsigLogger`, mocked
  `StatsigNetwork`, DataStore) and were removed. The harness (golden transcript plus SQL unit tests)
  replaces them, and CI runs it (`.github/workflows/tests.yml`).
- **Schema versioning.** Tables are created with `IF NOT EXISTS`. A future column change needs a
  `user_version` migration step.
- **Contention.** Every call takes the client's connection lock. A large payload being applied on
  an IO thread briefly blocks gate checks on the main thread. The original read an in-memory
  snapshot instead.

## Reproducing

```sh
cd harness
gradle :baseline:scenario :sqlsdk:scenario       # transcripts
python3 compare_transcripts.py golden/baseline-scenario.json sqlsdk/build/scenario.json golden/expected-differences.json
gradle :sqlsdk:test                               # SQL unit tests (-PsqliteJdbc=org.xerial:sqlite-jdbc:3.39.2.0 for Android 14's SQLite)
gradle :baseline:bench :sqlsdk:bench              # benchmarks -> */build/bench.json
python3 bench_table.py baseline/build/bench.json sqlsdk/build/bench.json
```
