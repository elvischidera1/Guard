package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.statsig.androidsdk.sql.AndroidSqlDriver
import com.statsig.androidsdk.sql.Row
import com.statsig.androidsdk.sql.SqlDriver
import com.statsig.androidsdk.sql.StatsigDb
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val DATABASE_NAME = "statsig.db"
private const val LEGACY_PREFERENCES = "com.statsig.androidsdk"
private const val FLUSH_INTERVAL_MS = 60_000L
private const val MIN_POLLING_INTERVAL_MS = 60_000L

/**
 * The Statsig client. Every decision (which values apply, what is logged, what is kept on disk)
 * is made by the SQL in resources/com/statsig/androidsdk/sql; this class exposes it through the
 * public API and performs the I/O the SQL cannot: HTTP, timers, lifecycle callbacks.
 */
class StatsigClient : LifecycleEventListener {
    companion object {
        private const val TAG: String = "statsig::StatsigClient"

        /**
         * Opens the SQLite database for a client. The SDK's SQL needs SQLite 3.38 or newer (Android
         * 14+ ships it); apps supporting older devices set this to a [SqlDriver] over a bundled
         * SQLite (e.g. androidx.sqlite's BundledSQLiteDriver or requery's sqlite-android).
         */
        @JvmStatic
        var sqlDriverFactory: (Application) -> SqlDriver = { app ->
            AndroidSqlDriver(app.getDatabasePath(DATABASE_NAME).apply { parentFile?.mkdirs() }.path)
        }
    }

    private lateinit var db: StatsigDb

    // Parameters of the hot lookups, built once per name
    private val gateParams = StatsigDb.ParamsMemo { mapOf("kind" to "gate", "name" to it) }
    private val configParams = StatsigDb.ParamsMemo { mapOf("kind" to "config", "name" to it) }
    private val paramStoreParams =
        StatsigDb.ParamsMemo { mapOf("kind" to "param_store", "name" to it) }
    private val nonExposedParams = StatsigDb.ParamsMemo { mapOf("name" to it) }

    // get_experiment parameters by (keep, logs); "logs" is not a SQL parameter: it keeps cached
    // layers with and without a client apart
    private val experimentParams = Array(4) { i ->
        StatsigDb.ParamsMemo { mapOf("name" to it, "kind" to "config", "keep" to (i and 1 != 0)) }
    }
    private val layerParams = Array(4) { i ->
        StatsigDb.ParamsMemo {
            mapOf(
                "name" to it,
                "kind" to "layer",
                "keep" to (i and 1 != 0),
                "logs" to (i and 2 != 0)
            )
        }
    }

    private fun flags(keep: Boolean, logs: Boolean) = (if (keep) 1 else 0) + (if (logs) 2 else 0)
    private lateinit var diagnostics: Diagnostics
    private lateinit var network: StatsigNetwork

    @Volatile
    private lateinit var user: StatsigUser
    private lateinit var application: Application
    private lateinit var sdkKey: String
    private lateinit var lifecycleListener: StatsigActivityLifecycleListener
    private lateinit var connectivityListener: StatsigNetworkConnectivityListener
    internal lateinit var statsigClientMetadata: StatsigMetadata
    internal lateinit var options: StatsigOptions

    @Volatile
    private var loggingEnabled = DEFAULT_LOGGING_ENABLED

    @Volatile
    private var eventLoggingAPI = DEFAULT_EVENT_API
    private var lifetimeCallback: IStatsigLifetimeCallback? = null
    private var onDeviceEvalAdapter: OnDeviceEvalAdapter? = null
    private var initTime: Long = SystemClock.elapsedRealtime()

    private val dispatcherProvider = CoroutineDispatcherProvider()
    private val errorScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    internal var errorBoundary = ErrorBoundary(errorScope)
    private var statsigJob = SupervisorJob()
    internal lateinit var statsigScope: CoroutineScope

    // Background retries are best effort: a failure must never reach the thread's handler.
    private val retryScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Retrying failed log requests failed", e)
        }
    )
    private val backgroundFlush = AtomicBoolean(false)
    private val flushRequested = AtomicBoolean(false)
    private var pollingJob: Job? = null
    private var flushTimer: Job? = null

    private val initialized = AtomicBoolean(false)
    private val isBootstrapped = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val gson = StatsigUtil.getOrBuildGson()

    // ---------------------------------------------------------------------------------------
    // Initialization

    /**
     * Initializes the SDK for the given user. Initialization is complete when the callback is
     * invoked. Checking gates/configs before then returns cached or default values. Subsequent
     * calls are ignored; use updateUser() to switch users.
     */
    fun initializeAsync(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions()
    ) {
        if (isInitializing.getAndSet(true)) {
            Log.w(
                TAG,
                "initializeAsync() called on a client that is already started or starting - this is a no-op."
            )
            return
        }
        errorBoundary.initialize(sdkKey, options.sdkErrorAPI)
        errorBoundary.capture(
            {
                setup(application, sdkKey, user, options)
                statsigScope.launch {
                    val details = setupAsync()
                    details.duration = SystemClock.elapsedRealtime() - initTime
                    withContext(dispatcherProvider.main) {
                        try {
                            callback?.onStatsigInitialize(details)
                            logInitResult("initializeAsync", details)
                        } catch (e: Exception) {
                            throw ExternalException(e.message)
                        }
                    }
                }
            },
            tag = "initializeAsync",
            recover = {
                logEndDiagnosticsWhenException(ContextType.INITIALIZE, it)
                try {
                    callback?.onStatsigInitialize(failedInitialization(it))
                } catch (e: Exception) {
                    throw ExternalException(e.message)
                }
            }
        )
    }

    /** Initializes the SDK for the given user. See [initializeAsync]. */
    suspend fun initialize(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        options: StatsigOptions = StatsigOptions()
    ): InitializationDetails? {
        if (isInitializing.getAndSet(true)) {
            Log.w(
                TAG,
                "initialize() called on a client that is already started or starting - this is a no-op."
            )
            return null
        }
        errorBoundary.initialize(sdkKey, options.sdkErrorAPI)
        return errorBoundary.captureAsync(
            tag = "initialize",
            task = {
                setup(application, sdkKey, user, options)
                val details = setupAsync()
                details.duration = SystemClock.elapsedRealtime() - initTime
                logInitResult("initialize", details)
                details
            },
            recover = {
                logEndDiagnosticsWhenException(ContextType.INITIALIZE, it)
                failedInitialization(it)
            }
        )
    }

    private fun setup(
        application: Application,
        sdkKey: String,
        user: StatsigUser?,
        options: StatsigOptions
    ) {
        if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
            throw IllegalArgumentException(
                "Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console"
            )
        }
        initTime = SystemClock.elapsedRealtime()
        HttpUtils.maybeInitializeHttpClient(application)
        HttpUtils.addInterceptors(options.interceptors ?: emptyList())
        this.application = application
        this.sdkKey = sdkKey
        this.options = options
        this.loggingEnabled = options.loggingEnabled
        this.eventLoggingAPI = options.eventLoggingAPI
        this.lifetimeCallback = options.lifetimeCallback
        this.user = normalizeUser(user)
        statsigScope =
            CoroutineScope(
                statsigJob + dispatcherProvider.main + errorBoundary.getExceptionHandler()
            )

        val factory = sqlDriverFactory
        db = StatsigDb.open(application to factory) { factory(application) }
        db.run(
            "session_start",
            mapOf(
                "sdk_key" to sdkKey,
                "user" to userJson(),
                "scoped_key" to scopedCacheKey(),
                "options" to gson.toJson(options.getLoggingCopy())
            )
        )
        importLegacyStorage()
        // flush when the SQL says the event queue is full
        db.onShouldFlush = ::flushInBackground
        diagnostics = Diagnostics(db)
        diagnostics.markStart(KeyType.OVERALL, ContextType.INITIALIZE)

        connectivityListener = StatsigNetworkConnectivityListener(application)
        network =
            StatsigNetwork(db, sdkKey, options, connectivityListener, statsigScope, diagnostics)
        statsigClientMetadata =
            if (options.optOutNonSdkMetadata) {
                createCoreStatsigMetadata()
            } else {
                createStatsigMetadata()
            }
        populateStatsigMetadata()
        errorBoundary.setMetadata(statsigClientMetadata)

        onDeviceEvalAdapter = options.onDeviceEvalAdapter?.also { it.attach(db) }
        initialized.set(true)
        lifecycleListener = StatsigActivityLifecycleListener(application, this)
        flushTimer = statsigScope.launch(dispatcherProvider.io) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                try {
                    flushEvents()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Periodic flush failed", e)
                }
            }
        }

        if (!options.loadCacheAsync) {
            diagnostics.markStart(KeyType.INITIALIZE, ContextType.INITIALIZE, StepType.LOAD_CACHE)
            db.run("load_cache")
            diagnostics.markEnd(
                KeyType.INITIALIZE,
                ContextType.INITIALIZE,
                true,
                StepType.LOAD_CACHE
            )
        }
        options.initializeValues?.let {
            bootstrap(it)
            isBootstrapped.set(true)
        }
    }

    private suspend fun setupAsync(): InitializationDetails = withContext(dispatcherProvider.io) {
        errorBoundary.captureAsync(
            tag = "setupAsync",
            task = {
                if (isBootstrapped.get()) {
                    val details = globalEvalDetails()
                    diagnostics.markEnd(
                        KeyType.OVERALL,
                        ContextType.INITIALIZE,
                        details.source == EvalSource.Bootstrap,
                        evaluationDetails = details
                    )
                    logDiagnostics(ContextType.INITIALIZE)
                    return@captureAsync InitializationDetails(0, true, null, details.source)
                }
                if (options.loadCacheAsync) {
                    diagnostics.markStart(
                        KeyType.INITIALIZE,
                        ContextType.INITIALIZE,
                        StepType.LOAD_CACHE
                    )
                    db.run("load_cache")
                    diagnostics.markEnd(
                        KeyType.INITIALIZE,
                        ContextType.INITIALIZE,
                        true,
                        StepType.LOAD_CACHE
                    )
                }
                val failure = if (options.initializeOffline) {
                    null
                } else {
                    fetchValues(ContextType.INITIALIZE)
                }
                pollForUpdates()
                if (!options.disableLogEventRetries) {
                    retryScope.launch {
                        try {
                            network.retryFailedLogs(eventLoggingAPI, statsigClientMetadata)
                        } catch (_: Exception) {
                            // best effort
                        }
                    }
                }
                val success = failure == null
                logEndDiagnostics(success, ContextType.INITIALIZE, failure)
                InitializationDetails(0, success, failure, globalEvalDetails().source)
            },
            recover = { e ->
                logEndDiagnosticsWhenException(ContextType.INITIALIZE, e)
                InitializationDetails(
                    0,
                    false,
                    InitializeResponse.FailedInitializeResponse(
                        if (e is TimeoutCancellationException) {
                            InitializeFailReason.CoroutineTimeout
                        } else {
                            InitializeFailReason.InternalError
                        },
                        e
                    ),
                    initializationSource()
                )
            }
        )
    }

    /** Fetches values for the current user and stores them. Returns the failure, if any. */
    private suspend fun fetchValues(
        context: ContextType
    ): InitializeResponse.FailedInitializeResponse? {
        val result = network.initialize(
            context,
            statsigClientMetadata,
            if (options.disableHashing == true) HashAlgorithm.NONE else HashAlgorithm.DJB2
        )
        if (result.payload == null) {
            db.run("network_failed")
            return result.failure
        }
        diagnostics.markStart(KeyType.INITIALIZE, context, StepType.PROCESS)
        try {
            saveValues(result)
        } catch (e: Exception) {
            // e.g. a 200 response that is not JSON: an ordinary failed response, as before
            diagnostics.markEnd(KeyType.INITIALIZE, context, false, StepType.PROCESS)
            db.run("network_failed")
            return InitializeResponse.FailedInitializeResponse(InitializeFailReason.NetworkError, e)
        }
        diagnostics.markEnd(KeyType.INITIALIZE, context, true, StepType.PROCESS)
        return null
    }

    private fun saveValues(result: StatsigNetwork.InitializeResult) {
        val saved = db.one(
            "save_values",
            mapOf(
                "payload" to result.payload,
                "user" to result.user,
                "scoped_key" to result.scopedKey
            )
        )
        if (saved?.bool("has_updates") == true && saved.bool("applied")) {
            statsigScope.launch(dispatcherProvider.main) { lifetimeCallback?.onValuesUpdated() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Evaluation

    /**
     * The value of a feature gate for the current user, logging an exposure.
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun checkGate(gateName: String): Boolean =
        evaluateGate("checkGate", gateName, logExposure = true).getValue()

    fun checkGateWithExposureLoggingDisabled(gateName: String): Boolean = evaluateGate(
        "checkGateWithExposureLoggingDisabled",
        gateName,
        logExposure = false
    ).getValue()

    fun getFeatureGate(gateName: String): FeatureGate =
        evaluateGate("getFeatureGate", gateName, logExposure = true)

    fun getFeatureGateWithExposureLoggingDisabled(gateName: String): FeatureGate =
        evaluateGate("getFeatureGateWithExposureLoggingDisabled", gateName, logExposure = false)

    /** A dynamic config for the current user, logging an exposure. */
    fun getConfig(configName: String): DynamicConfig =
        evaluateConfig("getConfig", configName, true) {
            getDynamicConfigEvaluation(configName)
        }

    fun getConfigWithExposureLoggingDisabled(configName: String): DynamicConfig =
        evaluateConfig("getConfigWithExposureLoggingDisabled", configName, false) {
            getDynamicConfigEvaluation(configName)
        }

    /**
     * An experiment for the current user, logging an exposure. With [keepDeviceValue] the value
     * is kept on the device for as long as the experiment runs.
     */
    fun getExperiment(experimentName: String, keepDeviceValue: Boolean = false): DynamicConfig =
        evaluateConfig("getExperiment", experimentName, true) {
            getExperimentEvaluation(experimentName, keepDeviceValue)
        }

    fun getExperimentWithExposureLoggingDisabled(
        experimentName: String,
        keepDeviceValue: Boolean = false
    ): DynamicConfig =
        evaluateConfig("getExperimentWithExposureLoggingDisabled", experimentName, false) {
            getExperimentEvaluation(experimentName, keepDeviceValue)
        }

    /** A layer for the current user. Exposures are logged as its parameters are read. */
    fun getLayer(layerName: String, keepDeviceValue: Boolean = false): Layer =
        evaluateLayer("getLayer", layerName, keepDeviceValue, logExposure = true)

    fun getLayerWithExposureLoggingDisabled(
        layerName: String,
        keepDeviceValue: Boolean = false
    ): Layer = evaluateLayer(
        "getLayerWithExposureLoggingDisabled",
        layerName,
        keepDeviceValue,
        logExposure = false
    )

    /** A parameter store for the current user. */
    fun getParameterStore(
        parameterStoreName: String,
        options: ParameterStoreEvaluationOptions? = null
    ): ParameterStore {
        enforceInitialized("getParameterStore")
        var paramStore = ParameterStore(
            this,
            HashMap(),
            parameterStoreName,
            EvalDetails(EvalSource.Error, EvalReason.Unrecognized),
            options
        )
        errorBoundary.capture(
            {
                db.run("count_non_exposed", nonExposedParams[parameterStoreName])
                val (parameters, details) = db.read(
                    "get_value",
                    paramStoreParams[parameterStoreName]
                ) { rows ->
                    jsonParamStore(rows.first().string("value")) to
                        rows.first().evalDetails(session())
                }
                paramStore =
                    ParameterStore(
                        this,
                        parameters,
                        parameterStoreName,
                        details,
                        options
                    )
                paramStore = onDeviceEvalAdapter?.getParamStore(this, paramStore) ?: paramStore
            },
            tag = "getParameterStore",
            configName = parameterStoreName
        )
        return paramStore
    }

    private fun evaluateGate(
        functionName: String,
        gateName: String,
        logExposure: Boolean
    ): FeatureGate {
        enforceInitialized(functionName)
        var result: FeatureGate? = null
        errorBoundary.capture(
            {
                if (!logExposure) db.run("count_non_exposed", nonExposedParams[gateName])
                val gate = getFeatureGateEvaluation(gateName)
                if (logExposure) logGateExposure(gate, isManual = false)
                result = gate
            },
            tag = functionName,
            configName = gateName
        )
        val gate = result ?: FeatureGate.getError(gateName)
        options.evaluationCallback?.invoke(gate)
        return gate
    }

    private fun evaluateConfig(
        functionName: String,
        name: String,
        logExposure: Boolean,
        evaluate: () -> DynamicConfig
    ): DynamicConfig {
        enforceInitialized(functionName)
        var evaluated: DynamicConfig? = null
        errorBoundary.capture(
            {
                if (!logExposure) db.run("count_non_exposed", nonExposedParams[name])
                val config = evaluate()
                if (logExposure) logConfigExposure(config, isManual = false)
                evaluated = config
            },
            tag = functionName,
            configName = name
        )
        val result = evaluated ?: DynamicConfig.getError(name)
        options.evaluationCallback?.invoke(result)
        return result
    }

    private fun evaluateLayer(
        functionName: String,
        layerName: String,
        keepDeviceValue: Boolean,
        logExposure: Boolean
    ): Layer {
        enforceInitialized(functionName)
        var evaluated: Layer? = null
        errorBoundary.capture(
            {
                if (!logExposure) db.run("count_non_exposed", nonExposedParams[layerName])
                evaluated =
                    getLayerEvaluation(if (logExposure) this else null, layerName, keepDeviceValue)
            },
            tag = functionName,
            configName = layerName
        )
        val layer = evaluated ?: Layer.getError(layerName)
        options.evaluationCallback?.invoke(layer)
        return layer
    }

    private fun getFeatureGateEvaluation(gateName: String): FeatureGate {
        val gate = db.read("get_value", gateParams[gateName]) {
            it.first().toFeatureGate(gateName, session())
        }
        return onDeviceEvalAdapter?.getGate(gate, user) ?: gate
    }

    private fun getDynamicConfigEvaluation(configName: String): DynamicConfig {
        val config = db.read("get_value", configParams[configName]) {
            it.first().toDynamicConfig(configName, session())
        }
        return onDeviceEvalAdapter?.getDynamicConfig(config, user) ?: config
    }

    private fun getExperimentEvaluation(
        experimentName: String,
        keepDeviceValue: Boolean
    ): DynamicConfig {
        val experiment = db.read(
            "get_experiment",
            experimentParams[flags(keepDeviceValue, false)][experimentName]
        ) { it.first().toDynamicConfig(experimentName, session()) }
        return onDeviceEvalAdapter?.getDynamicConfig(experiment, user) ?: experiment
    }

    private fun getLayerEvaluation(
        client: StatsigClient?,
        layerName: String,
        keepDeviceValue: Boolean
    ): Layer {
        val layer = db.read(
            "get_experiment",
            layerParams[flags(keepDeviceValue, client != null)][layerName]
        ) { it.first().toLayer(client, layerName, session()) }
        return onDeviceEvalAdapter?.getLayer(client, layer, user) ?: layer
    }

    // ---------------------------------------------------------------------------------------
    // Logging

    /**
     * Log an event to Statsig for the current user
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmOverloads
    fun logEvent(eventName: String, value: Double? = null, metadata: Map<String, String>? = null) {
        logEvent(eventName, value, metadata as Map<String, Any>?)
    }

    @JvmName("logEventWithAnyMetadata")
    fun logEvent(eventName: String, value: Double?, metadata: Map<String, Any>?) {
        logCustomEvent(eventName, value, metadata) {
            val eventMetadata = mutableMapOf<String, String>()
            if (!options.disableCurrentActivityLogging) {
                lifecycleListener.getCurrentActivity()?.let {
                    eventMetadata["currentPage"] =
                        it.javaClass.simpleName
                }
            }
            if (options.logNetworkMetadata) {
                eventMetadata.putAll(connectivityListener.getLogEventNetworkMetadata())
            }
            eventMetadata.ifEmpty { null }
        }
    }

    @JvmOverloads
    fun logEvent(eventName: String, value: String, metadata: Map<String, String>? = null) {
        logEvent(eventName, value, metadata as Map<String, Any>?)
    }

    @JvmName("logEventWithAnyMetadata")
    fun logEvent(eventName: String, value: String, metadata: Map<String, Any>?) {
        logCustomEvent(eventName, value, metadata)
    }

    fun logEvent(eventName: String, metadata: Map<String, String>) {
        logEvent(eventName, metadata as Map<String, Any>)
    }

    @JvmName("logEventWithAnyMetadata")
    fun logEvent(eventName: String, metadata: Map<String, Any>) {
        logCustomEvent(eventName, null, metadata)
    }

    private fun logCustomEvent(
        eventName: String,
        value: Any?,
        metadata: Map<String, Any>?,
        eventMetadata: () -> Map<String, String>? = { null }
    ) {
        enforceInitialized("logEvent")
        errorBoundary.capture(
            {
                val statsigMetadata = eventMetadata()
                db.run(
                    "log_event",
                    mapOf(
                        "event_name" to eventName,
                        "value" to toJson(value),
                        "metadata" to toJson(metadata),
                        "statsig_metadata" to toJson(statsigMetadata)
                    )
                )
            },
            tag = "logEvent"
        )
    }

    private fun logGateExposure(gate: FeatureGate, isManual: Boolean) {
        val params = if (isManual) {
            gateExposure(gate, true)
        } else {
            gate.exposureParams ?: gateExposure(gate, false).also { gate.exposureParams = it }
        }
        db.run("log_gate_exposure", params)
    }

    private fun gateExposure(gate: FeatureGate, isManual: Boolean): Map<String, Any?> {
        val details = gate.getEvalDetails()
        return StatsigDb.Params(
            mapOf(
                "name" to gate.getName(),
                "value" to gate.getValue(),
                "rule_id" to gate.getRuleID(),
                "secondary" to
                    (gate.secondaryExposuresJson ?: toJson(gate.getSecondaryExposures())),
                "reason" to details.getDetailedReasonString(),
                "lcut" to details.lcut,
                "received_at" to details.receivedAt,
                "manual" to isManual
            )
        )
    }

    private fun logConfigExposure(config: DynamicConfig, isManual: Boolean) {
        val params = if (isManual) {
            configExposure(config, true)
        } else {
            config.exposureParams ?: configExposure(config, false).also {
                config.exposureParams = it
            }
        }
        db.run("log_config_exposure", params)
    }

    private fun configExposure(config: DynamicConfig, isManual: Boolean): Map<String, Any?> {
        val details = config.getEvalDetails()
        return StatsigDb.Params(
            mapOf(
                "name" to config.getName(),
                "rule_id" to config.getRuleID(),
                "secondary" to
                    (config.secondaryExposuresJson ?: toJson(config.getSecondaryExposures())),
                "reason" to details.getDetailedReasonString(),
                "lcut" to details.lcut,
                "received_at" to details.receivedAt,
                "rule_passed" to config.getRulePassed(),
                "manual" to isManual
            )
        )
    }

    internal fun logLayerParameterExposure(
        layer: Layer,
        parameterName: String,
        isManual: Boolean = false
    ) {
        if (!isInitialized()) return
        errorBoundary.capture({
            queueLayerExposure(layer, parameterName, isManual)
        }, tag = "logLayerExposure")
    }

    private fun queueLayerExposure(layer: Layer, parameterName: String, isManual: Boolean) {
        val params = if (isManual) {
            layerExposure(layer, parameterName, true)
        } else {
            layer.exposureParams().getOrPut(parameterName) {
                layerExposure(layer, parameterName, false)
            }
        }
        db.run("log_layer_exposure", params)
    }

    private fun layerExposure(
        layer: Layer,
        parameterName: String,
        isManual: Boolean
    ): Map<String, Any?> {
        val details = layer.getEvalDetails()
        val json = layer.exposureJson ?: Layer.ExposureJson(
            toJson(layer.getExplicitParameters()),
            toJson(layer.getSecondaryExposures()),
            toJson(layer.getUndelegatedSecondaryExposures())
        ).also { layer.exposureJson = it }
        return StatsigDb.Params(
            mapOf(
                "name" to layer.getName(),
                "rule_id" to layer.getRuleIDForParameter(parameterName),
                "parameter" to parameterName,
                "explicit" to json.explicit,
                "allocated" to layer.getAllocatedExperimentName(),
                "secondary" to json.secondary,
                "undelegated" to json.undelegated,
                "reason" to details.getDetailedReasonString(),
                "received_at" to details.receivedAt,
                "manual" to isManual
            )
        )
    }

    fun manuallyLogGateExposure(gateName: String) =
        manualExposure("logManualGateExposure", gateName) {
            logGateExposure(
                db.one("get_value", mapOf("kind" to "gate", "name" to gateName))!!
                    .toFeatureGate(gateName, session()),
                isManual = true
            )
        }

    fun manuallyLogConfigExposure(configName: String) =
        manualExposure("logManualConfigExposure", configName) {
            logConfigExposure(
                db.one(
                    "get_value",
                    mapOf("kind" to "config", "name" to configName)
                )!!.toDynamicConfig(configName, session()),
                isManual = true
            )
        }

    fun manuallyLogExperimentExposure(configName: String, keepDeviceValue: Boolean) =
        manualExposure("logManualExperimentExposure", configName) {
            val experiment = db.one(
                "get_experiment",
                mapOf("name" to configName, "kind" to "config", "keep" to keepDeviceValue)
            )!!.toDynamicConfig(configName, session())
            logConfigExposure(experiment, isManual = true)
        }

    fun manuallyLogLayerParameterExposure(
        layerName: String,
        parameterName: String,
        keepDeviceValue: Boolean
    ) = manualExposure("logManualLayerExposure", layerName) {
        val layer = db.one(
            "get_experiment",
            mapOf("name" to layerName, "kind" to "layer", "keep" to keepDeviceValue)
        )!!.toLayer(null, layerName, session())
        queueLayerExposure(layer, parameterName, isManual = true)
    }

    fun manuallyLogGateExposure(gate: FeatureGate) =
        manualExposure("logManualGateExposure", gate.getName()) {
            logGateExposure(gate, isManual = true)
        }

    fun manuallyLogConfigExposure(config: DynamicConfig) =
        manualExposure("logManualConfigExposure", config.getName()) {
            logConfigExposure(config, isManual = true)
        }

    fun manuallyLogExperimentExposure(experiment: DynamicConfig) =
        manualExposure("logManualExperimentExposure", experiment.getName()) {
            logConfigExposure(experiment, isManual = true)
        }

    fun manuallyLogLayerParameterExposure(layer: Layer, parameterName: String) =
        manualExposure("logManualLayerExposure", layer.getName()) {
            queueLayerExposure(layer, parameterName, isManual = true)
        }

    private fun manualExposure(functionName: String, name: String, log: () -> Unit) {
        enforceInitialized(functionName)
        errorBoundary.capture(log, tag = functionName, configName = name)
    }

    /**
     * Sends the queued events. take_batch takes them atomically, so flushes need no lock of their
     * own and their requests may overlap, as the original's did.
     */
    private suspend fun flushEvents() {
        val batch = db.one(
            "take_batch",
            mapOf(
                "metadata" to toJson(statsigClientMetadata),
                "logging_enabled" to loggingEnabled
            )
        )
        if (batch == null) {
            if (!loggingEnabled) Log.d(TAG, "loggingEnabled is FALSE, flush() skipped")
            return
        }
        val count = batch.long("n").toString()
        network.postLogs(eventLoggingAPI, batch.string("body")!!, count, statsigClientMetadata)
        Log.v(TAG, "flush() completed with $count events")
    }

    private fun logDiagnostics(context: ContextType) {
        if (diagnostics.logDiagnostics(context)) {
            flushInBackground()
        }
    }

    /**
     * A flush for a full queue. One at a time: one asked for meanwhile runs when it is done and
     * takes all the events queued by then, so a burst of events makes a few large requests
     * rather than many small ones.
     */
    private fun flushInBackground() {
        flushRequested.set(true)
        if (!backgroundFlush.compareAndSet(false, true)) return
        statsigScope.launch(dispatcherProvider.io) {
            try {
                while (flushRequested.getAndSet(false)) flushEvents()
            } finally {
                backgroundFlush.set(false)
                if (flushRequested.get()) flushInBackground()
            }
        }
    }

    suspend fun flush() {
        enforceInitialized("flush")
        errorBoundary.captureAsync(task = {
            Log.v(TAG, "starting manual flush...")
            flushEvents()
        }, tag = "flush")
    }

    // ---------------------------------------------------------------------------------------
    // Users and values

    fun updateRuntimeOptions(runtimeMutableOptions: StatsigRuntimeMutableOptions) {
        enforceInitialized("updateRuntimeOptions")
        errorBoundary.capture(
            {
                loggingEnabled = runtimeMutableOptions.loggingEnabled
                eventLoggingAPI = runtimeMutableOptions.eventLoggingAPI
                Log.v(TAG, "Runtime options successfully updated")
            },
            tag = "updateRuntimeOptions"
        )
    }

    /**
     * Switch to a new user (or the same user with new properties). Cached values for the user are
     * used right away; fresh values are fetched in the background unless [values] are given.
     */
    fun updateUserAsync(
        user: StatsigUser?,
        callback: IStatsigCallback? = null,
        values: Map<String, Any>? = null
    ) {
        enforceInitialized("updateUserAsync")
        errorBoundary.capture(
            {
                diagnostics.markStart(KeyType.OVERALL, ContextType.UPDATE_USER)
                switchUser(user)
                if (values != null) {
                    bootstrap(values)
                    logEndDiagnostics(true, ContextType.UPDATE_USER, null)
                    Log.v(TAG, "updateUserAsync completed with provided values")
                    callback?.onStatsigUpdateUser()
                    lifetimeCallback?.onValuesUpdated()
                } else {
                    db.run("load_cache")
                    statsigScope.launch {
                        updateUserImpl()
                        withContext(dispatcherProvider.main) {
                            try {
                                Log.v(TAG, "updateUserAsync completed")
                                callback?.onStatsigUpdateUser()
                            } catch (e: Exception) {
                                throw ExternalException(e.message)
                            }
                        }
                    }
                }
            },
            tag = "updateUserAsync"
        )
    }

    suspend fun updateUser(user: StatsigUser?, values: Map<String, Any>? = null) {
        enforceInitialized("updateUser")
        errorBoundary.captureAsync(task = {
            diagnostics.markStart(KeyType.OVERALL, ContextType.UPDATE_USER)
            switchUser(user)
            if (values != null) {
                bootstrap(values)
                logEndDiagnostics(true, ContextType.UPDATE_USER, null)
                Log.v(TAG, "updateUser completed with provided values")
            } else {
                db.run("load_cache")
                updateUserImpl()
                Log.v(TAG, "updateUser completed")
            }
        }, tag = "updateUser")
    }

    suspend fun refreshCacheAsync(callback: IStatsigCallback? = null) {
        enforceInitialized("refreshCacheAsync")
        errorBoundary.capture(
            {
                diagnostics.markStart(KeyType.OVERALL, ContextType.UPDATE_USER)
                statsigScope.launch {
                    updateUserImpl()
                    withContext(dispatcherProvider.main) {
                        try {
                            callback?.onStatsigUpdateUser()
                            Log.v(TAG, "refreshCacheAsync completed")
                        } catch (e: Exception) {
                            throw ExternalException(e.message)
                        }
                    }
                }
            },
            tag = "refreshCacheAsync"
        )
    }

    suspend fun refreshCache() {
        enforceInitialized("refreshCache")
        errorBoundary.captureAsync(task = {
            diagnostics.markStart(KeyType.OVERALL, ContextType.UPDATE_USER)
            updateUserImpl()
            Log.v(TAG, "refreshCache completed")
        }, tag = "refreshCache")
    }

    private fun switchUser(newUser: StatsigUser?) {
        user = normalizeUser(newUser)
        pollingJob?.cancel()
        db.run("set_user", mapOf("user" to userJson(), "scoped_key" to scopedCacheKey()))
    }

    private suspend fun updateUserImpl() = withContext(dispatcherProvider.io) {
        errorBoundary.captureAsync(
            tag = "updateUserImpl",
            task = {
                val failure = fetchValues(ContextType.UPDATE_USER)
                pollForUpdates()
                logEndDiagnostics(failure == null, ContextType.UPDATE_USER, failure)
            },
            recover = { logEndDiagnosticsWhenException(ContextType.UPDATE_USER, it) }
        )
    }

    private fun bootstrap(values: Map<String, Any>) {
        db.run("bootstrap", mapOf("values" to gson.toJson(values)))
    }

    private fun pollForUpdates() {
        if (!options.enableAutoValueUpdate) return
        pollingJob?.cancel()
        val interval =
            maxOf(
                (options.autoValueUpdateIntervalMinutes * 60 * 1000).toLong(),
                MIN_POLLING_INTERVAL_MS
            )
        pollingJob = statsigScope.launch(dispatcherProvider.io) {
            while (isActive) {
                delay(interval)
                try {
                    val result = network.initialize(
                        null,
                        statsigClientMetadata,
                        HashAlgorithm.DJB2,
                        poll = true
                    )
                    if (result.payload != null) saveValues(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Init Polling Error", e)
                }
            }
        }
    }

    /** @return the values in use, as JSON, with their evaluation details */
    fun getInitializeResponseJson(): ExternalInitializeResponse {
        enforceInitialized("getInitializeResponseJson")
        var result: ExternalInitializeResponse? = null
        errorBoundary.capture(
            {
                val row = db.one("values_json")!!
                result = ExternalInitializeResponse(
                    row.string("json"),
                    EvalDetails(
                        EvalSource.valueOf(row.string("source")!!),
                        EvalReason.Recognized,
                        row.long("lcut"),
                        row.long("received_at")
                    )
                )
            },
            tag = "getInitializeResponseJson"
        )
        return result ?: ExternalInitializeResponse.getUninitialized()
    }

    // ---------------------------------------------------------------------------------------
    // Overrides

    /** @param value the result to be returned when checkGate is called */
    fun overrideGate(gateName: String, value: Boolean) =
        setOverride("overrideGate", "gate", gateName, value)

    /** @param value the values to be returned when getConfig or getExperiment is called */
    fun overrideConfig(configName: String, value: Map<String, Any>) =
        setOverride("overrideConfig", "config", configName, value)

    /** @param value the values to be returned in a Layer object when getLayer is called */
    fun overrideLayer(configName: String, value: Map<String, Any>) =
        setOverride("overrideLayer", "layer", configName, value)

    private fun setOverride(functionName: String, kind: String, name: String, value: Any) {
        errorBoundary.capture(
            {
                db.run(
                    "override_set",
                    mapOf(
                        "kind" to kind,
                        "name" to name,
                        "value" to gson.toJson(value)
                    )
                )
                Log.v(TAG, "$functionName() completed")
            },
            tag = functionName
        )
    }

    /** Clears any override of a gate, config, experiment or layer with this name. */
    fun removeOverride(name: String) {
        errorBoundary.capture({
            db.run("override_remove", mapOf("name" to name))
        }, tag = "removeOverride")
    }

    /** Throw away all overridden values */
    fun removeAllOverrides() {
        errorBoundary.capture({ db.run("override_clear") }, tag = "removeAllOverrides")
    }

    /** @return the overrides that are currently applied */
    fun getAllOverrides(): StatsigOverrides {
        var result: StatsigOverrides? = null
        errorBoundary.capture(
            {
                val overrides = StatsigOverrides.empty()
                for (row in db.run("overrides")) {
                    val name = row.string("name")!!
                    val value = row.string("value")!!
                    when (row.string("kind")) {
                        "gate" -> overrides.gates[name] = value == "true"
                        "config" -> overrides.configs[name] = jsonMap(value)
                        "layer" -> overrides.layers[name] = jsonMap(value)
                    }
                }
                result = overrides
            },
            tag = "getAllOverrides"
        )
        return result ?: StatsigOverrides.empty()
    }

    // ---------------------------------------------------------------------------------------
    // Metadata, lifecycle

    /** @return the current Statsig stableID */
    @Deprecated(
        "This function will be deprecated in a future release - the field is available in StatsigMetadata",
        ReplaceWith("getStatsigMetadata().stableID")
    )
    fun getStableID(): String {
        enforceInitialized("getStableID")
        return statsigClientMetadata.stableID ?: ""
    }

    /** @return the current Statsig sessionID */
    @Deprecated(
        "This function will be deprecated in a future release - the field is available in StatsigMetadata",
        ReplaceWith("getStatsigMetadata().stableID")
    )
    fun getSessionID(): String {
        enforceInitialized("getSessionID")
        return statsigClientMetadata.sessionID
    }

    /** @return the current [StatsigMetadata] of this client instance */
    fun getStatsigMetadata(): StatsigMetadata {
        enforceInitialized("getStatsigMetadata()")
        return statsigClientMetadata.copy()
    }

    fun openDebugView(context: Context, callback: DebugViewCallback? = null) {
        errorBoundary.capture({
            val row = db.one("values_json")!!
            val map = mapOf(
                "values" to row.string("json"),
                "evalReason" to globalEvalDetails().getDetailedReasonString(),
                "user" to user.getCopyForEvaluation(),
                "options" to options.toMap()
            )
            DebugView.show(context, sdkKey, map, callback)
        }, tag = "openDebugView")
    }

    suspend fun shutdownSuspend() {
        enforceInitialized("shutdownSuspend")
        errorBoundary.captureAsync(task = { shutdownImpl() }, tag = "shutdownSuspend")
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun shutdown() {
        enforceInitialized("shutdown")
        runBlocking { withContext(Dispatchers.Main.immediate) { shutdownSuspend() } }
    }

    private suspend fun shutdownImpl() {
        Log.v(TAG, "shutting down...")
        initialized.set(false)
        pollingJob?.cancel()
        flushTimer?.cancel()
        try {
            flushEvents()
        } finally {
            lifecycleListener.shutdown()
            statsigJob.cancel()
            db.close()
            isBootstrapped.set(false)
            errorBoundary = ErrorBoundary(errorScope)
            statsigJob = SupervisorJob()
            isInitializing.set(false)
            Log.v(TAG, "shutdown completed.")
        }
    }

    fun isInitialized(): Boolean = initialized.get()

    internal fun enforceInitialized(functionName: String) {
        if (!initialized.get()) {
            throw IllegalStateException(
                "The SDK must be initialized prior to invoking $functionName"
            )
        }
    }

    override fun onAppFocus() {
        if (options.disableLogEventRetries) return
        retryScope.launch { network.retryFailedLogs(eventLoggingAPI, statsigClientMetadata) }
    }

    override fun onAppBlur() {
        statsigScope.launch(dispatcherProvider.io) { flushEvents() }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        val normalized = user?.getCopyForEvaluation() ?: StatsigUser(null)
        normalized.statsigEnvironment = options.getEnvironment()
        options.userObjectValidator?.let { it(normalized) }
        return normalized
    }

    private fun userJson(): String = gson.toJson(user)

    private fun scopedCacheKey(): String = options.customCacheKey(sdkKey, user)

    /** The session-wide evaluation details (and more), cached until the values change. */
    private fun session(): Row = db.one("session_state")!!

    private fun globalEvalDetails(reason: EvalReason? = null): EvalDetails =
        db.one("session_state")!!.evalDetails().apply { this.reason = reason }

    /** For failure paths: must not throw, whatever state the database is in. */
    private fun initializationSource(): EvalSource = try {
        db.run("session_state").firstOrNull()?.evalDetails()?.source ?: EvalSource.Error
    } catch (e: Exception) {
        EvalSource.Error
    }

    private fun failedInitialization(e: Exception?) = InitializationDetails(
        duration = SystemClock.elapsedRealtime() - initTime,
        success = false,
        failureDetails = InitializeResponse.FailedInitializeResponse(
            InitializeFailReason.InternalError,
            e
        ),
        source = initializationSource()
    )

    private fun logInitResult(functionName: String, details: InitializationDetails) {
        Log.v(TAG, "$functionName completed. Success: ${details.success}")
        details.failureDetails?.let { failure ->
            Log.e(TAG, "$functionName failure reason: ${failure.reason}")
            failure.exception?.let { Log.e(TAG, "$functionName failure exception: $it") }
        }
    }

    /** Carries the stable ID, overrides and device sticky values over from SDK versions < 6. */
    private fun importLegacyStorage() {
        if (db.one("legacy_import_pending")?.bool("pending") != true) return
        val prefs = application.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        db.run(
            "legacy_import",
            mapOf(
                "stable_id" to prefs?.getString("STABLE_ID", null),
                "overrides" to prefs?.getString("Statsig.LOCAL_OVERRIDES", null),
                "device_sticky" to prefs?.getString("Statsig.STICKY_DEVICE_EXPERIMENTS", null)
            )
        )
    }

    private fun populateStatsigMetadata() {
        statsigClientMetadata.stableID =
            options.overrideStableID ?: db.one("stable_id")!!.string("stable_id")
        try {
            if (application.packageManager != null && !options.optOutNonSdkMetadata) {
                val info = application.packageManager.getPackageInfo(application.packageName, 0)
                statsigClientMetadata.appVersion = info.versionName
                statsigClientMetadata.appIdentifier = info.packageName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // noop
        }
    }

    private fun logEndDiagnostics(
        success: Boolean,
        context: ContextType,
        failure: InitializeResponse.FailedInitializeResponse?
    ) {
        diagnostics.markEnd(
            KeyType.OVERALL,
            context,
            success,
            evaluationDetails = globalEvalDetails(),
            error = failure?.let { Diagnostics.formatFailedResponse(it) }
        )
        logDiagnostics(context)
    }

    private fun logEndDiagnosticsWhenException(context: ContextType, e: Exception?) {
        try {
            if (this::diagnostics.isInitialized) {
                diagnostics.markEnd(
                    KeyType.OVERALL,
                    context,
                    false,
                    evaluationDetails = globalEvalDetails(),
                    error = Marker.ErrorMessage(message = "${e?.javaClass?.name}: ${e?.message}")
                )
                logDiagnostics(context)
                statsigScope.launch(dispatcherProvider.io) { flushEvents() }
            }
        } catch (_: Exception) {
            // no-op
        }
    }
}
