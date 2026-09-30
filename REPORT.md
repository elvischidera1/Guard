# Statsig Android SDK, re-implemented as SQL — report

This repository started as an unmodified copy of
[statsig-io/android-sdk](https://github.com/statsig-io/android-sdk) at `507d86a` (the first
commit here). Its internal logic has since been re-implemented as SQL that runs in SQLite. What is
left in Kotlin is the public API, the model classes, and the I/O that SQL cannot do.

**TL;DR**

- **Portability.** Every decision the SDK makes now lives in about 2,000 lines of plain SQL: which
  values apply, sticky experiments, overrides, caching and eviction, exposure de-duplication, the
  event queue, diagnostics, retry storage, fallback URLs, name hashing and the whole on-device rules
  engine. Another platform ports it by running the same `.sql` files through its SQLite binding;
  honoring the blocks' directives (caching, batching, skipping known no-ops) makes it fast.
- **Behavior.** It matches the original SDK: a public-API script of 337 observations gives the same
  results on both SDKs, except one diagnostics event where the original has a bug (fixed here).
- **Performance.** After three performance passes, 18 of the 24 benchmarks are as fast as the
  original or faster, and the slowest is 1.4×. Repeated reads (`checkGate`, `getConfig`, layers,
  parameter stores) take 0.15–0.7 µs (the original: 0.4–0.8 µs; parameter stores are 1.2–1.3×,
  within noise); on-device evaluation 0.4–0.8 µs
  (1.8–10 µs); `initialize` from a warm cache or offline 0.5–0.9×. Still slower: a fresh install
  (1.4×: a new database file, its schema and statements), a never-seen gate name with a new
  exposure (1.4×), a 2,000 + 2,000 entity response over the network (1.2×), and `updateUser` over
  the network (1.1×). The first SQL
  version was up to 240× slower; [below](#benchmark-before-and-after) is what changed.
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
| `00_schema.sql` | ~180 | `KeyValueStorage`, in-memory state | File format, connection settings, durable tables (`main`), per-client tables (`temp`) |
| `01_hashing.sql` | ~190 | `Hashing`, `BoundedMemo` | DJB2, SHA-256 + base64, and bucketing, in plain SQL, memoized |
| `02_values.sql` | ~570 | `Store`, `BootstrapValidator`, `BootstrapMetadata`, `InitializeResponseFormatter` | Sessions, cache load/save with 10-entry LRU, bootstrap (v1 and compact init-v2), lookups, sticky experiments, overrides, legacy import |
| `03_logging.sql` | ~300 | `StatsigLogger`, `Diagnostics`, offline part of `StatsigNetwork` | Event queue (flush at 50, cap 1000), 10-minute exposure de-duplication, non-exposed counts, diagnostics markers, failed-batch retention |
| `04_network.sql` | ~100 | `StatsigNetwork` (bodies), `NetworkFallbackResolver` | Initialize request bodies, gzip decision, fallback-URL expiry/rotation, DNS cooldown, TXT-record parsing |
| `05_evaluator.sql` | ~620 | `evaluator/Evaluator`, `EvaluatorUtils`, `SpecStore` | The on-device rules engine: every condition type and operator, nested gates, segments, delegation, pass percentages, unsupported → default |

**Conventions.** These keep the SQL portable and the host code trivial:

- `-- name: <block>` starts a named block of statements. A block runs atomically; it is a
  transaction when it writes more than once. The rows of its last row-returning statement are its
  result.
- Parameters are `:name`. Values are bound as integer, real, text or NULL.
- `main.*` tables hold what the original kept in SharedPreferences.
- `temp.*` tables hold what it kept in memory. Each client owns a connection, so TEMP tables give
  every client its own session state with no extra code. When a client shuts down, the `reset`
  block clears them and the connection (its schema and compiled statements) is kept for the next
  client on the same database.
- Triggers carry the rules that should hold for every write: applying a new set of values, and
  computing hashes on demand.
- **Directives** are comment lines at the top of a block. They describe the block's data flow, so
  the host can skip or batch work without knowing what the block does:

  | Directive | Meaning | What the host does with it |
  | --- | --- | --- |
  | `@reads: a, b` / `@writes: a, b` | The data domains (`values`, `events`, `network`) the block depends on / changes. | Invalidates cached results and runs queued calls in the right order. |
  | `@cache` | The result depends only on the parameters and on what the block reads. A result row with `_cacheable = 0` opts one call out. | Keeps the result until a block writing one of its domains runs. |
  | `@defer` | Calls may be queued and run later, in order, in one transaction. The first statement runs per call; the others run once after each run of consecutive calls. | Queues calls; runs them in bulk (at 50, or before anything that depends on them). |
  | `@coalesce` | Identical queued calls may be merged (`:repeat` counts them). | Merges them. |
  | `@quiet: <ms>` | After a call that changed rows, identical calls within `<ms>` change nothing, unless something the block reads is written. | Skips such calls without touching SQLite. |
  | `@needs: a, b` | Blocks that must have run on the connection first (schema that only some features use, e.g. the SHA-256 program or the evaluator's views). | Runs each once per connection, before the first block that needs it. |

  A result row may also carry `_then`: the name of a block to run with the same parameters before
  the block is run once more (a lookup uses it to have a missing hash computed).

**How a host drives it.** A host ports the SDK by running these blocks and moving bytes. For
`checkGate`:

1. Run `get_value` and turn its row into a `FeatureGate`.
2. Run `log_gate_exposure` with the gate's fields.
3. If that returns `should_flush`, run `take_batch` and POST the body it returns.

That alone is a correct port. The directives are what make it fast: the lookup is `@cache`, so a
repeated `checkGate` is a hash-map hit, and the exposure is `@defer` / `@coalesce` / `@quiet`, so a
repeated exposure is skipped and new ones reach SQLite in batches.

On-device evaluation is the same idea over several blocks: `eval_begin`, then fulfil `regex_request`
rows, then `eval_conditions`, then `eval_step` until it reports no progress, then `eval_result`.

### What stays in Kotlin, and why

| Kotlin | Why it is not SQL |
| --- | --- |
| Public API (`Statsig`, `StatsigClient`) and models (`FeatureGate`, `DynamicConfig`, `Layer`, `ParameterStore`, `StatsigUser`, `StatsigOptions`, ...) | The user-facing surface; kept source- and binary-compatible. |
| `Rows.kt` | Maps result columns to model constructors; makes no decisions. |
| `sql/` (`SqlScript`, `StatsigDb`, `SqlDriver`, `AndroidSqlDriver`) | Loads the SQL, binds parameters, serializes access to one connection, and implements the directives generically (result cache, queued/merged/skipped calls). |
| `StatsigNetwork.kt`, `HttpUtils`, `DnsTxtQuery` | HTTP, gzip, timeouts, retry/backoff schedule, DNS-over-HTTPS, and binary DNS response parsing. |
| `ErrorBoundary`, coroutines, flush timer, polling, lifecycle and connectivity listeners, `DebugView` | Host runtime concerns. |
| Regex for `str_matches` | SQLite has no portable regex. The SQL lists `(pattern, value)` pairs in `regex_request` and the host answers them. |

**Size.**

- **Before:** 9,561 lines of Kotlin in `src/main`.
- **After:** 5,661 lines of Kotlin (1,292 of them `StatsigClient`, whose public methods and
  KDoc are the bulk) plus 1,963 lines of SQL (`wc -l`, comments included).
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
- The **new** SDK runs its SQL on the SQLite bundled with sqlite-jdbc. The harness driver calls
  SQLite's statement API directly (prepare once; bind, step, read columns, reset), as Android's
  framework binding does, rather than going through the JDBC wrapper.

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
2. **SQL unit tests** (`gradle -p harness :sqlsdk:test`, 26 tests):
   - hashing against the platform's DJB2/SHA-256 over random Unicode, and lookups of DJB2- and
     SHA-256-keyed names of any length and alphabet
   - LRU eviction (including the stored entities), sticky values, and responses for a stale user
   - failed-log retention, exposure de-duplication, the queue's flush/cap, and that every kind of
     event is valid JSON without null members
   - the directives: cache reuse and invalidation, per-call opt-out, coalescing, queued-call
     order, and skipped `@quiet` calls
   - fallback URLs, the script parser, and concurrency (8 threads)
   - regression tests for the review findings
3. **SQLite versions.**
   - The unit tests and the golden transcript pass on SQLite **3.50.3** and on **3.39.2**, the
     version Android 14 ships.
   - On 3.36 the SQL fails to parse (`->` JSON operators), which confirms the documented floor.
4. **Review passes.**
   - First pass: the golden diff and profiling. Profiling drove typed entity columns (since
     replaced, see the benchmark section),
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
   - Fourth pass (performance): profiling with JFR and per-statement timing, then the changes
     described in the benchmark section. Each change was checked against the golden transcript
     and the unit tests before moving on. The one bug this caught (a NULL comparison for SHA-256
     payloads that omit `hash_used`) was found by a new unit test; the golden transcript had
     passed.

## Benchmark before and after

`harness/scenario/src/harness/Benchmark.kt` uses only the public API, so both SDKs run the same
code.

- Each run reports, per case, the median of 7 timed rounds after warm-up. The table below is the
  median of three full runs of each SDK, alternating original / SQL, on the same machine: a
  shared 4-vCPU cloud VM, JDK 21. Single runs on this VM move by ±30%, so ratios within about
  1.3× of each other are within noise.
- Network calls go to a local fake server with Nagle's algorithm disabled, so they measure the
  SDK, not the network.
- The original stores in SharedPreferences, which in the harness is purely in memory, so its
  storage cost is, if anything, understated. The SQL SDK uses a file-backed SQLite database (WAL).
- The benchmark JVM pre-touches its heap (`-XX:+AlwaysPreTouch`), for both SDKs. Without it,
  first-touch page faults made allocation-heavy loops of either SDK several times slower for
  seconds, which dominated some cases.
- "First SQL version" is the table this report showed before the performance pass (same
  benchmark, the harness's earlier JDBC-based driver, no heap pre-touch).

| Benchmark (median per operation) | Original | First SQL version | SQL now | Now vs original |
| --- | ---: | ---: | ---: | ---: |
| initialize (network, fresh install) | 19.7 ms | 40.8 ms | 28.2 ms | 1.4× |
| initialize (network, warm cache) | 16.6 ms | 30.1 ms | 14.1 ms | 0.9× |
| initialize (offline, from cache) | 7.7 ms | 21.9 ms | 5.9 ms | 0.8× |
| initialize (network, 2000 gates+2000 configs) | 58.0 ms | 148.4 ms | 70.7 ms | 1.2× |
| initialize (offline, 2000+2000 from cache) | 22.3 ms | 100.8 ms | 11.1 ms | 0.5× |
| checkGate (hit, deduped exposure) | 535 ns | 44.6 µs | 368 ns | 0.7× |
| checkGate (miss) | 812 ns | 38.7 µs | 161 ns | 0.2× |
| checkGateWithExposureLoggingDisabled | 436 ns | 29.7 µs | 288 ns | 0.7× |
| getConfig + getString | 677 ns | 52.7 µs | 398 ns | 0.6× |
| getExperiment(keepDeviceValue=true) | 26.4 µs | 86.4 µs | 394 ns | 0.01× |
| getLayer + getString (param exposure) | 720 ns | 106.3 µs | 520 ns | 0.7× |
| getParameterStore + getString(static) | 512 ns | 34.5 µs | 590 ns | 1.2× |
| getParameterStore + getString(gate ref) | 586 ns | 82.3 µs | 739 ns | 1.3× |
| overridden gate | 552 ns | 38.8 µs | 148 ns | 0.3× |
| checkGate (unique names -> new exposure each) | 33.7 µs | 194.2 µs | 48.1 µs | 1.4× |
| logEvent x2000 + flush | 38.7 µs | 173.8 µs | 15.9 µs | 0.4× |
| updateUser(values) bootstrap switch | 254.8 µs | 222.1 µs | 242.7 µs | 1.0× |
| updateUser (network) switch between 2 users | 3.6 ms | 3.9 ms | 3.8 ms | 1.1× |
| checkGate on 2000-gate payload (hit) | 788 ns | 47.1 µs | 389 ns | 0.5× |
| getConfig on 2000-config payload | 2.1 µs | 48.2 µs | 570 ns | 0.3× |
| on-device checkGate (public rule) | 1.8 µs | 371.5 µs | 805 ns | 0.5× |
| on-device checkGate (nested pass_gate) | 10.1 µs | 1.1 ms | 549 ns | 0.05× |
| on-device checkGate (bucketing/sha256) | 1.8 µs | 439.2 µs | 416 ns | 0.2× |
| on-device getLayer (delegated experiment) | 4.6 µs | 743.4 µs | 770 ns | 0.2× |

### What made it fast

The first version ran 2–4 SQLite statements per `checkGate` and re-parsed JSON everywhere. The
performance pass kept every decision in SQL and attacked three things: work that did not need to
reach SQLite at all, work SQLite did more than once, and per-call overhead.

**1. Most calls no longer reach SQLite (the directives).** The blocks now declare their data
flow, and `StatsigDb` uses that, generically:

- `get_value`, `get_experiment`, `session_state` and friends are `@cache`, so a repeated
  `checkGate` is a lock-free hash-map hit. The cache is invalidated by any block that writes
  `values`, i.e. new values, overrides, a user switch or a sticky write. A sticky `getExperiment`
  that stores a value opts out with `_cacheable = 0`; the next call is cacheable.
- Exposures and events are `@defer`: queued in memory and run 50 at a time, in call order, in one
  transaction, or earlier when a block that reads what they write runs (a flush, a user switch).
- Exposures are `@coalesce` and `@quiet: 600000`. Identical queued calls merge. Once an exposure
  has been logged, identical calls within 10 minutes are known to change nothing (the SQL's
  de-duplication window), so they are skipped until something they read (`values`: the user, the
  exposure memory) changes.
- The exposure parameters are built once per model object.

Together these make a repeated `checkGate` / `getConfig` / `getLayer` about as fast as the
original's `HashMap`, or faster.

**2. SQLite does each piece of work once.**

- *Values are applied without re-parsing.* An entity is stored as its JSON text (`entity_body`);
  a view (`entity`) extracts the fields when a lookup reads them. The response header is read with
  one multi-path `json_extract`, the entities with one pass of `json_each`, and init-v2 statements
  get NULL input unless the payload is init-v2. Before, a large payload was parsed about ten times.
- *Cached values are stored as applied.* `cached_values` keeps the parsed header and
  `cached_entity` the entity rows, so loading the cache is a row copy, not a 1 MB parse. This
  turned the offline start with 2,000 gates + 2,000 configs from 5× slower into faster than the
  original.
- *Exposures are staged.* Each call runs one statement: check the 10-minute window, then note the
  exposure in `exposure_call` (an index lookup when deduplicated). The events are then built for
  all staged calls at once. The first version built every event, then let a trigger reject it, in
  a statement that also paid for a statement journal and trigger setup on every run.
- *Events are written as JSON text.* Strings go through `json_quote`, other members are JSON that
  SQLite or the host made. This avoids `json_patch` / `json_remove` re-parsing each event, and
  `take_batch` concatenates the queued events instead of re-parsing them.
- *Lookups are one statement.* `get_value` computes a name's DJB2 inline, as a sum of
  `c × 31^k` over a small table of powers (DJB2 is linear in the characters), instead of a
  recursive CTE and a memo insert. SHA-256-keyed values and unusual names (over 512 characters,
  or outside the BMP) still use the memo; `get_value` then asks for it with `_then`. Lookups read
  only the columns their callers use; the session-wide details come from the cached
  `session_state` row.

**3. Less per-call overhead.**

- Parameters are bound once per name (`:name` compiles to `?N`).
- The durable database uses WAL with `synchronous = NORMAL` (Android's own setting for WAL
  databases), so a commit does not fsync. Fallback-URL and compression checks are cached.
- Connection setup does less: schema that only some features use (the SHA-256 program, the
  evaluator's views) is created on first use (`@needs`), and hashing a user avoids compiling the
  SHA-256 program.
- The harness driver talks to SQLite's statement API directly, as Android's binding does. The
  JDBC wrapper cost 2–3 µs per statement plus about 0.5 µs per parameter and column.

**4. Setup, one-off work and flushing (third pass).** The third pass went after the cases that
were still slower than the original:

- *Connections are reused.* A client that shuts down hands its connection back: the `reset`
  blocks clear the per-client TEMP tables, and the next client on the same database skips
  `connect` and `schema` and finds its statements compiled. A connection that is not reused is
  closed (a WAL checkpoint) on a background thread. This made `initialize` from a warm cache or
  offline faster than the original.
- *A new file is switched to WAL without a sync.* Its first page holds nothing yet, and the switch
  went from about 2 ms to 0.2 ms.
- *No temporary copies of inserted rows.* SQLite copies the rows of an `INSERT ... SELECT` into a
  temporary table first when the target has triggers (20–40 µs even for one row). The inserts
  into `values_work` use `VALUES`, and the apply trigger is split per kind of payload (v1, stored
  as applied, init-v2) so an insert runs only the statements its payload needs.
- *The bootstrap check compares IDs directly.* The evaluated keys are compared with the user's IDs
  without scratch tables or `EXCEPT`, only `evaluated_keys` is walked (not the whole payload), and
  the outcome is kept with the bootstrap values for the same user and payload.
- *Diagnostics and exposure events are built as text.* Markers and the diagnostics event are
  concatenated (null members left out) instead of `json_object` + `json_patch`; exposure events
  are one `printf` each. `take_batch` returns the request body directly instead of copying it
  through a table.
- *One background flush at a time.* A full queue starts a flush unless one is running; one asked
  for meanwhile runs right after it and takes everything queued by then. Before, flushes queued
  behind a lock held across the HTTP request, so an explicit `flush()` waited for all of them.
- *The host cache stays out of the way of one-off lookups.* Memoized parameters keep their result
  in their own slot; the shared map is only used when two reads share parameters, so a stream of
  never-seen names no longer fills and clears it.
- *A large response is bound once.* Binding a 1 MB initialize response costs about 2 ms per
  statement that names it.

**Where the remaining cost is.** The cases still above 1× are bound by work SQLite does that the
original (whose SharedPreferences are in memory in the harness) does not:

- *Fresh install* (1.4×): a new database file needs its schema (about 60 `CREATE` statements at
  30–55 µs each inside SQLite) and every statement compiled once, 12–15 ms in all. The connection
  pool removes this for later clients on the same database, but the benchmark opens a new file
  each round. Sharing a connection across files (`ATTACH`) does not help: `DETACH` expires every
  compiled statement.
- *A never-seen gate name with a new exposure* (1.4×): a lookup (9.5 µs in SQLite, djb2 included)
  and a staged exposure plus its event (about 6 µs), which the original does with a `HashMap` and a
  list append. Repeats of a name are hash-map hits.
- *A 1 MB response over the network* (1.2×): parsing and applying 4,000 entities (about 30 ms) and
  persisting them as applied (about 15 ms, which makes the offline start 0.5×). A write-behind of
  the persisted copy would move the latter off `initialize`.
- *`updateUser` over the network* (1.1×): per switch, about 1.3 ms of SQL (loading the cache,
  applying and persisting the response, diagnostics) next to a 1.2–1.4 ms request.
- *Sub-microsecond reads* (parameter stores, 0.6–0.7 µs): within this VM's noise of the original
  (they measured 0.8–0.9× in other runs).
- *First use of a statement on a connection* compiles it, e.g. the first lookup of a SHA-256
  keyed name compiles the SHA-256 program (about 2 ms, once per connection).

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
- **Durability.** The database uses WAL with `synchronous = NORMAL`: an OS crash or power loss
  can lose the last commits (never corrupt the file). The original's SharedPreferences writes were
  asynchronous, so they could be lost the same way. A new, empty file is switched to WAL without
  syncing its first page.
- **Log requests during a burst.** While a background flush is in flight, a full queue does not
  start another one; the next flush takes everything queued by then. A burst of events is sent in
  fewer, larger requests than the original's one per 50 events. `flush()` still sends everything
  queued when it is called.
- **Fallback URLs** are looked up once per client while none is known. One that another client in
  the same process discovers later is not seen by this client until it changes fallback URLs
  itself.

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
    - single-connection mode for TEMP tables (the framework's own WAL mode is not enabled),
      while the `connect` block switches the journal to WAL with a `PRAGMA`,
    - `rawQueryWithFactory` binding (PRAGMAs also run as queries),
    - `executeUpdateDelete` returning the changed-row count (`@quiet` relies on it),
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
- **Contention.** Cached reads and skipped exposures take no lock. Anything that reaches SQLite
  takes the client's connection lock, so a large payload being applied on an IO thread briefly
  blocks checks of names not yet cached. The original read an in-memory snapshot instead.
- **Directives are promises.** The host trusts them: a block that writes a domain it does not
  declare in `@writes` would leave stale cached results behind, and a wrong `@quiet` window would
  drop exposures. Each directive is covered by a unit test, and a port can ignore all of them and
  still be correct, only slower.
- **Memory.** The host cache holds up to 16,384 results per domain before it is cleared; each is a
  model object (a few hundred bytes).
- **An idle connection.** After `shutdown()`, the client's connection stays open (its TEMP tables
  emptied) for the next client on the same database. One is kept per process: when a client on
  another database shuts down, its connection replaces the idle one, which is closed.
- **The JVM harness driver** reaches sqlite-jdbc's package-private native methods (from a class
  in its package). It is test infrastructure; the Android driver uses the public framework API.

## Reproducing

```sh
cd harness
gradle :baseline:scenario :sqlsdk:scenario       # transcripts
python3 compare_transcripts.py golden/baseline-scenario.json sqlsdk/build/scenario.json golden/expected-differences.json
gradle :sqlsdk:test                               # SQL unit tests (-PsqliteJdbc=org.xerial:sqlite-jdbc:3.39.2.0 for Android 14's SQLite)
gradle :baseline:bench :sqlsdk:bench              # benchmarks -> */build/bench.json
python3 bench_table.py baseline/build/bench.json sqlsdk/build/bench.json
# the table above: three alternating runs of each, median per case
for i in 1 2 3; do
  gradle :baseline:bench -PbenchOut=$PWD/baseline/build/bench$i.json
  gradle :sqlsdk:bench -PbenchOut=$PWD/sqlsdk/build/bench$i.json
done
python3 bench_table.py baseline/build/bench1.json,baseline/build/bench2.json,baseline/build/bench3.json \
  sqlsdk/build/bench1.json,sqlsdk/build/bench2.json,sqlsdk/build/bench3.json
```
