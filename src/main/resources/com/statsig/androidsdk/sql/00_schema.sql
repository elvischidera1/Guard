-- Statsig client SDK core, written as SQL for SQLite (>= 3.38, JSON functions built in).
--
-- Every platform SDK runs the same statements. The host language only:
--   * opens a database and runs the `schema` block once per connection,
--   * binds parameters and reads result rows,
--   * does I/O the SQL asks for (HTTP, timers, regex matching for str_matches),
--   * turns result rows into its model objects (FeatureGate, DynamicConfig, ...).
--
-- File format (parsed by the host, see SqlScript.kt):
--   * `-- name: <block>` starts a named block; a block runs as one transaction.
--   * A statement ends with `;` at the end of a line. A CREATE TRIGGER ends at a line `END;`.
--   * Parameters are named `:param`. Rows of the block's last statement are its result.
--   * All times are epoch milliseconds from the `clock` view unless passed in.
--
-- Storage layout:
--   main.*  durable state (what the original SDK kept in SharedPreferences/DataStore).
--   temp.*  per-client state (what the original SDK kept in memory). Each client owns a
--           connection, so TEMP tables give every client its own session for free.

-- name: schema

-- Per-client state is scratch data: keep TEMP tables in memory (the default is a temp file).
PRAGMA temp_store = MEMORY;

-- Durable ------------------------------------------------------------------------------------

-- Small facts that outlive a session, e.g. the device's stable ID.
CREATE TABLE IF NOT EXISTS setting (
  key TEXT PRIMARY KEY,
  value TEXT
);

-- Initialize responses, one per (user, SDK key). `payload` is the response as received.
CREATE TABLE IF NOT EXISTS cached_values (
  cache_key TEXT PRIMARY KEY,        -- '<user hash>:<sdk key>'
  user_hash TEXT NOT NULL,
  payload TEXT NOT NULL,
  received_at INTEGER
);

-- Custom cache key (StatsigOptions.customCacheKey) -> cache_key. The 10 most recently used
-- entries are kept; cached_values rows nobody maps to are deleted with them.
CREATE TABLE IF NOT EXISTS cache_key_map (
  scoped_key TEXT PRIMARY KEY,
  cache_key TEXT NOT NULL,
  last_used_at INTEGER NOT NULL
);

-- Values kept for getExperiment/getLayer(keepDeviceValue = true).
-- owner is the cache_key for user-based experiments and '' for device-based ones.
CREATE TABLE IF NOT EXISTS sticky_value (
  owner TEXT NOT NULL,
  name_hash TEXT NOT NULL,
  spec TEXT NOT NULL,                -- the experiment/layer as served, JSON (v1 shape)
  PRIMARY KEY (owner, name_hash)
);

-- overrideGate/overrideConfig/overrideLayer. kind: 'gate' | 'config' | 'layer'.
CREATE TABLE IF NOT EXISTS local_override (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  value TEXT NOT NULL,               -- JSON: true/false or an object
  PRIMARY KEY (kind, name)
);

-- Event batches that could not be delivered, retried on the next start / app focus.
CREATE TABLE IF NOT EXISTS failed_log (
  id INTEGER PRIMARY KEY,
  sdk_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  body TEXT NOT NULL,
  retry_count INTEGER NOT NULL,
  event_count TEXT
);

-- Fallback hosts discovered after the default API domain failed (NetworkFallbackResolver).
CREATE TABLE IF NOT EXISTS fallback_url (
  endpoint TEXT PRIMARY KEY,         -- 'initialize' | 'log_event'
  url TEXT NOT NULL,
  previous TEXT NOT NULL,            -- JSON array of urls tried before
  expires_at INTEGER NOT NULL
);

-- Per client ---------------------------------------------------------------------------------

CREATE TEMP VIEW IF NOT EXISTS clock AS
SELECT CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER) AS now_ms;

-- The current user and the values in use. Exactly one row once session_start has run.
CREATE TEMP TABLE IF NOT EXISTS session (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  sdk_key TEXT NOT NULL,
  user TEXT NOT NULL,                -- evaluation copy of the user, JSON
  first_user TEXT NOT NULL,          -- user the client started with (diagnostics events)
  user_hash TEXT NOT NULL,
  scoped_key TEXT NOT NULL,
  cache_key TEXT NOT NULL,
  source TEXT NOT NULL,              -- EvalSource name
  received_at INTEGER,
  -- header of the values in use (entity rows hold the rest)
  values_user_hash TEXT NOT NULL DEFAULT '',
  lcut INTEGER NOT NULL DEFAULT 0,
  has_updates INTEGER NOT NULL DEFAULT 0,
  hash_used TEXT,
  derived_fields TEXT,
  full_checksum TEXT,
  param_stores TEXT,
  sdk_flags TEXT,
  sdk_configs TEXT,
  bootstrap_metadata TEXT,
  options TEXT                       -- StatsigOptions logging copy, JSON
);

-- Gates, configs, layers and parameter stores of the values in use, keyed as served (the server
-- usually sends djb2-hashed names). Fields are extracted once, when values are applied, so that
-- lookups never parse JSON. Columns holding JSON say so.
CREATE TEMP TABLE IF NOT EXISTS entity (
  kind TEXT NOT NULL,                -- 'gate' | 'config' | 'layer' | 'param_store'
  name TEXT NOT NULL,
  value TEXT,                        -- JSON: true/false for gates, an object otherwise
  rule_id TEXT,
  group_name TEXT,
  id_type TEXT,
  secondary_exposures TEXT,          -- JSON array
  undelegated_secondary_exposures TEXT, -- JSON array
  is_user_in_experiment INTEGER NOT NULL DEFAULT 0,
  is_experiment_active INTEGER NOT NULL DEFAULT 0,
  is_device_based INTEGER NOT NULL DEFAULT 0,
  allocated_experiment_name TEXT,
  explicit_parameters TEXT,          -- JSON array
  passed INTEGER,                    -- NULL when not served
  parameter_rule_ids TEXT,           -- JSON object
  PRIMARY KEY (kind, name)
) WITHOUT ROWID;

-- This client's copy of the overrides (kept in sync with local_override, which persists them).
CREATE TEMP TABLE IF NOT EXISTS override (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (kind, name)
) WITHOUT ROWID;

-- Values that only live for this client (bootstrap values), by cache key.
CREATE TEMP TABLE IF NOT EXISTS memory_values (
  cache_key TEXT PRIMARY KEY,
  scoped_key TEXT NOT NULL,
  payload TEXT NOT NULL,
  bootstrap_metadata TEXT
);
